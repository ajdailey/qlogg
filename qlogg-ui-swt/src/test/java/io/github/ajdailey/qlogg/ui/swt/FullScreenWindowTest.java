package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.MenuItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * View > Full Screen in a real main window: the check item each way, polled for cocoa's animation,
 * the saved bounds and maximized state unchanged by entering, and a second toggle during the
 * transition still leaving full screen. No test resizes the full-screen shell: SWT's {@code
 * setBounds} and {@code setSize} leave full screen on cocoa and GTK.
 */
@Tag("swt")
class FullScreenWindowTest {
  private static final Duration BOUND = Duration.ofSeconds(60);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;

  @BeforeEach
  void create() {
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
            Preferences.DEFAULTS);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 800, 400), null);
    window.open();
  }

  @AfterEach
  void close() {
    window.setFullScreen(false);
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
    display.dispose();
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  @Test
  void theItemTogglesAndTheSavedBoundsStayTheNormalOnes() {
    MenuItem item = item();
    assertTrue((item.getStyle() & SWT.CHECK) != 0);
    assertTrue(item.getAccelerator() != 0);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.windowBounds().width() != null;
          }
        },
        "bounds tracked");
    WindowGeometry.Request normal = window.windowBounds();
    boolean maximized = window.windowMaximized();
    item.notifyListeners(SWT.Selection, new Event());
    awaitFullScreen(true);
    assertTrue(item.getSelection());
    // Entering resized the window; a resize through setSize would leave full screen in SWT.
    flush();
    assertEquals(normal, window.windowBounds());
    assertEquals(maximized, window.windowMaximized());
    item.notifyListeners(SWT.Selection, new Event());
    awaitFullScreen(false);
    assertFalse(item.getSelection());
  }

  @Test
  void aSecondToggleDuringTheTransitionStillLeavesFullScreen() {
    MenuItem item = item();
    item.notifyListeners(SWT.Selection, new Event());
    item.notifyListeners(SWT.Selection, new Event());
    assertFalse(window.fullScreen());
    awaitFullScreen(false);
    assertFalse(item.getSelection());
  }

  private MenuItem item() {
    MenuItem found = null;
    for (MenuItem top : window.shell().getMenuBar().getItems()) {
      if (top.getMenu() == null) {
        continue;
      }
      for (MenuItem item : top.getMenu().getItems()) {
        if (item.getData() == CommandId.FULL_SCREEN) {
          found = item;
        }
      }
    }
    assertNotNull(found);
    return found;
  }

  /** Waits for the shell's state, which cocoa changes after an animation, and syncs the menu. */
  private void awaitFullScreen(final boolean on) {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.fullScreen() == on && window.shell().getFullScreen() == on;
          }
        },
        "full screen " + on);
    window.syncMenu();
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private void flush() {
    while (display.readAndDispatch()) {
      // Run the posted tasks.
    }
  }
}
