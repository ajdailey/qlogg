package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandGroup;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.config.TimeSettings;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.time.SpreadMode;
import io.github.ajdailey.qlogg.core.time.TimeSpread;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The overview bar's time-spread layer and its View menu cascade, under Xvfb. */
@Tag("swt")
class TimeSpreadGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final int LINES = 3000;

  /** Lines before the pause; the clock steps back at {@link #STEP_BACK}. */
  private static final int PAUSE = 1000;

  private static final int STEP_BACK = 2000;

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
            Preferences.DEFAULTS.withTime(
                new TimeSettings(ZoneOffset.UTC, ZoneOffset.UTC, List.of())));
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 1100, 700), null);
    window.open();
    controller.openFile(mockLog(), 0);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController document = controller.activeDocument();
                return window.canvas() != null
                    && document != null
                    && !document.state().indexing()
                    && document.time().format() != null;
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
    workers.close();
    display.dispose();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  /** A line a second, an hour's pause, then a clock stepped back a day. */
  private Path mockLog() throws Exception {
    StringBuilder text = new StringBuilder();
    long seconds = 0;
    for (int i = 0; i < LINES; i++) {
      seconds += i == PAUSE ? 3600 : 1;
      seconds -= i == STEP_BACK ? 86400 : 0;
      long time = 2 * 86400 + seconds;
      text.append(
          String.format(
              "2026-03-%02d %02d:%02d:%02d.000 %s entry %d%n",
              time / 86400,
              time / 3600 % 24,
              time / 60 % 60,
              time % 60,
              i == PAUSE ? "BLOCKTERM" : "INFO",
              i));
    }
    return Files.writeString(temp.resolve("spread.log"), text.toString());
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private DocumentController document() {
    return controller.activeDocument();
  }

  private TimeSpread awaitSpread(final SpreadMode mode) {
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                TimeSpread spread = document().overview().spread();
                return spread.mode() == mode
                    && spread.height() == window.overviewBar().control().getClientArea().height;
              }
            }));
    return document().overview().spread();
  }

  /** Returns the last row of a level, clear of the top visible-line mark. */
  private int rowOf(TimeSpread spread, int level) {
    for (int y = spread.height() - 1; y > 0; y--) {
      if (spread.levels()[y] == level) {
        return y;
      }
    }
    throw new AssertionError("no row of level " + level);
  }

  private RGB pixel(int x, int y) {
    Canvas canvas = window.overviewBar().control();
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

  private static RGB rgb(int packed) {
    return new RGB(packed >> 16 & 0xff, packed >> 8 & 0xff, packed & 0xff);
  }

  private static RGB rgb(Rgb color) {
    return new RGB(color.red(), color.green(), color.blue());
  }

  private void settle() {
    while (display.readAndDispatch()) {
      // Paints and posted results.
    }
  }

  @Test
  void pausesPaintTheLevelColorAndDisorder() {
    Palette palette = window.overviewBar().palette();
    controller.execute(CommandId.TIME_SPREAD_PAUSES);
    TimeSpread spread = awaitSpread(SpreadMode.PAUSES);
    settle();
    int pause = rowOf(spread, TimeSpread.MAX_LEVEL);
    int mid = OverviewBar.WIDTH / 2;
    RGB shade = rgb(OverviewBar.spreadColor(palette, SpreadMode.PAUSES, TimeSpread.MAX_LEVEL));
    assertEquals(shade, pixel(mid, pause));
    assertEquals(shade, pixel(1, pause));
    assertEquals(rgb(palette.overviewFrame()), pixel(0, pause));
    int disorder = rowOf(spread, TimeSpread.DISORDER);
    assertTrue(disorder > pause, "rows " + pause + ", " + disorder);
    assertEquals(rgb(palette.followStoppedDot()), pixel(mid, disorder));
    assertEquals(rgb(palette.overview()), pixel(mid, rowOf(spread, 0)));
  }

  @Test
  void activityPaintsItsOwnColorAndOffClearsTheLayer() {
    Palette palette = window.overviewBar().palette();
    controller.execute(CommandId.TIME_SPREAD_ACTIVITY);
    TimeSpread spread = awaitSpread(SpreadMode.ACTIVITY);
    settle();
    int level = 1;
    while (level <= TimeSpread.MAX_LEVEL && !present(spread, level)) {
      level++;
    }
    assertTrue(level <= TimeSpread.MAX_LEVEL, "no shaded row");
    int row = rowOf(spread, level);
    int mid = OverviewBar.WIDTH / 2;
    assertEquals(
        rgb(OverviewBar.spreadColor(palette, SpreadMode.ACTIVITY, level)), pixel(mid, row));
    controller.execute(CommandId.TIME_SPREAD_OFF);
    assertSame(TimeSpread.EMPTY, document().overview().spread());
    settle();
    assertEquals(rgb(palette.overview()), pixel(mid, row));
  }

  private static boolean present(TimeSpread spread, int level) {
    for (byte value : spread.levels()) {
      if (value == level) {
        return true;
      }
    }
    return false;
  }

  @Test
  void aMatchLinePaintsOverTheShading() {
    Palette palette = window.overviewBar().palette();
    document().search().setText("BLOCKTERM", SearchOptions.DEFAULTS.withCaseSensitive(true));
    document().search().run();
    controller.execute(CommandId.TIME_SPREAD_PAUSES);
    final TimeSpread spread = awaitSpread(SpreadMode.PAUSES);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                int[] matches = document().overview().bins().matches();
                return matches.length > 0 && matchRow(matches) >= 0;
              }
            }));
    settle();
    int row = matchRow(document().overview().bins().matches());
    assertEquals(rgb(palette.matchMarker()), pixel(OverviewBar.WIDTH / 2, row));
    assertFalse(spread.levels()[row] == 0 && spread.levels()[Math.max(0, row - 1)] == 0);
  }

  private static int matchRow(int[] matches) {
    for (int y = 0; y < matches.length; y++) {
      if (matches[y] > 0) {
        return y;
      }
    }
    return -1;
  }

  @Test
  void theDarkPaletteRepaintsTheLayer() {
    controller.execute(CommandId.TIME_SPREAD_PAUSES);
    TimeSpread spread = awaitSpread(SpreadMode.PAUSES);
    controller.execute(CommandId.THEME_DARK);
    settle();
    assertEquals(
        rgb(OverviewBar.spreadColor(Palette.DARK, SpreadMode.PAUSES, TimeSpread.MAX_LEVEL)),
        pixel(OverviewBar.WIDTH / 2, rowOf(spread, TimeSpread.MAX_LEVEL)));
    controller.execute(CommandId.THEME_LIGHT);
  }

  @Test
  void theCascadeIsARadioGroupThatChoosesTheMode() {
    window.syncMenu();
    MenuItem[] items = spreadItems();
    assertEquals(3, items.length);
    for (MenuItem item : items) {
      assertTrue((item.getStyle() & SWT.RADIO) != 0);
    }
    assertTrue(items[0].getSelection());

    items[0].setSelection(false);
    items[2].setSelection(true);
    // A radio choice fires for the cleared item too; only the chosen item counts.
    items[0].notifyListeners(SWT.Selection, new Event());
    items[2].notifyListeners(SWT.Selection, new Event());
    assertEquals(SpreadMode.PAUSES, controller.preferences().view().timeSpread());
    awaitSpread(SpreadMode.PAUSES);

    window.syncMenu();
    assertFalse(items[0].getSelection());
    assertFalse(items[1].getSelection());
    assertTrue(items[2].getSelection());
  }

  /** Returns the items of View > Timestamps > Time Spread. */
  private MenuItem[] spreadItems() {
    String view = CommandGroup.VIEW.label();
    Menu bar = window.shell().getMenuBar();
    for (MenuItem top : bar.getItems()) {
      if (top.getText().equals(view)) {
        Menu timestamps = cascade(top.getMenu(), UiText.MENU_TIMESTAMPS);
        assertNotNull(timestamps);
        Menu spread = cascade(timestamps, UiText.MENU_TIME_SPREAD);
        assertNotNull(spread);
        return spread.getItems();
      }
    }
    throw new AssertionError("no View menu");
  }

  private static Menu cascade(Menu menu, UiText label) {
    for (MenuItem item : menu.getItems()) {
      if (item.getText().equals(label.text())) {
        return item.getMenu();
      }
    }
    return null;
  }
}
