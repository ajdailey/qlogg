package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.WindowSet;
import io.github.ajdailey.qlogg.app.log.LogFiles;
import io.github.ajdailey.qlogg.app.log.RecentLog;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Help menu's log items and the status bar's warning marker, under Xvfb. With {@code
 * -Dqlogg.shots=<dir>} it also writes PNGs of the window with the marker and of Show Log.
 */
@Tag("swt")
class HelpItemsTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final RecentLog recent = new RecentLog(20, 200);
  private volatile boolean attached;
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
    WindowSet set =
        new WindowSet(
            workers,
            new SwtUiDispatcher(display),
            services,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS,
            ThemeService.LIGHT);
    set.setLogging(
        recent,
        new LogFiles() {
          @Override
          public Path directory() {
            return temp.resolve("logs");
          }

          @Override
          public boolean attached() {
            return attached;
          }

          @Override
          public void attach() throws IOException {
            attached = true;
          }

          @Override
          public void detach() {
            attached = false;
          }
        });
    window = new MainWindow(display);
    controller = new ApplicationController(set, window);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 1100, 700), null);
    window.open();
  }

  /** Writes a PNG of a shell when {@code qlogg.shots} names a directory. */
  private void shoot(Shell shell, String name) {
    String dir = System.getProperty("qlogg.shots");
    if (dir == null) {
      return;
    }
    while (display.readAndDispatch()) {
      // Flush pending paints.
    }
    Point size = shell.getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(image);
    try {
      shell.print(gc);
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {image.getImageData()};
      loader.save(Path.of(dir, name).toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  /**
   * Writes a PNG of the screen area a shell covers when {@code qlogg.shots} names a directory; a
   * browser's page shows only on the screen, not through {@code Shell.print}.
   */
  private void shootScreen(Shell shell, String name) {
    String dir = System.getProperty("qlogg.shots");
    if (dir == null) {
      return;
    }
    shell.forceActive();
    final long until = System.nanoTime() + 1_000_000_000L;
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        });
    org.eclipse.swt.graphics.Rectangle bounds = shell.getBounds();
    Image image = new Image(display, bounds.width, bounds.height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, bounds.x, bounds.y);
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {image.getImageData()};
      loader.save(Path.of(dir, name).toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      image.dispose();
    }
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
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private MenuItem helpItem(String label) {
    Menu bar = window.shell().getMenuBar();
    Menu help = bar.getItem(bar.getItemCount() - 1).getMenu();
    for (MenuItem item : help.getItems()) {
      if (item.getText().replace("&", "").startsWith(label)) {
        return item;
      }
    }
    return null;
  }

  @Test
  void debugLogFileIsCheckedOnlyWhileTheFileIsOpen() {
    final MenuItem item = helpItem("Debug Log File");
    assertNotNull(item);
    window.syncMenu();
    assertFalse(item.getSelection());
    controller.execute(CommandId.DEBUG_LOG_FILE);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return attached;
              }
            }));
    window.syncMenu();
    assertTrue(item.getSelection());
    assertTrue(helpItem("Show Log").isEnabled());
    assertTrue(helpItem("Open Log Folder").isEnabled());
    assertTrue(helpItem("Report an Issue").isEnabled());
  }

  @Test
  void aWarningShowsTheMarkerAndShowLogClearsIt() throws Exception {
    final Path log = temp.resolve("service.log");
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      text.append("2026-11-02 12:00:").append(10 + i).append(" INFO mock entry ").append(i);
      text.append('\n');
    }
    Files.writeString(log, text.toString());
    controller.open(List.of(new FileRequest(log, 0)));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.activeDocument() != null
                    && !controller.activeDocument().state().indexing()
                    && controller.activeDocument().lines() != null
                    && !window.statusText().contains("Indexing");
              }
            }));
    assertFalse(window.warningMarkerShown());
    recent.append("2026-11-02 INFO mock start", false);
    recent.append("2026-11-02 WARN mock warning", true);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.warningMarkerShown();
              }
            }));
    shoot(window.shell(), "warning-marker.png");
    controller.execute(CommandId.SHOW_LOG);
    final Shell logShell = window.logShell();
    assertNotNull(logShell);
    assertFalse(window.warningMarkerShown());
    Text shown = (Text) logShell.getChildren()[0];
    assertTrue(shown.getText().contains("WARN mock warning"));
    assertTrue(shown.getText().startsWith("2026-11-02 INFO mock start"));
    shoot(logShell, "show-log.png");

    recent.append("2026-11-02 ERROR mock error", true);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.warningMarkerShown();
              }
            }));
    controller.execute(CommandId.SHOW_LOG);
    assertSame(logShell, window.logShell());
    assertTrue(shown.getText().contains("ERROR mock error"));
    assertFalse(window.warningMarkerShown());
  }

  // User guide and the macOS application menu.

  @Test
  void documentationShowsTheGuideInABrowserWindowOrNamesTheFile() throws Exception {
    assumeFalse(Gtk4.active(), Gtk4.BROWSER_ABORTS);
    final Path copy = temp.toRealPath().resolve("cache").resolve("guide.html");
    long start = System.nanoTime();
    controller.execute(CommandId.DOCUMENTATION);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.guideWindow() != null
                    || window.noticeText().contains(copy.toString());
              }
            }));
    assertTrue(Files.isRegularFile(copy));
    final GuideWindow guide = window.guideWindow();
    if (guide == null) {
      return;
    }
    final long windowMillis = (System.nanoTime() - start) / 1_000_000L;
    // Either outcome passes: the page loaded, or the window closed and took the fallback. An
    // exception the browser throws on the UI thread escapes runUntil and fails the test.
    boolean settled =
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return guide.shell().isDisposed() || guide.loaded();
              }
            });
    assertTrue(settled, "window after " + windowMillis + " ms; location " + guide.location());
    if (guide.shell().isDisposed()) {
      return;
    }
    assertTrue(GuideWindow.allowed(guide.location(), copy), guide.location());
    shootScreen(guide.shell(), "guide.png");
    controller.execute(CommandId.DOCUMENTATION);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return guide.shell().isVisible();
              }
            }));
    assertSame(guide, window.guideWindow());
  }

  @Test
  void theGuideWindowLoadsOnlyTheGuide() throws Exception {
    Path copy = temp.resolve("cache").resolve("guide.html");
    assertTrue(GuideWindow.allowed(copy.toUri().toString(), copy));
    assertTrue(GuideWindow.allowed(copy.toUri() + "#search", copy));
    assertTrue(GuideWindow.allowed("about:blank", copy));
    assertFalse(GuideWindow.allowed("https://github.com/ajdailey/qlogg", copy));
    assertFalse(GuideWindow.allowed(temp.resolve("other.html").toUri().toString(), copy));
    assertFalse(GuideWindow.allowed("data:text/html,mock", copy));
    assertFalse(GuideWindow.allowed("javascript:void(0)", copy));
  }

  @Test
  void aBrowserExceptionClosesTheGuideWindowWithoutEscaping() {
    Path copy = temp.resolve("guide.html");
    final List<Path> fallbacks = new CopyOnWriteArrayList<Path>();
    int shells = display.getShells().length;
    GuideWindow created =
        GuideWindow.create(
            window.shell(),
            copy,
            new Consumer<Path>() {
              @Override
              public void accept(Path file) {
                fallbacks.add(file);
              }
            },
            new GuideWindow.BrowserFactory() {
              @Override
              public Browser create(Composite parent) {
                throw new SWTException("Waiting for Edge operation to terminate timed out");
              }
            },
            GuideWindow.LOAD_BOUND_MILLIS);
    assertNull(created);
    assertEquals(shells, display.getShells().length);
    assertTrue(fallbacks.isEmpty());
  }

  @Test
  void theLoadBoundExpiresOnceUnlessMet() {
    final int[] expired = new int[1];
    Runnable count =
        new Runnable() {
          @Override
          public void run() {
            expired[0]++;
          }
        };
    final GuideWindow.LoadBound bound =
        new GuideWindow.LoadBound(new SwtUiDispatcher(display), 1, count);
    bound.arm();
    bound.arm();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return expired[0] > 0;
              }
            }));
    assertFalse(bound.armed());

    final GuideWindow.LoadBound met =
        new GuideWindow.LoadBound(new SwtUiDispatcher(display), 1, count);
    met.arm();
    met.met();
    final long until = System.nanoTime() + 200_000_000L;
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        });
    assertEquals(1, expired[0]);
  }

  @Test
  void withoutTheApplicationMenuTheMenuBarKeepsAboutOptionsAndExit() {
    assumeFalse(AppMenu.present(display), "Cocoa always has the application menu");
    assertSame(CommandId.OPTIONS, AppMenu.command(SWT.ID_PREFERENCES));
    assertSame(CommandId.ABOUT, AppMenu.command(SWT.ID_ABOUT));
    assertSame(CommandId.QUIT, AppMenu.command(SWT.ID_QUIT));
    assertSame(null, AppMenu.command(SWT.ID_HIDE));
    assertNotNull(helpItem("About"));
    boolean options = false;
    for (MenuItem header : window.shell().getMenuBar().getItems()) {
      for (MenuItem item : header.getMenu().getItems()) {
        options |= item.getData() == CommandId.OPTIONS;
      }
    }
    assertTrue(options);
  }
}
