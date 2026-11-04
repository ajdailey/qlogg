package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.highlight.HighlightSelector;
import io.github.ajdailey.qlogg.app.scratch.Transforms;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
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
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wheel modifiers, block drags, text wrap, whitespace and fold painting, the scratchpad, the search
 * across open files and the Automatic Highlighters dialog, driven through SWT events under Xvfb.
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

  @BeforeEach
  void open() throws Exception {
    display = new Display();
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
    canvas().notifyListeners(type, event);
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
    Text text = pad.current();
    assertEquals("aGVsbG8=", text.getText());
    pad.add("1700000000", true);
    text.selectAll();
    text.notifyListeners(SWT.MouseUp, new Event());
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
