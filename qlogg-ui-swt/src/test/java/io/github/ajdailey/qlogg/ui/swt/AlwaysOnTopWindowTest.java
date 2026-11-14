package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
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
import io.github.ajdailey.qlogg.ui.swt.platform.WindowStacking;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * View > Always on Top in a real main window: the item on this platform, its toggle, and on GTK 3
 * under X11 the window's above state with a modal dialog and the search history list over it.
 */
@Tag("swt")
class AlwaysOnTopWindowTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

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
            Preferences.DEFAULTS.withSearchHistory(List.of("mock-one", "mock-two")));
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 800, 400), null);
  }

  @AfterEach
  void close() {
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

  private MenuItem item() {
    MenuItem found = find(window.shell().getMenuBar());
    assertNotNull(found);
    return found;
  }

  private static MenuItem find(Menu menu) {
    for (MenuItem item : menu.getItems()) {
      if (item.getData() == CommandId.ALWAYS_ON_TOP) {
        return item;
      }
      if (item.getMenu() != null) {
        MenuItem nested = find(item.getMenu());
        if (nested != null) {
          return nested;
        }
      }
    }
    return null;
  }

  @Test
  void theItemFollowsThePlatform() {
    window.open();
    window.syncMenu();
    MenuItem item = item();
    assertTrue((item.getStyle() & SWT.CHECK) != 0);
    assertEquals(0, item.getAccelerator());
    boolean supported = WindowStacking.PLATFORM.supported();
    assertEquals(supported, window.onTopSupported());
    assertEquals(supported, item.getEnabled());
    // SWT keeps no menu item tool tip on GTK 4.
    String tip =
        WindowStacking.PLATFORM.windowManagerMenu() && !Gtk4.active()
            ? UiText.ALWAYS_ON_TOP_UNSUPPORTED.text()
            : null;
    assertEquals(tip, item.getToolTipText());
  }

  @Test
  void theItemTogglesWhereSupported() {
    window.open();
    window.syncMenu();
    MenuItem item = item();
    boolean supported = window.onTopSupported();
    item.notifyListeners(SWT.Selection, new Event());
    window.syncMenu();
    assertEquals(supported, item.getSelection());
    assertEquals(supported, controller.alwaysOnTop().saved());
    item.notifyListeners(SWT.Selection, new Event());
    window.syncMenu();
    assertFalse(item.getSelection());
    assertFalse(controller.alwaysOnTop().saved());
    assertEquals(supported, window.onTopSupported());
  }

  /**
   * The window maps above others. A modal dialog is transient for it, which an EWMH window manager
   * keeps above it, and the search history list is an override-redirect popup, which the window
   * manager does not stack. Toggled before the window maps: Xvfb has no window manager to apply a
   * toggle on a mapped window.
   */
  @Test
  void onGtk3X11TheWindowIsAboveAndItsDialogAndHistoryListStayOverIt() {
    assumeTrue(
        !Gtk4.active() && "x11".equals(System.getProperty(PlatformTraits.GDK_BACKEND)),
        "GTK 3 on X11");
    controller.execute(CommandId.ALWAYS_ON_TOP);
    window.shell().setText("qlogg-on-top-" + System.nanoTime());
    window.open();
    XWindows.awaitAbove(display, window.shell(), true);
    Shell dialog = Dialogs.shell(window.shell(), "qlogg-dialog-" + System.nanoTime(), true);
    dialog.setSize(200, 100);
    dialog.open();
    XWindows.awaitTransient(display, dialog, window.shell());
    dialog.dispose();
    List<String> before = XWindows.topLevels();
    Combo pattern = window.searchBar().pattern();
    assertEquals(2, pattern.getItemCount());
    pattern.setFocus();
    pattern.setListVisible(true);
    XWindows.awaitOverrideRedirectPopup(display, before);
    pattern.setListVisible(false);
  }
}
