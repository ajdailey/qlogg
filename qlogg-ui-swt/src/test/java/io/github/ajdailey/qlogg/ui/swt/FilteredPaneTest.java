package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.KeyStroke;
import io.github.ajdailey.qlogg.app.command.MenuTable;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.controller.FilteredController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.search.PredefinedFilter;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.model.Visibility;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The filtered pane, quick find bar, context menu and dialogs of the composed window. */
@Tag("swt")
class FilteredPaneTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final int LINES = 200;

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
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            services,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            // The setup needs 4 filtered rows; the default split keeps the minimum of 3 in a
            // window clamped to a 640x480 screen, and an even split gives 4.
            Preferences.DEFAULTS.withSplitPercent(50));
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 900, 600), null);
    window.open();
    controller.openFile(write(), 0);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController document = controller.activeDocument();
                return window.results() != null
                    && document != null
                    && !document.state().indexing()
                    && document.viewport().rows() > 3
                    && document.filtered().viewport().rows() > 3;
              }
            }),
        new Supplier<String>() {
          @Override
          public String get() {
            DocumentController document = controller.activeDocument();
            return document == null
                ? "no document"
                : "rows "
                    + document.viewport().rows()
                    + ", filtered "
                    + document.filtered().viewport().rows()
                    + ", shell "
                    + window.shell().getSize();
          }
        });
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
    for (Shell shell : display.getShells()) {
      shell.dispose();
    }
    display.dispose();
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private Path write() throws IOException {
    Path file = temp.resolve("pane.log");
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      for (int i = 1; i <= LINES; i++) {
        out.write(i % 10 == 0 ? "event id_" + i + " done" : "line " + i);
        out.write('\n');
      }
    }
    return file;
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private DocumentController document() {
    return controller.activeDocument();
  }

  private Canvas mainCanvas() {
    return window.canvas().control();
  }

  private Canvas filteredCanvas() {
    return window.results().canvas().control();
  }

  private static void key(Canvas canvas, int stateMask, int keyCode, char character) {
    Event event = new Event();
    event.stateMask = stateMask;
    event.keyCode = keyCode;
    event.character = character;
    canvas.notifyListeners(SWT.KeyDown, event);
  }

  private void searchFor(final String text) {
    final SearchBar bar = window.searchBar();
    bar.pattern().setText(text);
    bar.searchButton().notifyListeners(SWT.Selection, new Event());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document().search().state().text().equals(text)
                    && bar.statusText().endsWith("matches")
                    && window.busy().active() == 0;
              }
            }),
        bar.statusText());
  }

  // Accessible names.
  private static String nameOf(Control control, AccessibleName listener) {
    AccessibleEvent event = new AccessibleEvent(control.getAccessible());
    event.childID = ACC.CHILDID_SELF;
    listener.getName(event);
    return event.result;
  }

  @Test
  void viewsCarryAccessibleNamesAndTheFilteredViewItsCount() {
    assertEquals(
        UiText.ACCESSIBLE_LOG_VIEW.text(), nameOf(mainCanvas(), window.canvas().accessibleName()));
    assertEquals(
        UiText.ACCESSIBLE_OVERVIEW.text(),
        nameOf(window.overviewBar().control(), window.overviewBar().accessibleName()));
    assertEquals(
        UiText.ACCESSIBLE_FILTERED_VIEW.text(),
        nameOf(filteredCanvas(), window.results().canvas().accessibleName()));
    searchFor("event");
    assertFilteredName(UiText.ACCESSIBLE_FILTERED_VIEW_COUNT.format(20L));
    controller.execute(CommandId.CLEAR_RESULTS);
    assertFilteredName(UiText.ACCESSIBLE_FILTERED_VIEW.text());
    assertEquals(0, document().search().resultCount());
  }

  private void assertFilteredName(final String expected) {
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return expected.equals(
                    nameOf(filteredCanvas(), window.results().canvas().accessibleName()));
              }
            }),
        nameOf(filteredCanvas(), window.results().canvas().accessibleName()));
  }

  @Test
  void clickingAResultShowsItsLineInTheMainView() {
    searchFor("event");
    final FilteredController filtered = document().filtered();
    assertEquals(20, filtered.viewport().lineCount());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return filtered.lines().contains(2);
              }
            }));
    int row = window.results().canvas().lineHeight();
    Event down = new Event();
    down.button = 1;
    down.x = 200;
    down.y = 2 * row + 1;
    filteredCanvas().notifyListeners(SWT.MouseDown, down);
    filteredCanvas().notifyListeners(SWT.MouseUp, down);
    assertEquals(2, filtered.selection().caret().line());
    assertEquals(29, document().selection().caret().line());
    assertEquals("event id_30 done", filtered.lines().text(2));
    assertEquals(29, filtered.lines().sourceLine(2));
    assertTrue(window.positionText().startsWith("Ln 30/200"), window.positionText());
  }

  @Test
  void visibilityKeysAndComboAgree() {
    searchFor("event");
    key(mainCanvas(), 0, 'm', 'm');
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !document().markingRunning()
                    && document().filtered().viewport().lineCount() == 21;
              }
            }));
    assertTrue(document().marks().contains(0));
    key(mainCanvas(), 0, '2', '2');
    assertEquals(Visibility.MARKS, document().filtered().visibility());
    assertEquals(1, window.searchBar().visibility().getSelectionIndex());
    key(filteredCanvas(), SWT.MOD2, 'v', 'V');
    assertEquals(Visibility.MARKS_AND_MATCHES, document().filtered().visibility());
    window.searchBar().visibility().select(2);
    window.searchBar().visibility().notifyListeners(SWT.Selection, new Event());
    assertEquals(Visibility.MATCHES, document().filtered().visibility());
    assertEquals(20, document().filtered().viewport().lineCount());
  }

  @Test
  void plusAndMinusMoveTheSash() {
    int before = window.splitPercent();
    key(mainCanvas(), 0, '+', '+');
    assertEquals(before + ApplicationController.SPLIT_STEP, window.splitPercent());
    key(mainCanvas(), 0, '-', '-');
    key(mainCanvas(), 0, '-', '-');
    assertEquals(before - ApplicationController.SPLIT_STEP, window.splitPercent());
    controller.execute(CommandId.SAVE_SPLIT);
    assertEquals(
        before - ApplicationController.SPLIT_STEP, controller.preferences().splitPercent());
    assertEquals(
        UiText.NOTICE_SPLIT_SAVED.format(before - ApplicationController.SPLIT_STEP),
        window.noticeText());
  }

  /** Opening a file must not build either view's context menu: 35-60 ms each under GTK 4. */
  @Test
  void theContextMenuIsBuiltOnTheFirstMenuDetect() {
    assertNull(mainCanvas().getMenu());
    assertNull(filteredCanvas().getMenu());
    menuDetect(mainCanvas());
    Menu built = mainCanvas().getMenu();
    assertNotNull(built);
    assertNull(filteredCanvas().getMenu());
    menuDetect(mainCanvas());
    assertSame(built, mainCanvas().getMenu());
  }

  private static void menuDetect(Canvas canvas) {
    Event event = new Event();
    Point at = canvas.toDisplay(10, 1);
    event.x = at.x;
    event.y = at.y;
    canvas.notifyListeners(SWT.MenuDetect, event);
  }

  @Test
  void bothViewsHaveTheContextMenu() {
    List<CommandId> expected = new ArrayList<CommandId>();
    for (CommandId command : MenuTable.LOG_VIEW_MENU) {
      if (command != null) {
        expected.add(command);
      }
    }
    for (Canvas canvas : new Canvas[] {mainCanvas(), filteredCanvas()}) {
      menuDetect(canvas);
      List<CommandId> found = new ArrayList<CommandId>();
      for (MenuItem item : canvas.getMenu().getItems()) {
        if (item.getData() instanceof CommandId command) {
          found.add(command);
        }
        if (item.getText().equals(UiText.MENU_SEARCH_LIMITS.text())) {
          for (MenuItem limit : item.getMenu().getItems()) {
            found.add((CommandId) limit.getData());
          }
        }
      }
      assertEquals(expected, found);
    }
    window.canvas().menuAt(10, 3 * window.canvas().lineHeight() + 1);
    assertEquals(3, document().selection().caret().line());
    MenuItem copyLine = null;
    for (MenuItem item : mainCanvas().getMenu().getItems()) {
      if (item.getData() == CommandId.COPY_LINE) {
        copyLine = item;
      }
    }
    assertNotNull(copyLine);
    assertEquals(CommandId.COPY_LINE.label(), copyLine.getText());
  }

  @Test
  void doubleClickSelectsAWordAndQuickFindWalksIt() {
    final DocumentController document = document();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document.lines().contains(9);
              }
            }));
    window.canvas().document().selectWordAt(new TextPosition(9, 8));
    assertEquals(
        new Selection(new TextPosition(9, 6), new TextPosition(9, 11)), document.selection());
    assertTrue(window.positionText().endsWith("Sel 5 chars"), window.positionText());
    key(mainCanvas(), 0, '*', '*');
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document.selection().caret().line() == 99 && window.busy().active() == 0;
              }
            }));
    QuickFindBar bar = window.quickFindBar();
    assertTrue(bar.control().isVisible());
    assertEquals("id_10", bar.field().getText());
    controller.quickFind().close();
    assertFalse(bar.control().isVisible());
    controller.execute(CommandId.FIND);
    assertTrue(bar.control().isVisible());
    bar.field().setText("line 200");
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !controller.quickFind().state().running()
                    && !controller.quickFind().state().notice().isEmpty();
              }
            }));
    assertEquals(UiText.NOTICE_END_OF_FILE.text(), bar.noticeText());
    assertEquals(new KeyStroke(0, KeyStroke.SPACE), SwtKeys.stroke(0, ' ', ' '));
  }

  @Test
  void keptResultsShowAsClosableTabs() {
    searchFor("event");
    controller.execute(CommandId.TOGGLE_KEEP_RESULTS);
    searchFor("line 1");
    assertEquals(2, window.results().sets().getItemCount());
    assertEquals("event (20)", window.results().sets().getItem(1).getText());
    assertSame(document().search().kept().get(0), window.results().sets().getItem(1).getData());
    window.results().sets().setSelection(1);
    window.results().sets().notifyListeners(SWT.Selection, new Event());
    assertEquals(0, document().search().shown());
    assertEquals(20, document().filtered().viewport().lineCount());
    document().search().removeKept(0);
    assertEquals(1, window.results().sets().getItemCount());
  }

  @Test
  void closingTheCurrentResultTabClearsTheResultsAndKeepsTheTab() {
    final CTabFolder sets = window.results().sets();
    assertFalse(sets.getItem(0).getShowClose());
    searchFor("event");
    controller.execute(CommandId.TOGGLE_KEEP_RESULTS);
    searchFor("line 1");
    assertTrue(sets.getItem(0).getShowClose());
    window.results().closeTab(sets.getItem(0));
    assertEquals(SearchViewState.Phase.IDLE, document().search().state().phase());
    assertEquals(2, sets.getItemCount());
    assertFalse(sets.getItem(0).getShowClose());
    assertEquals(1, document().search().kept().size());
  }

  // Tab strip buttons.

  @Test
  void keptResultsPastTheStripWidthAreListed() {
    controller.execute(CommandId.TOGGLE_KEEP_RESULTS);
    for (int i = 0; i < 12; i++) {
      searchFor("line " + i);
    }
    final CTabFolder sets = window.results().sets();
    final TabStripButtons buttons = window.results().tabButtons();
    assertEquals(1, buttons.buttons().size(), "the list button only");
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                int hidden = 0;
                for (CTabItem item : sets.getItems()) {
                  hidden += item.isShowing() ? 0 : 1;
                }
                return hidden > 0 && buttons.hiddenCount() == hidden;
              }
            }),
        "hidden count " + buttons.hiddenCount());
    buttons.tabList().show(buttons.listButton());
    Menu menu = buttons.tabList().menu();
    menu.setVisible(false);
    int entries = 0;
    MenuItem first = null;
    for (MenuItem item : menu.getItems()) {
      if ((item.getStyle() & SWT.SEPARATOR) == 0) {
        first = first == null ? item : first;
        entries++;
      }
    }
    assertEquals(sets.getItemCount(), entries);
    first.setSelection(true);
    first.notifyListeners(SWT.Selection, new Event());
    assertEquals(0, sets.getSelectionIndex());
    assertEquals(-1, document().search().shown(), "the current search shows");
  }

  @Test
  void anAmpersandInASearchShowsLiterallyOnItsResultTab() {
    searchFor("a&b");
    controller.execute(CommandId.TOGGLE_KEEP_RESULTS);
    searchFor("event");
    assertEquals("a&&b (0)", window.results().sets().getItem(1).getText());
    assertEquals("a&&b", window.results().sets().getItem(1).getToolTipText());
    searchFor("c&d");
    assertEquals("c&&d (0)", window.results().sets().getItem(0).getText());
    assertEquals("c&&d", window.results().sets().getItem(0).getToolTipText());
  }

  @Test
  void filtersMenuHistoryAndDialogs() {
    controller.setFilters(
        List.of(
            new PredefinedFilter("Events", "event", MatchMode.REGEX),
            new PredefinedFilter("Ten", "id_10 ", MatchMode.LITERAL)));
    searchFor("line 5");
    final SearchBar bar = window.searchBar();
    bar.filterToggled(controller.preferences().filters(), 0);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return bar.statusText().equals("20 matches") && window.busy().active() == 0;
              }
            }),
        bar.statusText());
    assertEquals("event", bar.pattern().getText());
    bar.filterToggled(controller.preferences().filters(), 1);
    assertEquals("event|id_10 ", bar.pattern().getText());
    assertEquals(
        List.of("event|id_10 ", "event", "line 5"), controller.preferences().searchHistory());
    assertEquals(3, bar.pattern().getItemCount());
    bar.clearButton().notifyListeners(SWT.Selection, new Event());
    assertEquals("", bar.pattern().getText());

    controller.execute(CommandId.EDIT_HISTORY);
    Shell history = shellTitled(CommandId.EDIT_HISTORY.title());
    assertNotNull(history);
    history.dispose();
    controller.execute(CommandId.PREDEFINED_FILTERS);
    Shell filters = shellTitled(CommandId.PREDEFINED_FILTERS.title());
    assertNotNull(filters);
    assertEquals(3, bar.pattern().getMenu().getItemCount());
  }

  @Test
  void theSearchRowSitsInTheBottomPaneOverTheResultTabs() {
    SearchBar bar = window.searchBar();
    Composite bottom = bar.control().getParent();
    assertSame(window.split(), bottom.getParent());
    assertSame(bar.control(), bottom.getChildren()[0]);
    Control[] row = bar.control().getChildren();
    assertSame(bar.visibility(), row[0]);
    assertTrue(row[1] instanceof ToolBar, "toggles after the visibility combo");
    assertSame(bar.filtersButton().getParent(), row[2]);
    assertSame(bar.pattern(), row[3]);
    for (ToolItem toggle : bar.toggles()) {
      assertEquals(SWT.CHECK, toggle.getStyle() & SWT.CHECK);
      assertNotNull(toggle.getImage());
      assertFalse(toggle.getToolTipText().isEmpty());
    }
    assertTrue(bar.toggles()[0].getToolTipText().endsWith("(4)"), "match case names its key");
    assertTrue(bar.pattern().getSize().x >= SearchBar.MIN_PATTERN_WIDTH, "pattern field width");
    Rectangle sets = window.results().sets().getBounds();
    Point setsAt = window.results().sets().toDisplay(0, 0);
    Point rowAt = bar.control().toDisplay(0, 0);
    assertEquals(rowAt.x, setsAt.x, "result tabs aligned with the search row");
    assertEquals(bar.control().getSize().x, sets.width, "result tabs span the row");
    assertTrue(setsAt.y >= rowAt.y + bar.control().getSize().y, "result tabs under the row");
    Point canvasAt = filteredCanvas().toDisplay(0, 0);
    assertTrue(canvasAt.y >= setsAt.y + sets.height, "filtered view under the tabs");
  }

  // Context lines and range filter.

  /** Paints a canvas into an image and returns the pixel at a point. */
  private RGB pixel(Canvas canvas, int x, int y) {
    Image image = new Image(display, canvas.getSize().x, canvas.getSize().y);
    GC gc = new GC(image);
    try {
      Gtk4.assertPrinted(canvas.print(gc));
      ImageData data = image.getImageData();
      return data.palette.getRGB(data.getPixel(x, y));
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private static RGB rgb(Rgb color) {
    return new RGB(color.red(), color.green(), color.blue());
  }

  @Test
  void contextRowsAreShadedAndGroupsRuled() {
    SearchBar bar = window.searchBar();
    bar.contextFields()[0].setText("1");
    bar.contextFields()[1].setText("1");
    assertEquals(1, document().search().options().contextBefore());
    searchFor("event");
    final FilteredController filtered = document().filtered();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return filtered.viewport().lineCount() == 59 && filtered.lines().contains(3);
              }
            }),
        "rows: " + filtered.viewport().lineCount());
    assertEquals(20, document().search().resultCount(), "the count is of matches");
    assertEquals(10, filtered.lines().sourceLine(2));
    Canvas canvas = filteredCanvas();
    Palette palette = window.results().canvas().palette();
    int height = window.results().canvas().lineHeight();
    int x = canvas.getSize().x / 2;
    assertEquals(rgb(palette.contextRow()), pixel(canvas, x, 2 * height + height / 2), "context");
    assertEquals(rgb(palette.background()), pixel(canvas, x, height + height / 2), "match");
    assertEquals(rgb(palette.lineNumbers()), pixel(canvas, x, 3 * height), "group rule");
  }

  @Test
  void theRangeEndFieldShowsWhileRangeIsOn() {
    SearchBar bar = window.searchBar();
    assertFalse(bar.rangeEnd().isVisible());
    assertNotNull(bar.rangeToggle().getImage());
    bar.rangeToggle().setSelection(true);
    bar.rangeToggle().notifyListeners(SWT.Selection, new Event());
    assertTrue(bar.rangeEnd().isVisible());
    assertTrue(document().search().options().range());
    bar.rangeEnd().setText("id_50 ");
    bar.pattern().setText("id_20 ");
    bar.searchButton().notifyListeners(SWT.Selection, new Event());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document().search().state().phase() == SearchViewState.Phase.DONE
                    && window.busy().active() == 0;
              }
            }),
        bar.statusText());
    assertEquals(31, document().search().resultCount(), "lines 20 through 50");
    controller.execute(CommandId.TOGGLE_RANGE);
    assertFalse(bar.rangeEnd().isVisible());
    assertFalse(bar.rangeToggle().getSelection());
  }

  @Test
  void theSplitterHasAGrip() {
    Sash sash = window.sash();
    assertNotNull(sash);
    assertEquals(UiText.SPLIT_GRIP.text(), sash.getToolTipText());
  }

  @Test
  void predefinedFiltersDialogEditsRowsInPlace() {
    FiltersDialog dialog = new FiltersDialog(window.shell(), controller);
    dialog.open();
    Table table = dialog.table();
    assertEquals(3, table.getColumnCount());
    assertEquals("Name", table.getColumn(0).getText());
    assertEquals("Pattern", table.getColumn(1).getText());
    assertEquals("Regex", table.getColumn(2).getText());
    dialog.add();
    assertEquals(1, table.getItemCount());
    Text name = dialog.edit(0, FiltersDialog.NAME);
    name.setText("Errors");
    name.notifyListeners(SWT.FocusOut, new Event());
    Text pattern = dialog.edit(0, FiltersDialog.PATTERN);
    pattern.setText("ERROR|FATAL");
    Event enter = new Event();
    enter.detail = SWT.TRAVERSE_RETURN;
    pattern.notifyListeners(SWT.Traverse, enter);
    assertTrue(pattern.isDisposed());
    dialog.toggleRegex(0);
    assertEquals(
        new PredefinedFilter("Errors", "ERROR|FATAL", MatchMode.LITERAL), dialog.working().get(0));
    assertEquals("Errors", table.getItem(0).getText(0));
    dialog.apply();
    assertFalse(dialog.shell().isDisposed());
    assertEquals(dialog.working(), controller.preferences().filters());
    dialog.remove();
    assertEquals(0, table.getItemCount());
    dialog.shell().dispose();
  }

  private Shell shellTitled(final String title) {
    final Shell[] found = new Shell[1];
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            for (Shell shell : display.getShells()) {
              if (!shell.isDisposed() && title.equals(shell.getText())) {
                found[0] = shell;
                return true;
              }
            }
            return false;
          }
        });
    return found[0];
  }
}
