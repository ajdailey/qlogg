package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.highlight.HighlightSettings;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.theme.ThemeListener;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.model.RuleTrigger;
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
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Palette switching, the overview bar, highlighter menus and highlight painting. */
@Tag("swt")
class ThemeAndHighlightTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final int LINES = 400;
  private static final Rgb MOCK_RED = Rgb.of(0xd01010);
  private static final HighlightSet SET =
      new HighlightSet(
          "mock-errors",
          "Mock errors",
          List.of(HighlightRule.matchOnly("BLOCKTERM", MatchMode.LITERAL, null, MOCK_RED)));

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
            Preferences.DEFAULTS.withHighlights(HighlightSettings.DEFAULTS.withSets(List.of(SET))),
            new SwtThemeService(display));
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
                    && window.overviewBar() != null
                    && document != null
                    && !document.state().indexing()
                    && document.viewport().rows() > 3;
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
    for (Shell shell : display.getShells()) {
      shell.dispose();
    }
    display.dispose();
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private Path write() throws IOException {
    Path file = temp.resolve("theme.log");
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      for (int i = 1; i <= LINES; i++) {
        out.write(i % 50 == 0 ? "BLOCKTERM at " + i : "line " + i);
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
  void toolIconsTakeTheOtherInkWhenTheOwnerTurnsDark() {
    Shell shell = new Shell(display);
    ToolBar bar = new ToolBar(shell, SWT.FLAT);
    bar.setBackground(new Color(240, 240, 240));
    ToolIcons icons = new ToolIcons(bar);
    ToolItem item = new ToolItem(bar, SWT.PUSH);
    assertTrue(icons.apply(item, ToolIcons.OPEN));
    Image light = item.getImage();
    assertFalse(icons.darkBackground());
    icons.reink();
    assertSame(light, item.getImage());
    bar.setBackground(new Color(30, 30, 30));
    icons.reink();
    assertTrue(icons.darkBackground());
    assertNotSame(light, item.getImage());
    assertTrue(light.isDisposed());
    shell.dispose();
  }

  @Test
  void openReportsWhenASwitchWaitsForRestart() {
    try {
      SwtThemeService service = SwtThemeService.open(display, ThemeMode.DARK);
      assertTrue(service.showsNow(true));
      assertEquals("win32".equals(SWT.getPlatform()), !service.showsNow(false));
    } finally {
      display.setDarkThemePreferred(false);
    }
  }

  @Test
  void checkBoxUnderTheDarkStartupSettingIsAnEnabledCheckBox() {
    Shell shell = new Shell(display);
    try {
      SwtThemeService.open(display, ThemeMode.DARK);
      Button check = SwtThemeService.check(shell);
      assertTrue((check.getStyle() & SWT.CHECK) != 0);
      assertTrue(check.getEnabled());
    } finally {
      SwtThemeService.open(display, ThemeMode.LIGHT);
      shell.dispose();
    }
  }

  @Test
  void refreshReportsASystemChangeOnce() {
    final boolean[] setting = {false};
    SwtThemeService service =
        new SwtThemeService(
            display,
            false,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return setting[0];
              }
            });
    final List<Boolean> changes = new ArrayList<Boolean>();
    service.addListener(
        new ThemeListener() {
          @Override
          public void systemThemeChanged(boolean dark) {
            changes.add(dark);
          }
        });
    service.refresh();
    assertEquals(List.of(), changes);
    setting[0] = true;
    service.refresh();
    service.refresh();
    assertEquals(List.of(true), changes);
    assertTrue(service.systemDark());
  }

  @Test
  void cocoaChromeLeavesAPaletteColoredFolderAlone() {
    Shell shell = new Shell(display);
    CTabFolder tabs = new CTabFolder(shell, SWT.NONE);
    tabs.setData(CocoaChrome.PALETTE_COLORED, Boolean.TRUE);
    RGB mock = new RGB(0x12, 0x34, 0x56);
    tabs.setForeground(new Color(mock));
    tabs.setSelectionForeground(new Color(mock));
    tabs.setSelectionBackground(new Color(mock));
    CocoaChrome chrome = new CocoaChrome(display, false);
    try {
      chrome.apply(true);
      assertEquals(mock, tabs.getForeground().getRGB());
      assertEquals(mock, tabs.getSelectionForeground().getRGB());
      assertEquals(mock, tabs.getSelectionBackground().getRGB());
      chrome.apply(false);
      assertEquals(mock, tabs.getForeground().getRGB());
      assertEquals(mock, tabs.getSelectionBackground().getRGB());
    } finally {
      shell.dispose();
    }
  }

  @Test
  void cocoaChromeFollowsAnAppearanceThatDiffersFromTheStartupOne() {
    RGB window = new RGB(0x32, 0x32, 0x32);
    Shell shell = new Shell(display);
    Composite row = new Composite(shell, SWT.NONE);
    CTabFolder tabs = new CTabFolder(shell, SWT.NONE);
    Text field = new Text(shell, SWT.SINGLE);
    RGB before = row.getBackground().getRGB();
    RGB fieldBefore = field.getBackground().getRGB();
    final int[] settings = {0};
    row.addListener(
        SWT.Settings,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            settings[0]++;
          }
        });
    CocoaChrome chrome = new CocoaChrome(display, false);
    try {
      chrome.apply(true);
      assertEquals(window, row.getBackground().getRGB());
      assertEquals(new RGB(0xff, 0xff, 0xff), tabs.getForeground().getRGB());
      assertEquals(new RGB(0x1e, 0x1e, 0x1e), field.getBackground().getRGB());
      assertEquals(1, settings[0]);
      Shell shown = new Shell(display);
      Composite shownRow = new Composite(shown, SWT.NONE);
      shown.open();
      assertEquals(window, shownRow.getBackground().getRGB());
      shown.dispose();
    } finally {
      chrome.apply(false);
    }
    assertEquals(before, row.getBackground().getRGB());
    assertEquals(fieldBefore, field.getBackground().getRGB());
    assertEquals(2, settings[0]);
    shell.dispose();
  }

  @Test
  void paletteSwitchRepaintsBothCanvasesAndTheOverviewBar() {
    final Canvas main = window.canvas().control();
    final Canvas filtered = window.results().canvas().control();
    int x = main.getClientArea().width - 4;
    int y = window.canvas().lineHeight() * 3 + 2;
    assertEquals(rgb(Palette.LIGHT.background()), pixel(main, x, y));
    controller.execute(CommandId.THEME_DARK);
    assertSame(Palette.DARK, window.canvas().palette());
    assertSame(Palette.DARK, window.results().canvas().palette());
    assertSame(Palette.DARK, window.overviewBar().palette());
    assertEquals(rgb(Palette.DARK.background()), pixel(main, x, y));
    assertEquals(
        rgb(Palette.DARK.background()),
        pixel(filtered, filtered.getClientArea().width - 4, filtered.getClientArea().height - 4));
    assertEquals(ThemeMode.DARK, controller.preferences().theme());
    window.syncMenu();
    controller.execute(CommandId.THEME_LIGHT);
    assertEquals(rgb(Palette.LIGHT.background()), pixel(main, x, y));
  }

  @Test
  void overviewBarSitsBetweenTheTextAndTheScrollBar() {
    LogCanvas main = window.canvas();
    Control[] row = main.frame().getChildren();
    assertEquals(3, row.length);
    assertSame(main.control(), row[0]);
    assertSame(window.overviewBar().control(), row[1]);
    assertSame(main.verticalScrollBar(), row[2]);
    Rectangle text = main.control().getBounds();
    Rectangle bar = window.overviewBar().control().getBounds();
    assertEquals(text.x + text.width, bar.x);
    assertEquals(bar.x + bar.width, main.verticalScrollBar().getBounds().x);
    assertEquals(
        rgb(window.overviewBar().palette().overviewFrame()),
        pixel(window.overviewBar().control(), 0, bar.height / 2));
    Control[] filtered = window.results().canvas().frame().getChildren();
    assertEquals(2, filtered.length);
    assertSame(window.results().canvas().verticalScrollBar(), filtered[1]);
  }

  @Test
  void overviewClickShowsTheLine() {
    final OverviewBar bar = window.overviewBar();
    final int height = bar.control().getClientArea().height;
    assertTrue(height > 20, "bar height " + height);
    Event down = new Event();
    down.button = 1;
    down.y = height / 2;
    bar.control().notifyListeners(SWT.MouseDown, down);
    bar.control().notifyListeners(SWT.MouseUp, down);
    long caret = document().selection().caret().line();
    assertTrue(Math.abs(caret - LINES / 2) <= LINES / height + 1, "caret " + caret);
    long top = document().viewport().topLine();
    assertTrue(top > 0 && top <= caret, "top " + top);
    controller.execute(CommandId.TOGGLE_OVERVIEW);
    assertTrue(!bar.control().getVisible());
    assertTrue(!controller.preferences().overview());
    controller.execute(CommandId.TOGGLE_OVERVIEW);
    assertTrue(bar.control().getVisible());
  }

  @Test
  void highlighterMenuTogglesASetAndRowsPaintIt() {
    Menu bar = window.shell().getMenuBar();
    Menu highlighters = null;
    for (MenuItem item : bar.getItems()) {
      if (item.getText().replace("&", "").equals("Highlighters")) {
        highlighters = item.getMenu();
      }
    }
    assertNotNull(highlighters);
    highlighters.notifyListeners(SWT.Show, new Event());
    MenuItem setItem = null;
    for (MenuItem item : highlighters.getItems()) {
      if (item.getData() instanceof HighlightSet) {
        setItem = item;
      }
    }
    assertNotNull(setItem);
    assertEquals("Mock errors", setItem.getText());
    setItem.notifyListeners(SWT.Selection, new Event());
    assertTrue(controller.highlightSettings().isActive(SET.id()));
    document().goToLine(50);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document().lines().contains(49) && document().lines().spans(49).count() == 1;
              }
            }));
    long row = 49 - document().viewport().topLine();
    int y = (int) (row + 1) * window.canvas().lineHeight() - 1;
    int x = window.canvas().gutterWidth() + window.canvas().columnOffset(4);
    assertEquals(rgb(MOCK_RED), pixel(window.canvas().control(), x, y));
    highlighters.notifyListeners(SWT.Show, new Event());
    for (MenuItem item : highlighters.getItems()) {
      if (item.getData() instanceof HighlightSet) {
        assertTrue(item.getSelection());
      }
    }
    controller.execute(CommandId.NO_HIGHLIGHTERS);
    assertTrue(controller.highlightSettings().active().isEmpty());
  }

  @Test
  void theHighlightersDialogEditsTheSetsAndReportsAnInvalidPattern() {
    HighlightersDialog dialog = new HighlightersDialog(window.shell(), controller);
    dialog.addRule();
    assertEquals(2, dialog.sets().get(0).rules().size());
    dialog.pattern().setText("(open");
    assertTrue(dialog.statusText().startsWith(UiText.RULE_INVALID.format("")));
    assertEquals("(open", dialog.sets().get(0).rules().get(1).pattern());
    dialog.ruleUp();
    assertEquals("(open", dialog.sets().get(0).rules().get(0).pattern());
    dialog.removeRule();
    assertEquals(SET.rules(), dialog.sets().get(0).rules());
    dialog.addSet();
    assertEquals(2, dialog.result().sets().size());
    dialog.removeSet();
    assertEquals(List.of(SET), dialog.result().sets());
    dialog.shell().dispose();
  }

  // Keyword triggers.

  private static void click(Button check) {
    check.setSelection(!check.getSelection());
    check.notifyListeners(SWT.Selection, new Event());
  }

  @Test
  void theHighlightersDialogEditsARulesTriggers() {
    HighlightersDialog dialog = new HighlightersDialog(window.shell(), controller);
    dialog.selectRule(0, 0);
    assertFalse(dialog.stopFollow().getSelection());
    assertFalse(dialog.commentTemplate().getEnabled(), "no mark, no template");
    click(dialog.stopFollow());
    click(dialog.setMark());
    assertTrue(dialog.commentTemplate().getEnabled());
    dialog.commentTemplate().setText("at %L");
    click(dialog.quiet());
    assertEquals(
        new RuleTrigger(true, true, "at %L", true), dialog.sets().get(0).rules().get(0).trigger());
    dialog.commentTemplate().setText("{(}{x}");
    assertTrue(dialog.statusText().startsWith(UiText.RULE_TEMPLATE_INVALID.format("")));
    controller.setHighlightSettings(dialog.result());
    dialog.shell().dispose();
    HighlightersDialog reopened = new HighlightersDialog(window.shell(), controller);
    reopened.selectRule(0, 0);
    assertTrue(reopened.stopFollow().getSelection());
    assertTrue(reopened.setMark().getSelection());
    assertTrue(reopened.quiet().getSelection());
    assertEquals("{(}{x}", reopened.commentTemplate().getText());
    reopened.shell().dispose();
  }
}
