package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ColumnSettings;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.CommandStates;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.CellRule;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.ColumnLayout;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
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
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The column view in a window: a wide glyph cut at its cell's edge, rule pixels in Light and Dark
 * in the rows and the header, the header scrolled with and without frozen cells, a drag to the
 * narrowest width, a double-click fit, hide and reset from the header menu with the stored keys,
 * the current cell moved by the cell keys, and the commands column view turns off.
 */
@Tag("swt")
class ColumnViewGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  /** A header, then rows whose cells are 12345, INFO, a message and an empty tail. */
  private static final String CSV = rows();

  private static String rows() {
    StringBuilder text = new StringBuilder("id,level,message,tail\n");
    for (int i = 0; i < 40; i++) {
      text.append(10000 + i).append(",INFO,mock message ").append(i).append(",\n");
    }
    return text.toString();
  }

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;

  @BeforeEach
  void open() {
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
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings"))),
            Preferences.DEFAULTS);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 800, 400), null);
    window.open();
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

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private DocumentController document() {
    return controller.activeDocument();
  }

  private ColumnLayout layout() {
    return document().columns().layout();
  }

  private LogCanvas view() {
    return window.canvas();
  }

  /** Opens a file and waits until its column view holds fitted rows from row 0. */
  private void openColumns(final String name, String text) throws Exception {
    Path file = temp.resolve(name);
    Files.writeString(file, text, StandardCharsets.UTF_8);
    controller.openFile(file, 0);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                return shown != null
                    && shown.state().title().equals(name)
                    && !shown.state().indexing()
                    && window.canvas() != null
                    && window.canvas().document().owner() == shown
                    && shown.columns().shown()
                    && !shown.columns().layout().unfitted()
                    && shown.lines().cellView()
                    && shown.lines().contains(0)
                    && view().header().shown();
              }
            }),
        "column view of " + name);
    settled();
  }

  /** Waits until the view's rows are read for the current layout and scroll position. */
  private void settled() {
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              private int quiet;

              @Override
              public boolean getAsBoolean() {
                boolean current =
                    document().lines().cellView()
                        && document().lines().contains(document().viewport().topLine())
                        && document().lines().firstColumn() == document().viewport().firstColumn()
                        && document().lines().frozenColumns() == layout().frozenWidth()
                        && document().lines().width(document().viewport().topLine())
                            == layout().width();
                quiet = current ? quiet + 1 : 0;
                return quiet > 20;
              }
            }));
  }

  private CellGrid cells() {
    return new CellGrid(
        view().grid(),
        view().gutterWidth(),
        document().viewport().firstColumn(),
        layout().frozenWidth());
  }

  private int ruleX(int cell) {
    return cells().ruleX(layout().start(cell + 1));
  }

  private ImageData capture(Control control) {
    Image image = new Image(display, control.getSize().x, control.getSize().y);
    GC gc = new GC(image);
    try {
      Gtk4.assertPrinted(control.print(gc));
      return Pixels.read(image, control);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private static RGB rgbAt(ImageData data, int x, int y) {
    return data.palette.getRGB(data.getPixel(x, y));
  }

  private static RGB rule(Palette palette) {
    int rgb = PaletteColors.packed(CellRule.of(palette));
    return new RGB((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
  }

  private static RGB rgb(io.github.ajdailey.qlogg.core.model.Rgb color) {
    return new RGB(color.red(), color.green(), color.blue());
  }

  private Event at(int x, int y) {
    Event event = new Event();
    event.button = 1;
    event.x = x;
    event.y = y;
    return event;
  }

  @Test
  void aWideGlyphIsCutAtItsCellEdge() throws Exception {
    openColumns("wide.csv", "name,note,tail\n日本語日本語日本語,,x\nmock,,y\n");
    document().columns().sizing().resize(0, 4);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return layout().width(0) == 4;
              }
            }));
    settled();
    int height = view().lineHeight();
    ImageData data = capture(view().control());
    RGB background = rgb(view().palette().background());
    int from = ruleX(0) + 1;
    int to = ruleX(1) - 1;
    for (int x = from; x < to; x++) {
      for (int y = height; y < 2 * height; y++) {
        assertEquals(background, rgbAt(data, x, y), "ink in the empty cell at " + x + "," + y);
      }
    }
    // The cut glyphs still ink their own cell.
    boolean inked = false;
    for (int x = cells().columnX(0); x < ruleX(0) - 1 && !inked; x++) {
      for (int y = height; y < 2 * height && !inked; y++) {
        inked = !rgbAt(data, x, y).equals(background);
      }
    }
    assertTrue(inked);
  }

  @Test
  void rulesTakeTheRuleColorInLightAndDark() throws Exception {
    openColumns("rules.csv", CSV);
    Palette[] palettes = {Palette.LIGHT, Palette.DARK};
    for (Palette palette : palettes) {
      view().setPalette(palette);
      view().control().update();
      view().header().control().update();
      int height = view().lineHeight();
      ImageData rows = capture(view().control());
      ImageData names = capture(view().header().control());
      for (int cell = 0; cell < layout().count(); cell++) {
        int x = ruleX(cell);
        assertEquals(rule(palette), rgbAt(rows, x, 3 * height + height / 2), "row rule " + cell);
        assertEquals(rule(palette), rgbAt(names, x, 1), "header rule " + cell);
      }
      assertEquals(rgb(palette.gutter()), rgbAt(names, ruleX(0) - 2, 1));
      assertEquals(rgb(palette.background()), rgbAt(rows, ruleX(0) - 2, 3 * height + 1));
    }
  }

  @Test
  void theHeaderScrollsExceptItsFrozenCells() throws Exception {
    openColumns("scroll.csv", CSV);
    document().columns().sizing().resize(2, 200);
    settled();
    ColumnHeader header = view().header();
    int before = ruleX(1);
    assertEquals(1, header.boundaryAt(before));
    document().setFirstColumn(3);
    settled();
    int shift = view().grid().columnOffset(3);
    assertNear(before - shift, ruleX(1));
    assertEquals(1, header.boundaryAt(before - shift));
    int columns = document().viewport().columns();
    document().setFirstColumn(0);
    document().columns().sizing().freeze(0);
    settled();
    assertEquals(1, layout().frozen());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document().viewport().columns() == columns - layout().frozenWidth();
              }
            }));
    int frozenRule = ruleX(0);
    int scrolling = ruleX(1);
    document().setFirstColumn(2);
    settled();
    assertEquals(frozenRule, ruleX(0));
    assertEquals(0, header.boundaryAt(frozenRule));
    assertNear(scrolling - view().grid().columnOffset(2), ruleX(1));
    assertEquals(1, header.boundaryAt(scrolling - view().grid().columnOffset(2)));
    assertEquals(0, header.cellAt(frozenRule - 4));
  }

  /** A fractional advance rounds each offset, so a difference of offsets may be 1 px off. */
  private static void assertNear(int expected, int actual) {
    assertTrue(Math.abs(expected - actual) <= 1, "expected " + expected + " but was " + actual);
  }

  @Test
  void aDragResizesToTheNarrowestWidthAndIsStored() throws Exception {
    openColumns("drag.csv", CSV);
    Canvas header = view().header().control();
    header.notifyListeners(SWT.MouseDown, at(ruleX(2), 2));
    header.notifyListeners(SWT.MouseMove, at(view().gutterWidth(), 2));
    header.notifyListeners(SWT.MouseUp, at(view().gutterWidth(), 2));
    assertEquals(ColumnLayout.MIN_WIDTH, layout().width(2));
    ColumnSettings.Entry stored =
        controller.preferences().view().columns().format("csv").entry("message");
    assertNotNull(stored);
    assertEquals(ColumnLayout.MIN_WIDTH, stored.width());
    settled();
    assertEquals(ColumnLayout.MIN_WIDTH, document().lines().cellStart(1, 3) - layout().start(2));
  }

  @Test
  void aDoubleClickOnARuleFitsTheColumn() throws Exception {
    openColumns("fit.csv", CSV);
    assertEquals(6, layout().width(0));
    document().columns().sizing().resize(0, 30);
    settled();
    view().header().control().notifyListeners(SWT.MouseDoubleClick, at(ruleX(0), 2));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return layout().width(0) == 6;
              }
            }));
    assertEquals(6, controller.preferences().view().columns().format("csv").entry("id").width());
  }

  @Test
  void hideAndResetFromTheHeaderMenu() throws Exception {
    openColumns("menu.csv", CSV);
    ColumnHeader header = view().header();
    int x = (cells().columnX(layout().start(1)) + ruleX(1)) / 2;
    assertEquals(1, header.cellAt(x));
    choose(header.menuAt(x), UiText.COLUMN_HIDE.text());
    assertEquals(3, layout().count());
    assertEquals(
        ColumnSettings.Visibility.HIDDEN,
        controller.preferences().view().columns().format("csv").entry("level").visibility());
    Menu menu = header.menuAt(x);
    assertFalse(menu.getItem(1).getSelection());
    assertTrue(menu.getItem(0).getSelection());
    menu.dispose();
    choose(header.menuAt(x), UiText.COLUMN_RESET.text());
    assertEquals(4, layout().count());
    assertEquals(
        ColumnSettings.Format.EMPTY, controller.preferences().view().columns().format("csv"));
    assertEquals("level column", header.accessibleName(1));
  }

  private static void choose(Menu menu, String text) {
    try {
      for (MenuItem item : menu.getItems()) {
        if (text.equals(item.getText())) {
          assertTrue(item.isEnabled(), text);
          item.notifyListeners(SWT.Selection, new Event());
          return;
        }
      }
      throw new AssertionError("no item " + text);
    } finally {
      menu.dispose();
    }
  }

  @Test
  void theSearchBarColumnShowsInColumnViewAndSearchInColumnSetsIt() throws Exception {
    openColumns("bar.csv", CSV);
    final ColumnChooser chooser = window.searchBar().columnChooser();
    assertTrue(chooser.control().getVisible());
    assertEquals(
        List.of(UiText.SEARCH_ALL_COLUMNS.text(), "id", "level", "message", "tail"),
        List.of(chooser.control().getItems()));
    assertEquals("", chooser.column());
    int x = (cells().columnX(layout().start(1)) + ruleX(1)) / 2;
    choose(view().header().menuAt(x), UiText.COLUMN_SEARCH.text());
    assertEquals("level", chooser.column());
    assertEquals("level", document().search().options().column());
    assertEquals("level", window.searchBar().options().column());
    controller.execute(CommandId.TOGGLE_COLUMNS);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !chooser.control().getVisible();
              }
            }));
    assertEquals("", window.searchBar().options().column());
  }

  @Test
  void theCellKeysMoveTheCurrentCell() throws Exception {
    openColumns("caret.csv", CSV);
    view().control().setFocus();
    controller.setFocusedView(document());
    controller.execute(CommandId.SCROLL_RIGHT);
    assertEquals(layout().start(1), document().selection().caret().column());
    controller.execute(CommandId.LINE_END);
    assertEquals(layout().start(3), document().selection().caret().column());
    controller.execute(CommandId.SCROLL_LEFT);
    assertEquals(layout().start(2), document().selection().caret().column());
    controller.execute(CommandId.LINE_START);
    assertEquals(0, document().selection().caret().column());
  }

  @Test
  void columnViewTurnsOffWrapBlockSelectionFoldingAndWhitespace() throws Exception {
    openColumns("off.csv", CSV);
    CommandStates states = new CommandStates(controller);
    for (CommandId command : CommandStates.OFF_IN_COLUMNS) {
      assertFalse(states.enabled(command), command.name());
    }
    controller.execute(CommandId.TOGGLE_WRAP);
    assertFalse(document().wrap());
    controller.execute(CommandId.TOGGLE_COLUMNS);
    assertTrue(states.enabled(CommandId.TOGGLE_WRAP));
    assertFalse(document().wrap());
    assertFalse(view().header().shown());
  }

  /** Selects a radio or check item of the menu bar as a click does. */
  private void click(CommandId command) {
    MenuItem item = window.menuBar().item(command);
    assertTrue(item.isEnabled(), command.name());
    item.setSelection(!item.getSelection() || (item.getStyle() & SWT.RADIO) != 0);
    item.notifyListeners(SWT.Selection, new Event());
  }

  private boolean selected(CommandId command) {
    window.syncMenu();
    return window.menuBar().item(command).getSelection();
  }

  @Test
  void theFormatItemsAndFirstLineIsHeaderFollowTheTab() throws Exception {
    openColumns("menu.csv", CSV);
    assertTrue(selected(CommandId.COLUMNS_CSV));
    assertFalse(selected(CommandId.COLUMNS_TEXT));
    assertTrue(selected(CommandId.FIRST_LINE_HEADER));
    click(CommandId.FIRST_LINE_HEADER);
    assertFalse(selected(CommandId.FIRST_LINE_HEADER));
    assertEquals("1", document().columns().schema().columns().get(0).name());
    click(CommandId.COLUMNS_TEXT);
    assertTrue(selected(CommandId.COLUMNS_TEXT));
    assertFalse(selected(CommandId.COLUMNS_CSV));
    assertFalse(document().columns().shown());
    click(CommandId.COLUMNS_CLF);
    assertTrue(selected(CommandId.COLUMNS_CLF));
    assertFalse(window.menuBar().item(CommandId.FIRST_LINE_HEADER).isEnabled());
    assertFalse(window.menuBar().item(CommandId.COLUMNS_TIME).isEnabled());
  }
}
