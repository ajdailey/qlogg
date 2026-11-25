package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.WindowSet;
import io.github.ajdailey.qlogg.app.theme.ThemeController;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
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
import io.github.ajdailey.qlogg.ui.swt.platform.DialogColors;
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
import org.eclipse.swt.widgets.Display;

/**
 * Main windows in {@link MainWindowSlot}s over one {@link WindowSet}, composed as the launcher
 * does, with the theme service rebuilding them through a {@link WindowViews}. Confined to the
 * test's thread, which owns the Display.
 */
final class SlotWindows implements AutoCloseable {
  static final Duration BOUND = Duration.ofSeconds(60);

  final Display display;
  final SwtThemeService system;
  final WindowViews views;
  final WindowSet set;
  final List<MainWindowSlot> slots = new ArrayList<MainWindowSlot>();
  final List<ApplicationController> controllers = new ArrayList<ApplicationController>();
  final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final Workers workers;
  private final ThemeMode theme;

  /**
   * Composes the windows' services; the views rebuild through the given dispatcher, or the
   * Display's when null.
   */
  SlotWindows(Display display, Path config, ThemeMode theme, WindowViews views) throws IOException {
    this.display = display;
    this.theme = theme;
    system = SwtThemeService.open(display, theme);
    this.views = views == null ? new WindowViews(display) : views;
    system.setWindows(this.views);
    workers =
        new Workers(
            4,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    MemoryBudget budget = MemoryBudget.of(1L << 29);
    DocumentServices services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(4, budget)),
            new SearchEngine(
                workers, StandardMatchers.INSTANCE, SearchSettings.defaults(4, budget)),
            new BlockCache(65536, 1 << 22),
            budget,
            StandardCharsets.ISO_8859_1);
    set =
        new WindowSet(
            workers,
            new SwtUiDispatcher(display),
            services,
            new ConfigStore(
                new SettingsLocation(SettingsMode.EXPLICIT, Files.createDirectories(config))),
            Preferences.DEFAULTS.withTheme(theme),
            system);
  }

  /** Opens a window at a given place. */
  ApplicationController open(int x, int y, int width, int height) {
    MainWindow window =
        new MainWindow(display, set.keymap(), ThemeController.resolve(theme, system.systemDark()));
    MainWindowSlot slot = views.add(window);
    ApplicationController controller = new ApplicationController(set, slot);
    slot.attach(controller);
    window.place(new WindowGeometry.Request(x, y, width, height), null);
    window.open();
    slots.add(slot);
    controllers.add(controller);
    return controller;
  }

  /**
   * Switches the theme and waits until every window is rebuilt. Elsewhere than win32 it records the
   * side as {@code Win32ThemeFollow} does and asks the views for the rebuild.
   *
   * @param controller a window's controller
   * @param dark the side
   * @return the longest UI turn during the wait, in ms: the turn that rebuilt the window
   */
  double switchTo(ApplicationController controller, boolean dark) {
    final List<MainWindow> old = new ArrayList<MainWindow>();
    for (MainWindowSlot each : slots) {
      old.add(each.window());
    }
    controller.setTheme(dark ? ThemeMode.DARK : ThemeMode.LIGHT);
    if (!"win32".equals(SWT.getPlatform())) {
      display.setData(DialogColors.NATIVE_DARK, dark);
      views.rebuild();
    }
    final long[] last = {System.nanoTime(), 0};
    assertTrue(
        await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                long now = System.nanoTime();
                last[1] = Math.max(last[1], now - last[0]);
                last[0] = now;
                for (int i = 0; i < old.size(); i++) {
                  if (!old.get(i).shell().isDisposed() || slots.get(i).window() == old.get(i)) {
                    return false;
                  }
                }
                return true;
              }
            }),
        "rebuilt to " + (dark ? "dark" : "light"));
    return last[1] / 1e6;
  }

  /** Dispatches events until the condition holds or the bound passes. */
  boolean await(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  /** Runs every queued event, with at most 5 s spent. */
  void settle() {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline && display.readAndDispatch()) {
      // Runs queued work.
    }
    display.update();
  }

  @Override
  public void close() {
    set.quit();
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !views.anyOpen();
          }
        },
        BOUND);
    workers.close();
  }
}
