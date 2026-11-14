package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.theme.ThemeListener;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.theme.ThemeService;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
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

/**
 * The main toolbar, the search row and the menu items take the ink of the background the main
 * toolbar draws after a live theme switch either way, and {@link PlatformTraits#shownBackground}
 * reads a visible GTK tool bar from a drawn pixel. GTK only: elsewhere the ink comes from {@code
 * getBackground}, which needs no drawn pixel and no event-loop turn.
 */
@Tag("swt")
class ToolbarInkTest {
  private static final Duration BOUND = Duration.ofSeconds(10);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;

  @BeforeEach
  void open() {
    assumeTrue("gtk".equals(SWT.getPlatform()), "the GTK ink is read from a drawn pixel");
    display = new Display();
    final SwtThemeService system = SwtThemeService.open(display, ThemeMode.LIGHT);
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
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS.withTheme(ThemeMode.LIGHT),
            new ThemeService() {
              @Override
              public boolean systemDark() {
                return system.systemDark();
              }

              @Override
              public void addListener(ThemeListener listener) {
                system.addListener(listener);
              }

              @Override
              public void preferDark(boolean dark) {
                system.preferDark(dark);
              }

              @Override
              public boolean showsNow(boolean dark) {
                return true;
              }
            });
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 900, 500), null);
    window.open();
  }

  @AfterEach
  void close() {
    if (display == null) {
      return;
    }
    controller.shutdown();
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.shell().isDisposed();
          }
        },
        BOUND);
    display.setDarkThemePreferred(false);
    display.dispose();
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  @Test
  void toolbarSearchRowAndMenusTakeTheToolbarsInkAfterEachSwitch() {
    assertTrue(EventLoop.runUntil(display, inkEverywhere(false), BOUND), "light start");
    controller.execute(CommandId.THEME_DARK);
    assumeTrue(
        EventLoop.runUntil(display, toolbarShows(true), BOUND),
        "the GTK theme draws no dark toolbar: it has no dark variant");
    assertTrue(EventLoop.runUntil(display, inkEverywhere(true), BOUND), "after a switch to dark");
    controller.execute(CommandId.THEME_LIGHT);
    assertTrue(EventLoop.runUntil(display, toolbarShows(false), BOUND), "light toolbar");
    assertTrue(EventLoop.runUntil(display, inkEverywhere(false), BOUND), "after a switch to light");
  }

  @Test
  void aVisibleGtkToolBarIsReadFromItsPixels() {
    Shell shell = new Shell(display);
    ToolBar bar = new ToolBar(shell, SWT.FLAT);
    bar.setSize(40, 20);
    PlatformTraits gtk = PlatformTraits.of("gtk", Gtk4.active());
    assertEquals(bar.getBackground().getRGB(), gtk.shownBackground(bar).getRGB(), "hidden");
    shell.setSize(80, 60);
    shell.open();
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return false;
          }
        },
        Duration.ofMillis(300));
    Color shown = gtk.shownBackground(bar);
    assertEquals(ToolIcons.dark(bar.getBackground()), ToolIcons.dark(shown));
    shell.dispose();
  }

  /** Reports whether the main toolbar draws a dark background. */
  private BooleanSupplier toolbarShows(final boolean dark) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return ToolIcons.dark(PlatformTraits.PLATFORM.shownBackground(window.toolBar())) == dark;
      }
    };
  }

  /** Reports whether every iconed tool and menu item shows the light ink on a dark background. */
  private BooleanSupplier inkEverywhere(final boolean light) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        List<Image> images = new ArrayList<Image>();
        for (ToolItem item : window.toolBar().getItems()) {
          images.add(item.getImage());
        }
        for (ToolItem item : window.searchBar().toggles()) {
          images.add(item.getImage());
        }
        if (PlatformTraits.PLATFORM.menuImages()) {
          for (MenuItem header : window.shell().getMenuBar().getItems()) {
            Menu menu = header.getMenu();
            for (MenuItem item : menu.getItems()) {
              if (item.getData() instanceof CommandId command && MenuIcons.icon(command) != null) {
                images.add(item.getImage());
              }
            }
          }
        }
        assertFalse(images.isEmpty());
        for (Image image : images) {
          if (image == null || lightInk(image) != light) {
            return false;
          }
        }
        return true;
      }
    };
  }

  /** Reports whether the visible pixels of an image average lighter than mid-gray. */
  private static boolean lightInk(Image image) {
    ImageData data = image.getImageData();
    long sum = 0;
    int count = 0;
    for (int y = 0; y < data.height; y++) {
      for (int x = 0; x < data.width; x++) {
        if (data.getAlpha(x, y) > 0) {
          RGB rgb = data.palette.getRGB(data.getPixel(x, y));
          sum += (rgb.red * 299 + rgb.green * 587 + rgb.blue * 114) / 1000;
          count++;
        }
      }
    }
    return count > 0 && sum / count >= 128;
  }
}
