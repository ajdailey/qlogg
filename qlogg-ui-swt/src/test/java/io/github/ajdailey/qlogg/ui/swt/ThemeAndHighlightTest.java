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
import io.github.ajdailey.qlogg.app.theme.Palette;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
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
      assertTrue(canvas.print(gc));
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
    int x = window.canvas().gutterWidth() + window.canvas().charWidth() * 4;
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
}
