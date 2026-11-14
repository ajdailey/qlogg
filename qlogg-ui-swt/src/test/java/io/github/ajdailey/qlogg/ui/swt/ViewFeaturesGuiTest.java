package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.controller.MultiSearchText;
import io.github.ajdailey.qlogg.app.highlight.HighlightSelector;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.scratch.Transforms;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchLimit;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wheel modifiers, block drags, text wrap, line wrap markers, whitespace and fold painting, the
 * scratchpad, the search across open files and the Automatic Highlighters dialog, driven through
 * SWT events under Xvfb.
 */
@Tag("swt")
class ViewFeaturesGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private PostedPointer pointer;

  @BeforeEach
  void open() throws Exception {
    display = new Display();
    pointer = new PostedPointer(display);
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    MemoryBudget budget = MemoryBudget.of(1L << 28);
    DocumentServices services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(2, budget)),
            new SearchEngine(
                workers, StandardMatchers.INSTANCE, SearchSettings.defaults(2, budget)),
            new BlockCache(65536, 1 << 20),
            budget,
            StandardCharsets.ISO_8859_1);
    window = new MainWindow(display);
    Preferences preferences =
        Preferences.DEFAULTS.withHighlights(
            Preferences.DEFAULTS
                .highlights()
                .withSets(List.of(new HighlightSet("errors", "Errors", List.of()))));
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            services,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            preferences);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 800, 400), null);
    window.open();
    controller.openFile(write("view.log"), 0);
    waitForDocument("view.log");
  }

  private void waitForDocument(final String name) {
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController document = controller.activeDocument();
                return window.canvas() != null
                    && document != null
                    && document.state().title().equals(name)
                    && !document.state().indexing()
                    && document.lines().contains(document.viewport().topLine())
                    && document.viewport().rows() > 5;
              }
            }));
  }

  @AfterEach
  void close() {
    controller.shutdown();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.shell().isDisposed();
          }
        });
    display.dispose();
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  /** 100 lines; line 0 is 400 columns wide, line 2 holds a tab and a trailing space. */
  private Path write(String name) throws IOException {
    StringBuilder text = new StringBuilder();
    text.append("x".repeat(400)).append('\n');
    text.append("Exception: hit\n");
    text.append("\tat a.B(B.java:1) \n");
    for (int i = 3; i < 100; i++) {
      text.append("line ").append(i).append(i % 7 == 0 ? " hit" : "").append('\n');
    }
    Path file = temp.resolve(name);
    Files.writeString(file, text.toString(), StandardCharsets.US_ASCII);
    return file;
  }

  private DocumentController document() {
    return controller.activeDocument();
  }

  private Canvas canvas() {
    return window.canvas().control();
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private void mouse(int type, int x, int y, int stateMask) {
    Event event = new Event();
    event.button = 1;
    event.x = x;
    event.y = y;
    event.stateMask = stateMask;
    pointer.post(canvas(), type, event);
  }

  private void wheel(int count, int stateMask) {
    Event event = new Event();
    event.count = count;
    event.stateMask = stateMask;
    canvas().notifyListeners(SWT.MouseVerticalWheel, event);
  }

  private void paint() {
    Image image = new Image(display, canvas().getSize().x, canvas().getSize().y);
    GC gc = new GC(image);
    try {
      Gtk4.assertPrinted(canvas().print(gc));
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  @Test
  void altWheelScrollsSidewaysAndShiftWheelByPages() {
    wheel(-3, SWT.ALT);
    assertEquals(3, document().viewport().firstColumn());
    assertEquals(0, document().viewport().topLine());
    int rows = document().viewport().rows();
    wheel(-1, SWT.SHIFT);
    assertEquals(rows, document().viewport().topLine());
    wheel(1, SWT.SHIFT);
    assertEquals(0, document().viewport().topLine());
  }

  @Test
  void anAltDragSelectsABlock() {
    LogCanvas view = window.canvas();
    int x0 = view.gutterWidth() + view.columnOffset(2);
    int x1 = view.gutterWidth() + view.columnOffset(6);
    mouse(SWT.MouseDown, x0, 1, SWT.ALT);
    mouse(SWT.MouseMove, x1, 3 * view.lineHeight() + 1, SWT.ALT);
    mouse(SWT.MouseUp, x1, 3 * view.lineHeight() + 1, SWT.ALT);
    assertTrue(document().blockSelection());
    assertEquals(new TextPosition(0, 2), document().selection().anchor());
    assertEquals(new TextPosition(3, 6), document().selection().caret());
    paint();
    mouse(SWT.MouseDown, x0, 1, 0);
    mouse(SWT.MouseMove, x1, 1, 0);
    mouse(SWT.MouseUp, x1, 1, 0);
    assertTrue(!document().blockSelection());
  }

  /** Dispatches events for the given time, measured by a display timer. */
  private void dispatchFor(int millis) {
    final boolean[] done = new boolean[1];
    display.timerExec(
        millis,
        new Runnable() {
          @Override
          public void run() {
            done[0] = true;
          }
        });
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return done[0];
              }
            }));
  }

  /** Writes a PNG of the window when {@code qlogg.shots} names a directory. */
  private void shoot(String name) {
    String dir = System.getProperty("qlogg.shots");
    if (dir == null) {
      return;
    }
    while (display.readAndDispatch()) {
      // Flush pending paints.
    }
    Point size = window.shell().getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(image);
    try {
      window.shell().print(gc);
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {image.getImageData()};
      loader.save(Path.of(dir, name).toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  /** Presses on the first row and moves the pointer below the view, then holds it still. */
  private void dragBelow() {
    LogCanvas view = window.canvas();
    int x = view.gutterWidth() + view.columnOffset(2);
    int below = canvas().getClientArea().height + 10;
    final long top = document().viewport().topLine();
    mouse(SWT.MouseDown, x, 1, 0);
    mouse(SWT.MouseMove, x, below, 0);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document().viewport().topLine() >= top + 3;
              }
            }));
    assertTrue(view.autoscrolling());
    assertTrue(document().selection().caret().line() > document().viewport().topLine());
  }

  @Test
  void aDragHeldBelowTheViewScrollsUntilReleaseFocusLossOrDispose() {
    final int rows = document().viewport().rows();
    window.shell().setSize(1100, 700);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document().viewport().rows() > rows;
              }
            }));
    final LogCanvas view = window.canvas();
    dragBelow();
    shoot("drag-autoscroll.png");
    mouse(SWT.MouseUp, 0, 0, 0);
    assertFalse(view.autoscrolling());
    long top = document().viewport().topLine();
    dispatchFor(4 * LogCanvas.AUTOSCROLL_MILLIS);
    assertEquals(top, document().viewport().topLine());

    document().scrollLines(-top);
    dragBelow();
    canvas().notifyListeners(SWT.FocusOut, new Event());
    assertFalse(view.autoscrolling());
    top = document().viewport().topLine();
    dispatchFor(4 * LogCanvas.AUTOSCROLL_MILLIS);
    assertEquals(top, document().viewport().topLine());

    document().scrollLines(-top);
    dragBelow();
    controller.execute(CommandId.CLOSE);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return view.control().isDisposed();
              }
            }));
    assertFalse(view.autoscrolling());
    dispatchFor(4 * LogCanvas.AUTOSCROLL_MILLIS);
  }

  /** Turns text wrap on for the shown document and waits for the wrapped read. */
  private void wrapOn() {
    controller.execute(CommandId.TOGGLE_WRAP);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                int columns = document().viewport().columns();
                return document().wrap()
                    && document().lines().contains(0)
                    && document().lines().columns() >= 2 * columns;
              }
            }));
  }

  /**
   * The first row ends with {@code " alpha."} and the second starts with {@code omega}: a double
   * click past the first row's right edge selects {@code alpha}, the word at that row's end.
   */
  @Test
  void aDoubleClickPastAWrappedRowsEndSelectsTheWordAtItsEnd() throws IOException {
    wrapOn();
    int columns = document().viewport().columns();
    Path file = temp.resolve("words.log");
    Files.writeString(
        file,
        "y".repeat(columns - 7) + " alpha.omega tail " + "z".repeat(columns) + "\nnext\n",
        StandardCharsets.US_ASCII);
    controller.openFile(file, 0);
    waitForDocument("words.log");
    wrapOn();
    assertEquals(columns, document().viewport().columns(), "wrap width");
    LogCanvas view = window.canvas();
    int past = view.gutterWidth() + view.columnOffset(columns + 2L);
    mouse(SWT.MouseDoubleClick, past, 1, 0);
    assertEquals(
        new Selection(new TextPosition(0, columns - 6L), new TextPosition(0, columns - 1L)),
        document().selection());
  }

  @Test
  void wrappedRowsMapClicksToTheirSegmentAndReportTheFit() {
    controller.execute(CommandId.TOGGLE_WRAP);
    final int screen = canvas().getClientArea().height / window.canvas().lineHeight();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document().lines().contains(0)
                    && document().lines().columns() >= 400
                    && document().viewport().rows() < screen;
              }
            }));
    int columns = document().viewport().columns();
    int segments = (400 + columns - 1) / columns;
    assertEquals(screen - segments + 1, document().viewport().rows());
    LogCanvas view = window.canvas();
    TextPosition second =
        view.hit(view.gutterWidth() + view.columnOffset(2), view.lineHeight() + 1);
    assertEquals(new TextPosition(0, columns + 2L), second);
    TextPosition next = view.hit(view.gutterWidth(), segments * view.lineHeight() + 1);
    assertEquals(1, next.line());
    paint();
  }

  // Line wrap.

  /** Waits until the shown document's line {@code row} is wrapped in place and read. */
  private void awaitLineWrapped(final long row) {
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document().wrappedLines().contains(row)
                    && window.canvas().wrappedRow() == row;
              }
            }));
  }

  /** Waits until the main view holds its first rows. */
  private void awaitRows() {
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document().lines().contains(1);
              }
            }));
  }

  /** Reports whether any pixel of a row's cell in the marker strip differs from the color. */
  private boolean markerInk(ImageData data, int row, Rgb color) {
    LogCanvas view = window.canvas();
    int right = canvas().getClientArea().width;
    int top = row * view.lineHeight();
    RGB wanted = new RGB(color.red(), color.green(), color.blue());
    for (int x = right - view.markerWidth(); x < right; x++) {
      for (int y = top; y < top + view.lineHeight(); y++) {
        if (!wanted.equals(data.palette.getRGB(data.getPixel(x, y)))) {
          return true;
        }
      }
    }
    return false;
  }

  private ImageData capture() {
    Image image = new Image(display, canvas().getSize().x, canvas().getSize().y);
    GC gc = new GC(image);
    try {
      Gtk4.assertPrinted(canvas().print(gc));
      return image.getImageData();
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  /** Returns the context menu's item of a command after the menu's {@code SWT.Show}. */
  private MenuItem contextItem(LogCanvas view, CommandId command) {
    view.menuAt(10, 1);
    Menu menu = view.control().getMenu();
    menu.setVisible(false);
    menu.notifyListeners(SWT.Show, new Event());
    for (MenuItem item : menu.getItems()) {
      if (item.getData() == command) {
        return item;
      }
    }
    throw new AssertionError(command.name());
  }

  @Test
  void theContextMenusDisableWrapLineWithTextWrap() {
    LogCanvas filtered = window.results().canvas();
    assertTrue(contextItem(window.canvas(), CommandId.WRAP_LINE).isEnabled());
    assertTrue(contextItem(filtered, CommandId.WRAP_LINE).isEnabled());
    assertFalse(contextItem(window.canvas(), CommandId.SCROLL_TABS_TO_TIME).isEnabled());
    controller.execute(CommandId.TOGGLE_WRAP);
    assertFalse(contextItem(window.canvas(), CommandId.WRAP_LINE).isEnabled());
    assertFalse(contextItem(filtered, CommandId.WRAP_LINE).isEnabled());
    controller.execute(CommandId.TOGGLE_WRAP);
    assertTrue(contextItem(window.canvas(), CommandId.WRAP_LINE).isEnabled());
  }

  /**
   * With text wrap off the reported columns exclude the marker strip; with it on they take the
   * whole width right of the gutter.
   */
  @Test
  void theMarkerStripNarrowsTheColumnsWithTextWrapOff() {
    awaitRows();
    final LogCanvas view = window.canvas();
    final int width = canvas().getClientArea().width - view.gutterWidth();
    assertEquals((int) ((width - view.markerWidth()) / view.advance()), columns());
    assertEquals(view.markerWidth(), view.markerMargin());
    controller.execute(CommandId.TOGGLE_WRAP);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return columns() == (int) (width / view.advance());
              }
            }));
    assertEquals(0, view.markerMargin());
  }

  private int columns() {
    return document().viewport().columns();
  }

  /** Line 0 runs 400 columns past the right edge and line 1 fits: only line 0 shows a marker. */
  @Test
  void aWrapMarkerPaintsOnACutRowOnly() {
    awaitRows();
    Rgb gutter = window.canvas().palette().gutter();
    ImageData data = capture();
    assertTrue(markerInk(data, 0, gutter));
    assertFalse(markerInk(data, 1, gutter));
    assertEquals(-1, window.canvas().lineMarker(window.canvas().gutterWidth() + 2, 1));
  }

  @Test
  void aMarkerClickWrapsAndUnwrapsWithoutMovingTheCaret() {
    awaitRows();
    document().select(Selection.caretAt(new TextPosition(5, 2)));
    LogCanvas view = window.canvas();
    int x = canvas().getClientArea().width - view.markerWidth() / 2;
    int y = view.lineHeight() / 2;
    view.tooltip(x, y);
    assertEquals(UiText.TOOLTIP_WRAP_LINE.text(), canvas().getToolTipText());
    mouse(SWT.MouseDown, x, y, 0);
    mouse(SWT.MouseUp, x, y, 0);
    assertEquals(0, document().wrappedLine());
    awaitLineWrapped(0);
    assertEquals(Selection.caretAt(new TextPosition(5, 2)), document().selection());
    view.tooltip(x, y);
    assertEquals(UiText.TOOLTIP_UNWRAP_LINE.text(), canvas().getToolTipText());
    mouse(SWT.MouseDown, x, y, 0);
    mouse(SWT.MouseUp, x, y, 0);
    assertEquals(-1, document().wrappedLine());
    assertEquals(Selection.caretAt(new TextPosition(5, 2)), document().selection());
    view.tooltip(view.gutterWidth() + 2, y);
    assertEquals(null, canvas().getToolTipText());
  }

  /**
   * With the view scrolled 3 columns right and line 0 wrapped in place, its second row maps from
   * column 0 by segment, and line 1 below it maps from column 3.
   */
  @Test
  void aLineWrappedInPlaceHitsBySegmentAndOtherLinesByTheScroll() {
    awaitRows();
    wheel(-3, SWT.ALT);
    assertEquals(3, document().viewport().firstColumn());
    controller.execute(CommandId.WRAP_LINE);
    awaitLineWrapped(0);
    final int screen = canvas().getClientArea().height / window.canvas().lineHeight();
    int columns = document().viewport().columns();
    final int segments = (400 + columns - 1) / columns;
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document().viewport().rows() == screen - segments + 1;
              }
            }));
    LogCanvas view = window.canvas();
    int x = view.gutterWidth() + view.columnOffset(2);
    assertEquals(new TextPosition(0, columns + 2L), view.hit(x, view.lineHeight() + 1));
    assertEquals(new TextPosition(1, 5), view.hit(x, segments * view.lineHeight() + 1));
    assertEquals(3, document().viewport().firstColumn());
    paint();
  }

  @Test
  void whitespaceMarkersAndFoldsPaint() {
    controller.execute(CommandId.TOGGLE_WHITESPACE);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document().lines().contains(2) && document().lines().whitespace(2) != null;
              }
            }));
    document().goToLine(2);
    controller.execute(CommandId.TOGGLE_FOLD);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document().folded(1);
              }
            }));
    assertEquals(3, document().sourceLine(2));
    paint();
  }

  @Test
  void theScratchpadTransformsAndShowsValues() {
    controller.execute(CommandId.SCRATCHPAD);
    ScratchpadWindow pad = window.scratchpad();
    assertNotNull(pad);
    pad.add("hello", false);
    pad.apply(Transforms.Kind.BASE64_ENCODE);
    final Text text = pad.current();
    assertEquals("aGVsbG8=", text.getText());
    // An input over the inline limit transforms on a worker; the tab is read-only until then.
    pad.add("<a/>".repeat(ApplicationController.INLINE_TRANSFORM_CHARS / 4 + 1), true);
    pad.apply(Transforms.Kind.XML_FORMAT);
    assertFalse(text.getEditable());
    assertEquals(UiText.SCRATCHPAD_RUNNING.text(), pad.values().getText());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return text.getEditable();
              }
            }));
    // Native text controls store line breaks as CRLF (win32), CR or LF (cocoa); compare lines.
    String formatted = text.getText().replace("\r\n", "\n").replace('\r', '\n');
    assertTrue(formatted.startsWith("<a/>\n<a/>\n"), formatted.substring(0, 40));
    assertEquals("", pad.values().getText());
    pad.add("1700000000", true);
    text.selectAll();
    pointer.post(text, SWT.MouseUp, new Event());
    assertTrue(pad.values().getText().contains("2023-11-14"), pad.values().getText());
    Event key = new Event();
    key.stateMask = SWT.MOD1;
    key.keyCode = 'n';
    text.notifyListeners(SWT.KeyDown, key);
    assertEquals(2, pad.tabs().getItemCount());
    pad.shell().dispose();
  }

  @Test
  void theMultiSearchWindowListsOneRowPerFileAndShowsItsTab() throws Exception {
    final DocumentController first = document();
    controller.openFile(write("second.log"), 0);
    waitForDocument("second.log");
    document().search().setFieldText("hit");
    controller.execute(CommandId.SEARCH_ALL_FILES);
    final MultiSearchWindow results = window.multiSearch();
    assertNotNull(results);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return results.groups().getItemCount() == 2
                    && first.search().state().phase() == SearchViewState.Phase.DONE
                    && results.groups().getItem(0).getText().endsWith("matches");
              }
            }));
    assertTrue(results.summary().getText().contains("in 2 files"), results.summary().getText());
    Event select = new Event();
    select.item = results.groups().getItem(0);
    results.groups().notifyListeners(SWT.Selection, select);
    assertEquals(first, controller.activeDocument());
    results.shell().dispose();
  }

  @Test
  void aTabClosedDuringTheSearchAcrossFilesDropsItsRowAndKeepsTheSelectedRow() throws Exception {
    DocumentController first = document();
    controller.openFile(write("second.log"), 0);
    waitForDocument("second.log");
    final DocumentController second = document();
    controller.openFile(write("third.log"), 0);
    waitForDocument("third.log");
    final DocumentController third = document();
    third.search().setFieldText("hit");
    controller.execute(CommandId.SEARCH_ALL_FILES);
    final MultiSearchWindow results = window.multiSearch();
    Table rows = results.groups();
    assertEquals(3, rows.getItemCount());
    rows.setSelection(1);
    controller.closeDocument(first);
    assertEquals(2, rows.getItemCount());
    assertSame(second, rows.getItem(0).getData());
    assertSame(third, rows.getItem(1).getData());
    assertEquals(0, rows.getSelectionIndex(), "the selected row stays on its file");
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return second.search().state().phase() == SearchViewState.Phase.DONE
                    && third.search().state().phase() == SearchViewState.Phase.DONE;
              }
            }));
    assertEquals(2, rows.getItemCount());
    assertTrue(rows.getItem(0).getText().startsWith("second.log"), rows.getItem(0).getText());
    assertTrue(rows.getItem(1).getText().startsWith("third.log"), rows.getItem(1).getText());
    assertEquals(0, rows.getSelectionIndex());
    results.shell().dispose();
  }

  @Test
  void aGroupStoppedByALimitOrWithSkippedLinesCarriesItsNotice() throws Exception {
    controller.openFile(write("limited.log"), 0);
    waitForDocument("limited.log");
    SearchOptions options = document().search().options();
    SearchViewState memory =
        new SearchViewState(
            "hit", options, SearchViewState.Phase.DONE, 100, 3, SearchLimit.MEMORY, 0, null);
    assertEquals(
        "limited.log: " + UiText.SEARCH_LIMIT_MEMORY.format(3L),
        MultiSearchText.text(document().state().title(), memory));
    SearchViewState skipped =
        new SearchViewState(
            "hit", options, SearchViewState.Phase.DONE, 100, 3, SearchLimit.NONE, 2, null);
    assertEquals(
        "limited.log: " + skipped.statusText(),
        MultiSearchText.text(document().state().title(), skipped));
    SearchViewState done =
        new SearchViewState(
            "hit", options, SearchViewState.Phase.DONE, 100, 3, SearchLimit.NONE, 0, null);
    assertEquals(
        UiText.MULTI_DONE.format("limited.log", 3L),
        MultiSearchText.text(document().state().title(), done));
  }

  @Test
  void theSelectorsDialogSavesItsRules() {
    SelectorsDialog dialog = new SelectorsDialog(window.shell(), controller);
    dialog.pattern().setText("*.err");
    HighlightSelector rule = dialog.edited();
    assertEquals(new HighlightSelector("*.err", "", "errors"), rule);
    dialog.kind().select(1);
    dialog.pattern().setText("^GET ");
    assertEquals(new HighlightSelector("", "^GET ", "errors"), dialog.edited());
    dialog.shell().dispose();
  }
}
