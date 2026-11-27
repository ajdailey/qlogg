package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.Keymap;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SaveResult;
import io.github.ajdailey.qlogg.app.config.Session;
import io.github.ajdailey.qlogg.app.config.SessionStore;
import io.github.ajdailey.qlogg.app.config.WindowState;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.log.LogFiles;
import io.github.ajdailey.qlogg.app.log.RecentLog;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.theme.PaletteListener;
import io.github.ajdailey.qlogg.app.theme.ThemeController;
import io.github.ajdailey.qlogg.app.theme.ThemeService;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.MarkComments;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.watch.FileMonitor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The main windows of one instance and what they share: the settings and their saves, the theme,
 * the key bindings, file monitoring, the document services, the recent log lines and the debug log
 * file. Confined to the UI thread, except {@link #isQuitting} and {@link #addFinishListener}.
 *
 * <p>Files handed to the instance ({@link #openHandoff}) go to the window activated last, except a
 * file a window shows already, which activates that tab. New Window opens an empty window through
 * the {@link Factory}. {@link #moveDocument} moves a tab with its live document to another window
 * or a new one. Closing a window that is not the last closes its documents and drops its tabs from
 * the session; closing the last one, or Quit from any window, runs {@link #quit}: stop accepting
 * operations and detach every window's tabs on the UI thread, hide the windows, then on a {@code
 * qlogg-lifecycle-} worker run the quit listeners, save, close every document at once ({@link
 * LogDocument#closeAll}, 5 s overall), close {@link Workers} (5 s) and any document an open
 * finished but no window claimed, run the finish listeners, and dispose the windows on the UI
 * thread, which ends the event loop. The UI thread never waits.
 *
 * <p>A save captures, on the UI thread, the settings, every tab's position and, when this instance
 * owns the session, each window's tabs and bounds as {@code window.N}; a worker writes them. Each
 * capture takes a sequence number, and a writer skips a part (settings or session) a later capture
 * has written, so a debounced save that ends after the quit save cannot replace newer state. A save
 * whose settings or session write fails re-arms the save timer from the UI thread at the {@link
 * SaveBackoff} delay, unless a save timer waits or quit has begun.
 *
 * <p>{@link #timeSync} holds the time sync groups, which span the windows.
 */
public final class WindowSet implements RememberedStateHost {
  /** Creates and opens a main window. */
  public interface Factory {
    /**
     * Creates a window over the set, places and opens it; called on the UI thread.
     *
     * @param set the set it joins
     * @param at the requested top-left corner in display coordinates and client-area size; a null
     *     position leaves it to the window system, a null size takes the remembered one
     * @param maximized true to open maximized, keeping {@code at} as the normal bounds
     * @return the window's controller, or null when it could not be created
     */
    ApplicationController create(WindowSet set, WindowGeometry.Request at, boolean maximized);
  }

  /**
   * Where a moved tab goes.
   *
   * @param index its tab position in an existing window, 0 first; clamped to the tabs
   * @param x a new window's left edge in display coordinates, or null for the window system
   * @param y a new window's top edge in display coordinates, or null for the window system
   */
  public record Placement(int index, Integer x, Integer y) {}

  private static final Logger LOG = LoggerFactory.getLogger(WindowSet.class);

  private final Workers workers;
  private final UiDispatcher ui;
  private final ConfigStore config;
  private final AtomicReference<Preferences> preferences;
  private final ThemeController theme;
  private final FileMonitor monitor;
  private final List<ApplicationController> windows = new ArrayList<ApplicationController>();
  private final List<ApplicationController> activations = new ArrayList<ApplicationController>();
  private final List<Runnable> quitListeners = new CopyOnWriteArrayList<Runnable>();
  private final List<Runnable> finishListeners = new CopyOnWriteArrayList<Runnable>();
  private final AtomicBoolean finished = new AtomicBoolean();
  private final AtomicBoolean warningPosted = new AtomicBoolean();
  private DocumentServices services;
  private Keymap keymap;
  private Factory factory;
  private RecentLog recent;
  // Embedded user guide.
  private boolean embeddedGuide = true;
  private LogFiles logFiles = LogFiles.NONE;
  private long seenWarnings;
  private volatile boolean quitting;
  private final SaveTimer saveTimer;
  private boolean preferencesDirty;
  private boolean sessionOwner;
  private long captured;

  /**
   * Orders settings writes from different lifecycle workers. Guards {@link #preferencesWritten},
   * {@link #sessionWritten}, {@link #written} and {@link #backoff}; the UI thread never takes it.
   */
  private final ReentrantLock saveLock = new ReentrantLock();

  private final SaveBackoff backoff = new SaveBackoff();

  private long preferencesWritten;
  private long sessionWritten;

  /** The settings last written, or loaded; a save writes only the keys that differ from them. */
  private Preferences written;

  private final PlacementMemory placements = new PlacementMemory();

  // Time sync.
  private final TimeSyncGroups timeSync = new TimeSyncGroups(this);
  // Filter tabs and their parents, across the windows.
  private final DerivedTabs derivedTabs = new DerivedTabs(this);

  /**
   * Creates the set with no window.
   *
   * @param workers the thread owner
   * @param ui the UI-thread dispatcher
   * @param services shared document services
   * @param config the settings store
   * @param preferences the settings loaded at startup
   * @param system the system's light or dark setting
   */
  public WindowSet(
      Workers workers,
      UiDispatcher ui,
      DocumentServices services,
      ConfigStore config,
      Preferences preferences,
      ThemeService system) {
    this.workers = workers;
    this.ui = ui;
    this.services = services;
    this.config = config;
    this.preferences = new AtomicReference<Preferences>(preferences);
    this.written = preferences;
    // The first save rewrites absolute path keys under the base (portable, explicit mode).
    this.preferencesDirty = config.pathsStale();
    // View colors.
    this.theme =
        new ThemeController(
            system, preferences.theme(), config.schemes().schemes(), preferences.view().colors());
    this.monitor = new FileMonitor(workers, preferences.options().monitor());
    this.keymap = Keymap.defaults().withOverrides(preferences.options().shortcuts());
    this.saveTimer =
        new SaveTimer(
            ui,
            new Runnable() {
              @Override
              public void run() {
                if (!quitting) {
                  flushSettings();
                }
              }
            });
    theme.addListener(
        new PaletteListener() {
          @Override
          public void paletteChanged(Palette palette) {
            refreshAllHighlights();
          }
        });
  }

  /**
   * Sets how New Window creates a window; without one New Window does nothing.
   *
   * @param value the factory
   */
  public void setFactory(Factory value) {
    factory = value;
  }

  /**
   * Stops every window from writing its placement and split to {@code qlogg.conf}; for a {@code -m}
   * process.
   */
  public void rememberNoWindow() {
    placements.rememberNone();
  }

  /**
   * Stops a window placed from the command line from writing its placement and split to {@code
   * qlogg.conf}; the session saves {@code saved} as its bounds.
   *
   * @param window the window
   * @param saved the bounds it started from in the saved session or {@code qlogg.conf}
   */
  public void keepPlacement(ApplicationController window, WindowState saved) {
    placements.keep(window, saved);
  }

  /**
   * Sets whether Help > User Guide may show the guide in the embedded browser; see {@link
   * io.github.ajdailey.qlogg.app.about.UserGuide#embedded}. True until set.
   *
   * @param embedded false to open the guide in the system's HTML application instead
   */
  public void setEmbeddedGuide(boolean embedded) {
    this.embeddedGuide = embedded;
  }

  /**
   * Reports whether Help > User Guide may show the guide in the embedded browser.
   *
   * @return the value given to {@link #setEmbeddedGuide}
   */
  boolean embeddedGuide() {
    return embeddedGuide;
  }

  /**
   * Connects Help > Show Log and the warning marker to the recent log lines, and Help > Debug Log
   * File and Open Log Folder to the log file.
   *
   * @param lines the recent lines
   * @param files the log file
   */
  public void setLogging(RecentLog lines, LogFiles files) {
    this.recent = lines;
    this.logFiles = files;
    this.seenWarnings = lines.warnings();
    lines.setWarningListener(
        new Runnable() {
          @Override
          public void run() {
            if (warningPosted.compareAndSet(false, true)) {
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      warningPosted.set(false);
                      showWarningMarkers();
                    }
                  });
            }
          }
        });
  }

  /** Adds a window; its controller's constructor calls it. */
  void register(ApplicationController window) {
    windows.add(window);
    activations.add(window);
  }

  /**
   * Returns the open windows.
   *
   * @return in creation order; closed and closing windows are left out
   */
  public List<ApplicationController> windows() {
    return Collections.unmodifiableList(windows);
  }

  /** Records the window the user activated last; handed files go to it. */
  void activated(ApplicationController window) {
    if (windows.contains(window)) {
      activations.remove(window);
      activations.add(window);
    }
  }

  /**
   * Returns the window handed files go to.
   *
   * @return the window activated last, or null when none is open
   */
  public ApplicationController target() {
    for (int i = activations.size() - 1; i >= 0; i--) {
      ApplicationController window = activations.get(i);
      if (windows.contains(window)) {
        return window;
      }
    }
    return null;
  }

  /**
   * Opens files another launch handed over in the window activated last, and brings it to the
   * front. A file a window shows already, compared by the opened document's path, is shown in that
   * tab instead, at its {@code +line:N} and following with {@code -f}, and that window comes to the
   * front.
   *
   * @param files the validated files
   * @param follow {@code -f} of that launch
   */
  public void openHandoff(List<FileRequest> files, boolean follow) {
    ApplicationController window = target();
    if (quitting || window == null) {
      return;
    }
    window.toFront();
    window.openHanded(files, follow);
  }

  /**
   * Returns the window showing a file, so a handed file activates that tab instead of opening a
   * second one.
   *
   * @param path the opened document's path
   * @return the window activated most recently among those showing it, or null
   */
  ApplicationController holderOf(Path path) {
    for (int i = activations.size() - 1; i >= 0; i--) {
      ApplicationController window = activations.get(i);
      if (windows.contains(window) && window.shows(path)) {
        return window;
      }
    }
    for (ApplicationController window : windows) {
      if (window.shows(path)) {
        return window;
      }
    }
    return null;
  }

  /** Opens an empty window through the factory. */
  public void newWindow() {
    openWindow();
  }

  /**
   * Opens an empty window through the factory and makes it the target of handed files.
   *
   * @return its controller, or null while quitting, without a factory, or when it failed
   */
  public ApplicationController openWindow() {
    return openWindow(new WindowGeometry.Request(null, null, null, null), false);
  }

  /**
   * Opens a session window through the factory at its saved bounds and makes it the target of
   * handed files.
   *
   * @param saved the bounds and maximized state the session saved; unknown values fall back as for
   *     {@link #openWindow()}
   * @return its controller, or null while quitting, without a factory, or when it failed
   */
  public ApplicationController openWindow(WindowState saved) {
    return openWindow(
        new WindowGeometry.Request(saved.x(), saved.y(), saved.width(), saved.height()),
        saved.maximized());
  }

  private ApplicationController openWindow(WindowGeometry.Request at, boolean maximized) {
    if (quitting || factory == null) {
      return null;
    }
    ApplicationController window = factory.create(this, at, maximized);
    if (window == null) {
      LOG.warn("New window not created");
    } else {
      activated(window);
    }
    return window;
  }

  // Tab move between windows.

  /**
   * Moves a tab to another window, or to a new one, with its live document: the index, marks,
   * follow state, position, selection, search results, kept sets, filter, folds, tab name and
   * encoding override go with it, and nothing is read again. The source window shows its next tab,
   * or stays open and empty. The target window shows the tab. The settings and session save once,
   * without the debounce. Call on the UI thread.
   *
   * <p>Nothing moves while quitting, while the tab's reload or reopen runs, when the target window
   * shows the same file (a notice says so), or when no new window could be created.
   *
   * @param source the window the tab is in
   * @param document the tab's controller
   * @param target the window it goes to, or null for a new window
   * @param placement the tab position in {@code target}, or the new window's corner
   * @return the window it moved to, or null when it did not move
   */
  public ApplicationController moveDocument(
      ApplicationController source,
      DocumentController document,
      ApplicationController target,
      Placement placement) {
    if (quitting
        || target == source
        || !windows.contains(source)
        || !source.documents().contains(document)
        || (target != null && !windows.contains(target))
        || source.isReopening(document)) {
      return null;
    }
    LogDocument log = document.document();
    if (target != null && !log.inMemory() && target.shows(log.path())) {
      source.showNotice(UiText.NOTICE_MOVE_SHOWN.format(document.state().title()));
      return null;
    }
    ApplicationController destination = target;
    if (destination == null) {
      destination =
          openWindow(new WindowGeometry.Request(placement.x(), placement.y(), null, null), false);
      if (destination == null) {
        return null;
      }
    }
    String name = source.detachMoved(document);
    destination.attachMoved(document, name, placement.index(), windows);
    if (target != null) {
      activated(target);
      target.toFront();
    }
    // Drops the debounced save the activation scheduled; this save covers it.
    saveTimer.cancel();
    flushSettings();
    return destination;
  }

  /** Returns the filter tabs of every window. */
  DerivedTabs derivedTabs() {
    return derivedTabs;
  }

  // Time sync.

  /**
   * Returns the time sync groups.
   *
   * @return the groups of every window
   */
  public TimeSyncGroups timeSync() {
    return timeSync;
  }

  /** Shows a change of the groups in every window. */
  void timeSyncChanged() {
    for (ApplicationController window : new ArrayList<ApplicationController>(windows)) {
      window.timeSyncChanged();
    }
  }

  // Shared state.

  Workers workers() {
    return workers;
  }

  UiDispatcher ui() {
    return ui;
  }

  ConfigStore config() {
    return config;
  }

  AtomicReference<Preferences> preferences() {
    return preferences;
  }

  ThemeController theme() {
    return theme;
  }

  FileMonitor monitor() {
    return monitor;
  }

  DocumentServices services() {
    return services;
  }

  void setServices(DocumentServices value) {
    services = value;
  }

  /**
   * Returns the key bindings in effect.
   *
   * @return the defaults with the user's overrides
   */
  public Keymap keymap() {
    return keymap;
  }

  /**
   * Returns the settings.
   *
   * @return the latest settings
   */
  @Override
  public Preferences settings() {
    return preferences.get();
  }

  void setKeymap(Keymap value) {
    keymap = value;
  }

  LogFiles logFiles() {
    return logFiles;
  }

  /**
   * Returns the recent log lines and stops the warning marker for what they hold.
   *
   * @return oldest first; empty when logging is not connected
   */
  List<String> readRecent() {
    if (recent == null) {
      return List.of();
    }
    seenWarnings = recent.warnings();
    showWarningMarkers();
    return recent.snapshot();
  }

  private void showWarningMarkers() {
    boolean shown = recent != null && recent.warnings() > seenWarnings;
    for (ApplicationController window : windows) {
      window.showWarningMarker(shown);
    }
  }

  /**
   * Reports whether a warning arrived after Show Log was last opened.
   *
   * @return true while the status bar shows the marker
   */
  boolean newWarnings() {
    return recent != null && recent.warnings() > seenWarnings;
  }

  void refreshAllHighlights() {
    for (ApplicationController window : windows) {
      window.refreshHighlights();
    }
  }

  void fireOptionsChanged(boolean keymap, boolean font, boolean other) {
    if (!keymap && !font && !other) {
      return;
    }
    for (ApplicationController window : new ArrayList<ApplicationController>(windows)) {
      window.fireOptionsChanged(keymap, font, other);
    }
  }

  // Saves.

  void setSessionOwner(boolean owner) {
    sessionOwner = owner;
  }

  /** Replaces the settings and schedules their save. */
  @Override
  public void setPreferences(Preferences changed) {
    preferences.set(changed);
    preferencesDirty = true;
    scheduleSave();
  }

  /** Marks the settings changed without scheduling a save; quit and window close save them. */
  @Override
  public void preferencesChanged(Preferences changed) {
    preferences.set(changed);
    preferencesDirty = true;
  }

  /** Saves {@link ApplicationController#SAVE_DELAY_MILLIS} after the last call. */
  @Override
  public void scheduleSave() {
    if (!quitting) {
      saveTimer.restart(ApplicationController.SAVE_DELAY_MILLIS);
    }
  }

  /** Arms the retry of a failed save unless quit has begun. */
  private void retry(int delay, boolean settings) {
    if (quitting) {
      return;
    }
    if (settings) {
      preferencesDirty = true;
    }
    saveTimer.retry(delay);
  }

  /**
   * Captures the settings, the open tabs' positions and the session on the UI thread and writes
   * them on a worker.
   */
  public void flushSettings() {
    final SaveSnapshot snapshot = capture();
    if (!workers.tryStart(
        Workers.Kind.LIFECYCLE,
        new Runnable() {
          @Override
          public void run() {
            save(snapshot);
          }
        })) {
      LOG.warn("Settings not saved: workers are closed");
    }
  }

  /**
   * What one save writes; null parts are unchanged. {@code sequence} orders captures, so a part is
   * written only when no later capture has written it already.
   */
  private record SaveSnapshot(long sequence, Preferences preferences, List<WindowDraft> windows) {}

  /** A window's session tabs, Always on Top flag and bounds captured on the UI thread. */
  record WindowDraft(List<DraftTab> tabs, int active, boolean onTop, WindowState window) {}

  /**
   * A session tab captured on the UI thread; a file tab's marks are read on the worker.
   *
   * @param tab the tab's controller
   * @param derived a filter tab's definition once {@link SessionFilterTabs#link} gave it its
   *     parent, else null
   */
  record DraftTab(
      String path,
      String name,
      boolean follow,
      long timeOffset,
      int syncGroup,
      LogDocument document,
      DocumentController tab,
      Session.Derived derived) {
    /** Returns this tab with a filter tab's definition. */
    DraftTab withDerived(Session.Derived definition) {
      return new DraftTab(path, name, follow, timeOffset, syncGroup, document, tab, definition);
    }
  }

  private SaveSnapshot capture() {
    long sequence = ++captured;
    for (ApplicationController window : windows) {
      window.rememberPositions();
    }
    Preferences saved = preferencesDirty ? preferences.get() : null;
    preferencesDirty = false;
    if (!sessionOwner) {
      return new SaveSnapshot(sequence, saved, null);
    }
    List<WindowDraft> drafts = new ArrayList<WindowDraft>();
    for (ApplicationController window : windows) {
      drafts.add(placements.draft(window));
    }
    return new SaveSnapshot(sequence, saved, derivedTabs.sessions().link(drafts));
  }

  /** Writes a snapshot. */
  @WorkerThread
  private void save(SaveSnapshot snapshot) {
    saveLock.lock();
    try {
      write(snapshot);
    } finally {
      saveLock.unlock();
    }
  }

  /**
   * Writes the parts of a snapshot no later capture has written; holds {@link #saveLock}. A failed
   * write posts the retry, marking failed settings changed again so the retry writes them.
   */
  @WorkerThread
  private void write(SaveSnapshot snapshot) {
    SaveResult settings = null;
    if (snapshot.preferences() != null && snapshot.sequence() > preferencesWritten) {
      preferencesWritten = snapshot.sequence();
      settings = config.write(written, snapshot.preferences());
      if (settings == SaveResult.WRITTEN) {
        written = snapshot.preferences();
      }
    }
    config.positions().save();
    SaveResult session = writeSession(snapshot);
    if (settings == null && session == null) {
      return;
    }
    final int delay = backoff.record(settings == SaveResult.FAILED || session == SaveResult.FAILED);
    final boolean settingsFailed = settings == SaveResult.FAILED;
    if (delay > 0) {
      LOG.debug("Settings save retries in {} ms", delay);
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              retry(delay, settingsFailed);
            }
          });
    }
  }

  /** Writes the session part; null when this snapshot has none or a later capture wrote it. */
  @WorkerThread
  private SaveResult writeSession(SaveSnapshot snapshot) {
    if (snapshot.windows() == null || snapshot.sequence() <= sessionWritten) {
      return null;
    }
    sessionWritten = snapshot.sequence();
    List<Session> sessions = new ArrayList<Session>();
    int ranges = 0;
    int comments = 0;
    for (WindowDraft window : snapshot.windows()) {
      long dropped = 0;
      List<Session.Tab> tabs = new ArrayList<Session.Tab>();
      for (DraftTab draft : window.tabs()) {
        long[] marks = new long[0];
        List<MarkComments.Comment> tabComments = List.of();
        boolean rotated = draft.document().files() != null && draft.derived() == null;
        List<Session.MemberMarks> members = List.of();
        // A filter tab's marks are its root's.
        if (draft.derived() == null && draft.document().enter()) {
          try {
            if (rotated) {
              SetSessions.Saved saved =
                  SetSessions.save(
                      draft.document(),
                      SessionStore.MAX_MARK_RANGES - ranges,
                      SessionStore.MAX_COMMENTS - comments);
              members = saved.members();
              ranges += saved.ranges();
              comments += saved.comments();
              dropped += saved.droppedComments();
            } else {
              marks =
                  SessionStore.ranges(
                      draft.document().marks(), SessionStore.MAX_MARK_RANGES - ranges);
              tabComments =
                  draft.document().markComments().list(0, SessionStore.MAX_COMMENTS - comments);
              dropped += draft.document().markComments().size() - tabComments.size();
            }
          } finally {
            draft.document().exit();
          }
        }
        ranges += marks.length / 2;
        comments += tabComments.size();
        tabs.add(
            new Session.Tab(
                draft.path(),
                draft.name(),
                draft.follow(),
                marks,
                tabComments,
                draft.timeOffset(),
                draft.syncGroup(),
                rotated,
                members,
                draft.derived()));
      }
      sessions.add(new Session(tabs, window.active(), dropped, window.onTop(), window.window()));
    }
    return config.sessions().writeWindows(sessions);
  }

  // Window close and quit.

  /**
   * Reports whether {@link #quit} has begun; callable from any thread.
   *
   * @return true once quitting
   */
  public boolean isQuitting() {
    return quitting;
  }

  /**
   * Adds code to run first when quit reaches its lifecycle worker, before the save, such as closing
   * a listener whose thread {@link Workers#close} would otherwise wait for. Runs on the UI thread
   * when the workers were closed already.
   *
   * @param listener the code; must not block for long
   */
  public void addQuitListener(Runnable listener) {
    quitListeners.add(listener);
  }

  private void runQuitListeners() {
    for (Runnable listener : quitListeners) {
      listener.run();
    }
  }

  /**
   * Adds code to run once quit has saved, closed the documents and closed {@link Workers}, before
   * the windows are disposed. Runs on the lifecycle worker, or on the UI thread when the workers
   * were closed already; at once when quit has finished.
   *
   * @param listener the code; must not block
   */
  public void addFinishListener(Runnable listener) {
    finishListeners.add(listener);
    if (finished.get() && finishListeners.remove(listener)) {
      listener.run();
    }
  }

  private void finish() {
    if (!finished.compareAndSet(false, true)) {
      return;
    }
    if (recent != null) {
      recent.setWarningListener(null);
    }
    for (Runnable listener : finishListeners) {
      if (finishListeners.remove(listener)) {
        listener.run();
      }
    }
  }

  /**
   * Closes one window. The last open window quits instead.
   *
   * @param window the window
   */
  void close(final ApplicationController window) {
    if (quitting || !windows.contains(window)) {
      return;
    }
    if (windows.size() == 1) {
      quit();
      return;
    }
    windows.remove(window);
    activations.remove(window);
    if (placements.remembers(window)) {
      window.rememberWindow();
    }
    final List<LogDocument> files = window.detachAll();
    flushSettings();
    Runnable finish =
        new Runnable() {
          @Override
          public void run() {
            for (LogDocument late : LogDocument.closeAll(files, workers, LogDocument.CLOSE_BOUND)) {
              LOG.warn(
                  "Operations on {} still running after {}",
                  LogText.oneLine(late.name()),
                  LogDocument.CLOSE_BOUND);
            }
            window.closeUnclaimed();
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    window.disposeView();
                  }
                });
          }
        };
    if (!workers.tryStart(Workers.Kind.LIFECYCLE, finish)) {
      window.disposeView();
    }
  }

  /** Starts quitting every window; a second call does nothing. Call on the UI thread. */
  public void quit() {
    if (quitting) {
      return;
    }
    quitting = true;
    ApplicationController last = target();
    if (last != null && placements.remembers(last)) {
      last.rememberWindow();
    }
    final SaveSnapshot snapshot = capture();
    final List<ApplicationController> closing = new ArrayList<ApplicationController>(windows);
    final List<LogDocument> files = new ArrayList<LogDocument>();
    for (ApplicationController window : closing) {
      files.addAll(window.detachAll());
    }
    windows.clear();
    activations.clear();
    Runnable finish =
        new Runnable() {
          @Override
          public void run() {
            runQuitListeners();
            save(snapshot);
            for (LogDocument late : LogDocument.closeAll(files, workers, LogDocument.CLOSE_BOUND)) {
              LOG.warn(
                  "Operations on {} still running after {}",
                  LogText.oneLine(late.name()),
                  LogDocument.CLOSE_BOUND);
            }
            if (!workers.close(Workers.CLOSE_BOUND)) {
              LOG.warn(
                  "Worker threads still running after {}: {}",
                  Workers.CLOSE_BOUND,
                  LogText.oneLine(workers.liveThreadNames()));
            }
            for (ApplicationController window : closing) {
              window.closeUnclaimed();
            }
            finish();
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    for (ApplicationController window : closing) {
                      window.disposeView();
                    }
                  }
                });
          }
        };
    if (!workers.tryStart(Workers.Kind.LIFECYCLE, finish)) {
      runQuitListeners();
      finish();
      for (ApplicationController window : closing) {
        window.disposeView();
      }
    }
  }
}
