package io.github.ajdailey.qlogg.launcher;

import io.github.ajdailey.qlogg.app.about.UserGuide;
import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.cli.SessionMode;
import io.github.ajdailey.qlogg.app.cli.StartupRequest;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.ErrorExitMarker;
import io.github.ajdailey.qlogg.app.config.Options;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.Session;
import io.github.ajdailey.qlogg.app.config.WindowState;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.WindowSet;
import io.github.ajdailey.qlogg.app.ipc.InstanceServer;
import io.github.ajdailey.qlogg.app.log.EventLoopWatchdog;
import io.github.ajdailey.qlogg.app.log.LogFiles;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.theme.ThemeController;
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
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import io.github.ajdailey.qlogg.ui.swt.AppMenu;
import io.github.ajdailey.qlogg.ui.swt.MainWindow;
import io.github.ajdailey.qlogg.ui.swt.SwtThemeService;
import io.github.ajdailey.qlogg.ui.swt.SwtUiDispatcher;
import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composes the application and runs the SWT event loop on the calling thread, which becomes the UI
 * thread. The theme resolves before the first Shell: light and dark, and system on win32, also set
 * the native controls' theme ({@link SwtThemeService#open}), which on win32 a later switch reaches
 * only at the next start; on cocoa native controls follow the system. Startup after the Display
 * exists does not block it: the command-line files open on workers once the first window is shown,
 * each in a tab, following with {@code -f}; each further window of a restored session opens with
 * its tabs. When {@link EventLoopWatchdog#enabled} is true at start ({@code -d 2} and above, or the
 * debug log file attached), an {@link EventLoopWatchdog} logs UI-thread stalls. A primary
 * instance's {@link InstanceServer} accepts handoffs from the start of {@link #run} and, once the
 * command-line files are on their way, delivers handed files to {@link WindowSet#openHandoff}; quit
 * closes it before the workers, and so does a run that ends before the first window. A JVM shutdown
 * hook ({@link ShutdownHook}) runs the quit when the process is ended without it. {@link
 * UiFailures} handles a failure of a UI-thread task. The first launch after it writes the {@link
 * ErrorExitMarker} shows a notice and records that in the marker; a launch that would restore the
 * session and finds the marker skips the session's tabs and windows and, as the session owner,
 * deletes the marker. A failure that leaves {@code readAndDispatch} goes to {@link UiFailures} too;
 * when the quit it runs throws, the loop ends at once. A primary with the debug log file attached
 * deletes other processes' old log files on a lifecycle worker ({@link FileLog#prune}). The loop
 * ends when every window is disposed, then the Display is disposed and {@link #run} returns.
 */
final class Gui {
  private static final Logger LOG = LoggerFactory.getLogger(Gui.class);

  /** Receives the composed application once the window is open; for tests. */
  interface Observer {
    /**
     * Called on the UI thread after the window opens.
     *
     * @param window the main window
     * @param controller the application controller
     * @param workers the thread owner
     */
    void opened(MainWindow window, ApplicationController controller, Workers workers);
  }

  private final StartupRequest request;
  private final ConfigStore config;
  private final Preferences preferences;
  private final PrintStream err;
  private final Observer observer;
  private final InstanceServer server;
  private final LogFiles logFiles;
  private final List<Shell> shells = new ArrayList<Shell>();

  Gui(
      StartupRequest request,
      ConfigStore config,
      Preferences preferences,
      PrintStream err,
      Observer observer) {
    this(request, config, preferences, err, observer, null, LogFiles.NONE);
  }

  /**
   * Creates the composition.
   *
   * @param server the handoff listener when this instance is the primary, else null; {@link #run}
   *     closes it
   * @param logFiles the debug log file
   */
  Gui(
      StartupRequest request,
      ConfigStore config,
      Preferences preferences,
      PrintStream err,
      Observer observer,
      InstanceServer server,
      LogFiles logFiles) {
    this.request = request;
    this.config = config;
    this.preferences = preferences;
    this.err = err;
    this.observer = observer;
    this.server = server;
    this.logFiles = logFiles;
  }

  /**
   * Runs until the main window closes.
   *
   * @return 0, {@link Launcher#EXIT_SWT} when the Display cannot be created, or {@link
   *     Launcher#EXIT_UI_FAILURE} when {@link UiFailures} ran the quit, also when that quit threw
   */
  int run() {
    Workers workers =
        new Workers(Runtime.getRuntime().availableProcessors(), new Launcher.FailureLogger());
    Display display = null;
    try {
      if (server != null) {
        server.start(workers);
      }
      Options options = preferences.options();
      config.positions().load();
      // View colors.
      config.schemes().load();
      boolean restore =
          request.session() == SessionMode.LOAD
              || (request.session() == SessionMode.DEFAULT
                  && request.files().isEmpty()
                  && options.loadLastSession());
      ErrorExitMarker marker = new ErrorExitMarker(config.location());
      ErrorExitMarker.State errorExit = marker.read();
      boolean skipped = restore && errorExit != ErrorExitMarker.State.ABSENT;
      boolean notice = errorExit == ErrorExitMarker.State.PENDING;
      if (skipped && !request.multi()) {
        marker.clear();
      } else if (notice) {
        marker.markShown();
      }
      List<Session> sessions = restore && !skipped ? config.sessions().loadWindows() : List.of();
      Display.setAppName("QLogg");
      try {
        display = new Display();
      } catch (UnsatisfiedLinkError | SWTError e) {
        err.println("qlogg: SWT failed to start: " + LogText.oneLine(e.getMessage()));
        LOG.debug("SWT failed to start", e);
        return Launcher.EXIT_SWT;
      }
      return runWindows(display, workers, options, restore, skipped, notice, sessions, marker);
    } finally {
      if (server != null) {
        server.close();
      }
      workers.close();
      if (display != null) {
        display.dispose();
      }
    }
  }

  /**
   * Composes the application on the Display and runs the event loop until every window closes.
   *
   * @return the exit status
   */
  private int runWindows(
      final Display display,
      Workers workers,
      Options options,
      boolean restore,
      boolean skipped,
      boolean notice,
      List<Session> sessions,
      ErrorExitMarker marker) {
    MemoryBudget budget = MemoryBudget.fromRuntime();
    DocumentServices services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(workers.cpuThreads(), budget)),
            new SearchEngine(
                workers,
                StandardMatchers.INSTANCE,
                SearchSettings.defaults(workers.cpuThreads(), budget)
                    .withMaxResults(preferences.maxResults())),
            BlockCache.fromBudget(budget),
            budget,
            options.defaultCharset(),
            server == null ? null : server.heldLock());
    final SwtThemeService system = SwtThemeService.open(display, preferences.theme());
    final Palette palette =
        ThemeController.resolve(
            preferences.theme(),
            system.systemDark(),
            config.schemes().schemes().find(preferences.view().colors()));
    warmTimeZone(workers);
    // Per-process log pruning.
    if (server != null && logFiles instanceof FileLog fileLog && fileLog.attached()) {
      pruneLogs(workers, fileLog);
    }
    final SwtUiDispatcher ui = new SwtUiDispatcher(display);
    final WindowSet set = new WindowSet(workers, ui, services, config, preferences, system);
    set.setLogging(LogConfigurator.RECENT, logFiles);
    UiFailures failures = new UiFailures(System::nanoTime, set::quit, marker);
    display.setRuntimeExceptionHandler(failures::runtime);
    display.setErrorHandler(failures::error);
    set.setEmbeddedGuide(UserGuide.embedded(config.location().mode(), OsTraits.current()));
    set.setFactory(
        new WindowSet.Factory() {
          @Override
          public ApplicationController create(WindowSet owner, WindowGeometry.Request at) {
            WindowState remembered = owner.settings().state().window();
            return openWindow(
                display,
                owner,
                palette,
                at,
                new WindowGeometry.Request(null, null, remembered.width(), remembered.height()));
          }
        });
    // macOS application menu.
    AppMenu.hook(display, set);
    WindowState remembered = preferences.state().window();
    final MainWindow window = new MainWindow(display, set.keymap(), palette);
    ApplicationController controller = new ApplicationController(set, window);
    attach(window, controller);
    window.place(
        new WindowGeometry.Request(request.x(), request.y(), request.width(), request.height()),
        new WindowGeometry.Request(
            remembered.x(), remembered.y(), remembered.width(), remembered.height()));
    boolean sized =
        request.x() != null
            || request.y() != null
            || request.width() != null
            || request.height() != null;
    if (remembered.maximized() && !sized) {
      window.shell().setMaximized(true);
    }
    window.open();
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            logGeometry(window.shell());
          }
        });
    if (EventLoopWatchdog.enabled()) {
      new EventLoopWatchdog(
              workers,
              ui,
              display.getThread(),
              EventLoopWatchdog.INTERVAL_MILLIS,
              EventLoopWatchdog.BOUND_MILLIS,
              EventLoopWatchdog.LOGGING)
          .start();
    }
    Thread hook = registerHook(set, ui, workers);
    try {
      if (server != null) {
        set.addQuitListener(server::close);
      }
      if (!sessions.isEmpty()) {
        controller.restoreSession(sessions.get(0));
        for (int i = 1; i < sessions.size(); i++) {
          ApplicationController more = set.openWindow();
          if (more != null) {
            more.restoreSession(sessions.get(i));
          }
        }
        controller.windowActivated();
      }
      controller.setSessionOwner(restore && !request.multi());
      if (notice) {
        controller.noticeErrorExit(skipped);
      }
      controller.open(request.files(), request.follow());
      if (server != null) {
        server.deliverTo(
            new InstanceServer.Receiver() {
              @Override
              public boolean deliver(final List<FileRequest> files, final boolean follow) {
                if (set.isQuitting()) {
                  return false;
                }
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        set.openHandoff(files, follow);
                      }
                    });
                return true;
              }
            });
      }
      if (observer != null) {
        observer.opened(window, controller, workers);
      }
      return loop(display, failures, shells);
    } finally {
      removeHook(hook);
    }
  }

  /** Creates, places and opens a window after the first one. */
  private ApplicationController openWindow(
      Display display,
      WindowSet set,
      Palette palette,
      WindowGeometry.Request request,
      WindowGeometry.Request persisted) {
    MainWindow window = new MainWindow(display, set.keymap(), palette);
    ApplicationController controller = new ApplicationController(set, window);
    attach(window, controller);
    window.place(request, persisted);
    window.open();
    return controller;
  }

  /**
   * Loads the default time zone's rules on a {@code qlogg-lifecycle-} worker, so the first file
   * open does not read the JDK time-zone data on the UI thread for the status bar's modified time.
   */
  private static void warmTimeZone(Workers workers) {
    workers.tryStart(
        Workers.Kind.LIFECYCLE,
        new Runnable() {
          @Override
          public void run() {
            ZoneId.systemDefault().getRules();
          }
        });
  }

  /** Runs {@link FileLog#prune} on a {@code qlogg-lifecycle-} worker. */
  private static void pruneLogs(Workers workers, final FileLog fileLog) {
    boolean started =
        workers.tryStart(
            Workers.Kind.LIFECYCLE,
            new Runnable() {
              @Override
              public void run() {
                fileLog.prune(Instant.now(), FileLog::running);
              }
            });
    if (!started) {
      LOG.debug("Old log files not pruned: workers are closed");
    }
  }

  private void attach(MainWindow window, final ApplicationController controller) {
    window.attach(controller);
    window.onCloseRequest(controller::closeWindow);
    shells.add(window.shell());
  }

  /**
   * Runs the event loop until every shell is disposed, or until the quit {@link UiFailures} ran
   * failed. A runtime exception or error that leaves {@code readAndDispatch} goes to {@code
   * failures}; the loop continues. The caller disposes the Display, which disposes the shells left.
   *
   * @param display the Display
   * @param failures the Display's handlers
   * @param shells the main windows' shells
   * @return 0, or {@link Launcher#EXIT_UI_FAILURE} once {@code failures} ran the quit
   */
  static int loop(Display display, UiFailures failures, List<Shell> shells) {
    while (anyOpen(shells) && !failures.quitFailed()) {
      if (!dispatch(display, failures)) {
        display.sleep();
      }
    }
    return failures.failed() ? Launcher.EXIT_UI_FAILURE : 0;
  }

  /** Runs one {@code readAndDispatch}; true after a failure, so the loop checks before sleeping. */
  private static boolean dispatch(Display display, UiFailures failures) {
    try {
      return display.readAndDispatch();
    } catch (RuntimeException e) {
      failures.runtime(e);
      return true;
    } catch (Error e) {
      failures.error(e);
      return true;
    }
  }

  private static boolean anyOpen(List<Shell> shells) {
    for (Shell shell : shells) {
      if (!shell.isDisposed()) {
        return true;
      }
    }
    return false;
  }

  /** Registers the JVM shutdown hook that quits when the process is ended without a quit. */
  private Thread registerHook(
      final WindowSet set, final SwtUiDispatcher ui, final Workers workers) {
    CountDownLatch finished = new CountDownLatch(1);
    set.addFinishListener(finished::countDown);
    Runnable release =
        new Runnable() {
          @Override
          public void run() {
            if (server != null) {
              server.close();
            }
          }
        };
    Thread hook =
        workers.shutdownHook(
            new ShutdownHook(
                new Runnable() {
                  @Override
                  public void run() {
                    ui.asyncExec(
                        new Runnable() {
                          @Override
                          public void run() {
                            set.quit();
                          }
                        });
                  }
                },
                finished,
                release,
                ShutdownHook.BOUND,
                workers::liveThreadNames));
    Runtime.getRuntime().addShutdownHook(hook);
    return hook;
  }

  /** Removes the hook after an ordered quit; the JVM may already be running it. */
  private static void removeHook(Thread hook) {
    try {
      Runtime.getRuntime().removeShutdownHook(hook);
    } catch (IllegalStateException e) {
      LOG.debug("Shutdown in progress; the hook stays");
    }
  }

  private static void logGeometry(Shell shell) {
    if (shell.isDisposed() || !LOG.isInfoEnabled()) {
      return;
    }
    Rectangle area = shell.getClientArea();
    Point location = shell.getLocation();
    LOG.info(
        "Main window open: client area {}x{}, location {},{}",
        area.width,
        area.height,
        location.x,
        location.y);
  }
}
