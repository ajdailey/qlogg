package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.config.FolderSearchSettings;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.FolderRequest;
import io.github.ajdailey.qlogg.app.view.FolderSearchState;
import io.github.ajdailey.qlogg.app.view.NotificationService;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.folder.FileHits;
import io.github.ajdailey.qlogg.core.folder.FolderListener;
import io.github.ajdailey.qlogg.core.folder.FolderProgress;
import io.github.ajdailey.qlogg.core.folder.FolderQuery;
import io.github.ajdailey.qlogg.core.folder.FolderResults;
import io.github.ajdailey.qlogg.core.folder.FolderSearch;
import io.github.ajdailey.qlogg.core.folder.NamePattern;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.search.ExpressionException;
import io.github.ajdailey.qlogg.core.search.ExpressionParser;
import io.github.ajdailey.qlogg.core.search.SearchExpression;
import io.github.ajdailey.qlogg.core.search.SearchLimit;
import io.github.ajdailey.qlogg.core.search.SearchQuery;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.PatternSyntaxException;

/**
 * Search > Search in Folder: runs a {@link FolderSearch} over a folder's files without opening them
 * as tabs, keeps each run as a {@link FolderResultSet}, opens a listed line in a tab and steps
 * through the shown set's lines. Confined to the UI thread, except {@link Events#resultsRefused};
 * the search's events reach it through the {@link UiDispatcher}.
 *
 * <p>One search runs at a time: a new run stops the last, which keeps what it listed. Sets are
 * listed newest first and kept until Clear, Clear All or {@link #clear}; a new run drops the oldest
 * unpinned sets beyond {@link #KEPT_SETS}. Each kept set holds its {@code RESULTS} reservation.
 * When the share refuses a run, the run's thread closes the oldest unpinned other set and retries;
 * the UI thread then drops that set, with one notice per run. The toggles that apply are mode,
 * case, whole word, inverse and combining; range, in selection, context and auto-refresh do not.
 * Lines are matched with ANSI colors removed when {@code view.hideAnsi} is on.
 */
public final class FolderSearchController {
  /** Unpinned sets kept. */
  public static final int KEPT_SETS = 10;

  /** Receives state changes. */
  public interface Listener {
    /** The sets, the shown set, its state or its results changed. Called on the UI thread. */
    void changed();
  }

  private final FolderSearchHost host;
  private final UiDispatcher ui;
  private final NotificationService notices;
  private final List<Listener> listeners = new ArrayList<Listener>();

  /**
   * Newest first. The UI thread changes it and the pins under its lock; a run's thread reads it
   * there.
   */
  private final List<FolderResultSet> sets = new ArrayList<FolderResultSet>();

  private FolderRequest request;
  private FolderSearchState refused;
  private FolderResultSet shown;
  private FolderResultSet running;
  private FileHits stepFile;
  private int stepHit = -1;

  FolderSearchController(FolderSearchHost host, UiDispatcher ui, NotificationService notices) {
    this.host = host;
    this.ui = ui;
    this.notices = notices;
  }

  /**
   * Returns what the window shows on open: the last run's request; else the remembered folder,
   * names, subfolders and exclude ({@code folderSearch.*}) with the shown tab's search text and
   * toggles, the folder falling back to the shown tab's folder when none is remembered.
   *
   * @return the request
   */
  public FolderRequest request() {
    if (request != null) {
      return request;
    }
    FolderSearchSettings saved = host.preferences().state().folderSearch();
    DocumentController document = host.activeDocument();
    String folder = saved.folder();
    if (folder.isBlank() && document != null) {
      Path path = document.document().path();
      Path parent = path == null ? null : path.getParent();
      folder = parent == null ? "" : parent.toString();
    }
    return new FolderRequest(
        folder,
        saved.names(),
        saved.recursive(),
        document == null ? "" : document.search().fieldText(),
        document == null ? SearchOptions.DEFAULTS : document.search().options(),
        saved.exclude());
  }

  /** Remembers a request's folder, names, subfolders and exclude in {@code qlogg.conf}. */
  private void remember(FolderRequest next) {
    if (next.folder().length() > FolderSearchSettings.MAX_CHARS
        || next.names().length() > FolderSearchSettings.MAX_CHARS
        || next.exclude().length() > FolderSearchSettings.MAX_CHARS) {
      return;
    }
    FolderSearchSettings value =
        new FolderSearchSettings(next.folder(), next.names(), next.recursive(), next.exclude());
    Preferences current = host.preferences();
    if (!value.equals(current.state().folderSearch())) {
      host.setPreferences(current.withState(current.state().withFolderSearch(value)));
    }
  }

  /**
   * Stops the running search and starts a new one as the shown set. An empty folder or text, a name
   * or exclude pattern or combined expression that does not parse, or closed workers start no set
   * and make {@link #state} {@code FAILED} with a message; the folder is validated on the search
   * thread.
   *
   * @param next what to search
   */
  public void run(FolderRequest next) {
    cancel();
    request = next;
    remember(next);
    String failure = check(next);
    if (failure != null) {
      refuse(failure);
      return;
    }
    SearchOptions toggles = next.options();
    SearchExpression expression;
    try {
      expression = ExpressionParser.parse(next.text(), toggles.mode(), toggles.combining());
    } catch (ExpressionException e) {
      refuse(UiText.SEARCH_EXPRESSION_ERROR.format(e.column(), SearchController.reason(e)));
      return;
    }
    SearchQuery query =
        new SearchQuery(
            expression,
            toggles.caseSensitive(),
            toggles.wholeWord(),
            toggles.inverse(),
            null,
            host.preferences().view().hideAnsi());
    DocumentServices services = host.services();
    FolderResultSet set = new FolderResultSet(next, new FolderResults(services.budget()));
    try {
      set.setHandle(
          new FolderSearch(services)
              .start(
                  new FolderQuery(
                      next.folder().strip(),
                      NamePattern.of(next.names()),
                      next.recursive(),
                      query,
                      NamePattern.of(next.exclude())),
                  set.results(),
                  new Events(set)));
    } catch (RejectedExecutionException e) {
      set.results().close();
      refuse(UiText.SEARCH_FAILED.format(UiText.SEARCH_SHUT_DOWN.text()));
      return;
    }
    List<FolderResultSet> beyond = new ArrayList<FolderResultSet>();
    synchronized (sets) {
      sets.add(0, set);
      int unpinned = 0;
      for (FolderResultSet kept : sets) {
        if (!kept.pinned() && ++unpinned > KEPT_SETS) {
          beyond.add(kept);
        }
      }
    }
    for (FolderResultSet old : beyond) {
      remove(old);
    }
    running = set;
    show(set);
  }

  /** Returns the first problem with a request that stops it before the search thread. */
  private static String check(FolderRequest next) {
    if (next.folder().isBlank()) {
      return UiText.FOLDER_NO_FOLDER.text();
    }
    if (next.text().isEmpty()) {
      return UiText.FOLDER_NO_TEXT.text();
    }
    try {
      NamePattern.of(next.names());
      NamePattern.of(next.exclude());
    } catch (IllegalArgumentException e) {
      return UiText.FOLDER_BAD_NAMES.format(LogText.oneLine(e.getMessage()));
    }
    return null;
  }

  private void refuse(String message) {
    refused = new FolderSearchState(FolderSearchState.Phase.FAILED, FolderProgress.START, message);
    publish();
  }

  /** Stops the running search; its set keeps the files listed so far. */
  public void cancel() {
    if (running != null) {
      running.handle().cancel();
    }
  }

  /**
   * Shows a set: the window lists its files and the step keys go through its lines. A set not in
   * {@link #sets}, or one whose results a refused run closed and the UI thread has yet to drop, is
   * ignored.
   *
   * @param set a set of {@link #sets}
   */
  public void show(FolderResultSet set) {
    synchronized (sets) {
      if (!sets.contains(set) || set.results().isClosed()) {
        return;
      }
    }
    shown = set;
    refused = null;
    stepFile = null;
    stepHit = -1;
    publish();
  }

  /**
   * Pins a set, or unpins it.
   *
   * @param set a set of {@link #sets}
   */
  public void togglePin(FolderResultSet set) {
    synchronized (sets) {
      set.setPinned(!set.pinned());
    }
    publish();
  }

  /**
   * Clear: stops a set's search and drops it, returning its reservation. The newest other set is
   * shown when it was the shown one.
   *
   * @param set a set of {@link #sets}
   */
  public void drop(FolderResultSet set) {
    remove(set);
    publish();
  }

  /** Clear All: stops the running search and drops every set, returning their reservation. */
  public void clear() {
    List<FolderResultSet> all;
    synchronized (sets) {
      all = new ArrayList<FolderResultSet>(sets);
    }
    for (FolderResultSet set : all) {
      remove(set);
    }
    refused = null;
    publish();
  }

  /** Stops and drops a set; shows the newest other one when it was shown. */
  private void remove(FolderResultSet set) {
    synchronized (sets) {
      sets.remove(set);
    }
    set.setDropped();
    if (set.handle() != null) {
      set.handle().cancel();
    }
    set.results().close();
    if (set == running) {
      running = null;
    }
    if (set == shown) {
      shown = null;
      stepFile = null;
      stepHit = -1;
      synchronized (sets) {
        if (!sets.isEmpty()) {
          shown = sets.get(0);
        }
      }
    }
  }

  /**
   * Returns the kept sets.
   *
   * @return a new list, newest first
   */
  public List<FolderResultSet> sets() {
    synchronized (sets) {
      return new ArrayList<FolderResultSet>(sets);
    }
  }

  /**
   * Returns the shown set.
   *
   * @return the set, or null with none kept
   */
  public FolderResultSet shown() {
    return shown;
  }

  /**
   * Returns the state the window shows: a request refused before its search thread, else the shown
   * set's state, else {@code IDLE}.
   *
   * @return the state
   */
  public FolderSearchState state() {
    if (refused != null) {
      return refused;
    }
    return shown == null ? FolderSearchState.IDLE : shown.state();
  }

  /**
   * Returns the listed files of the shown set; the search adds to them while it runs.
   *
   * @return the files, empty with no set shown
   */
  public List<FileHits> files() {
    return shown == null ? List.<FileHits>of() : shown.files();
  }

  /**
   * Counts the matching lines of the shown set.
   *
   * @return lines
   */
  public long matches() {
    return shown == null ? 0 : shown.matches();
  }

  /**
   * Reports whether the results share stopped the shown set's run.
   *
   * @return true once it did
   */
  public boolean limited() {
    return shown != null && shown.limited();
  }

  /**
   * Shows a listed line: selects the tab that shows the file, by the path searched or its real
   * path, or opens the file in a new tab, and goes to the line. The next step starts there. While
   * the file opens, a later line of the same file replaces the line the new tab goes to instead of
   * opening the file again, for {@link #OPEN_WAIT_NANOS} or until that open fails.
   *
   * @param file a listed file
   * @param hit the listed line, from 0 to {@link FileHits#size} - 1
   */
  public void open(FileHits file, int hit) {
    stepFile = file;
    stepHit = hit;
    long line = file.line(hit) + 1;
    Path path = file.path();
    for (DocumentController document : host.documents()) {
      if (path.equals(document.document().path()) || path.equals(document.document().realPath())) {
        opening = null;
        host.activate(document);
        document.goToLine(line);
        return;
      }
    }
    long now = System.nanoTime();
    if (opening != null && opening.path().equals(path) && now - opening.since() < OPEN_WAIT_NANOS) {
      opening = new Opening(path, line, opening.since());
      return;
    }
    if (!watching) {
      watching = true;
      host.addListener(new Opened());
    }
    opening = new Opening(path, line, now);
    host.openFile(path, line);
  }

  /** How long a file {@link #open} started stays the one later lines of it go to. */
  static final long OPEN_WAIT_NANOS = 5_000_000_000L;

  /** A file {@link #open} started opening, the line its tab goes to, and when it started. */
  private record Opening(Path path, long line, long since) {}

  private Opening opening;
  private boolean watching;

  /**
   * Moves a tab {@link #open} started to the newest line asked for it; a failed open of that file
   * ends the wait, so the next {@link #open} of it opens again.
   */
  private final class Opened implements ApplicationListener {
    @Override
    public void documentOpened(final DocumentController document) {
      Opening started = opening;
      if (started == null
          || !(started.path().equals(document.document().path())
              || started.path().equals(document.document().realPath()))) {
        return;
      }
      opening = null;
      final long line = started.line();
      // After the opener's own move to the first line asked, in the same UI task.
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              if (host.documents().contains(document)) {
                document.goToLine(line);
              }
            }
          });
    }

    @Override
    public void documentClosed(DocumentController document) {
      // Nothing to move.
    }

    @Override
    public void documentOpenFailed(Path path) {
      if (opening != null && opening.path().equals(path)) {
        opening = null;
      }
    }
  }

  // Step keys.

  /**
   * Starts the next step at a file: Next goes to its first listed line, Previous to the last line
   * of the file before it.
   *
   * @param file a listed file of the shown set
   */
  public void stepFrom(FileHits file) {
    stepFile = file;
    stepHit = -1;
  }

  /**
   * Returns the file of the last line opened or stepped to.
   *
   * @return the file, or null before the first step in the shown set
   */
  public FileHits stepFile() {
    return stepFile;
  }

  /**
   * Returns the listed line of the last line opened or stepped to.
   *
   * @return the index in {@link #stepFile}, or -1
   */
  public int stepHit() {
    return stepHit;
  }

  /**
   * Opens the next or previous listed line of the shown set, across its files, after the last line
   * opened or stepped to; the first step goes to the first or last line. Passing an end wraps to
   * the other with a notice, and a set with no listed line gives a notice.
   *
   * @param forward true for the next line
   */
  public void step(boolean forward) {
    List<FileHits> files = files();
    int count = files.size();
    int at = stepFile == null ? -1 : files.indexOf(stepFile);
    int hit = at < 0 ? -1 : stepHit;
    if (at < 0) {
      at = forward ? -1 : count;
    }
    int file = -1;
    int line = -1;
    boolean wrapped = false;
    if (forward) {
      if (at >= 0 && hit + 1 < files.get(at).size()) {
        file = at;
        line = hit + 1;
      } else {
        file = firstWithLines(files, at + 1, count);
        if (file < 0) {
          file = firstWithLines(files, 0, Math.min(at + 1, count));
          wrapped = file >= 0;
        }
        line = 0;
      }
    } else {
      if (at < count && hit > 0) {
        file = at;
        line = hit - 1;
      } else {
        file = lastWithLines(files, at - 1, 0);
        if (file < 0) {
          file = lastWithLines(files, count - 1, Math.max(at, 0));
          wrapped = file >= 0;
        }
        line = file < 0 ? -1 : files.get(file).size() - 1;
      }
    }
    if (file < 0) {
      notices.showNotice(UiText.NOTICE_FOLDER_NO_LINES.text());
      return;
    }
    if (wrapped) {
      notices.showNotice(
          forward
              ? UiText.NOTICE_FOLDER_WRAPPED_FIRST.text()
              : UiText.NOTICE_FOLDER_WRAPPED_LAST.text());
    }
    open(files.get(file), line);
    publish();
  }

  /** Returns the first file from {@code from} up to {@code end} with a listed line, or -1. */
  private static int firstWithLines(List<FileHits> files, int from, int end) {
    for (int i = Math.max(from, 0); i < end; i++) {
      if (files.get(i).size() > 0) {
        return i;
      }
    }
    return -1;
  }

  /** Returns the last file from {@code from} down to {@code end} with a listed line, or -1. */
  private static int lastWithLines(List<FileHits> files, int from, int end) {
    for (int i = Math.min(from, files.size() - 1); i >= end; i--) {
      if (files.get(i).size() > 0) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Adds a listener.
   *
   * @param listener called on the UI thread
   */
  public void addListener(Listener listener) {
    listeners.add(listener);
  }

  /**
   * Removes a listener.
   *
   * @param listener a listener added earlier
   */
  public void removeListener(Listener listener) {
    listeners.remove(listener);
  }

  private void publish() {
    for (Listener listener : new ArrayList<Listener>(listeners)) {
      listener.changed();
    }
  }

  private String failureText(Exception failure) {
    if (failure instanceof PatternSyntaxException syntax) {
      return UiText.SEARCH_REGEX_ERROR.format(
          Math.max(0, syntax.getIndex()) + 1, LogText.oneLine(syntax.getDescription()));
    }
    String message = failure == null ? null : LogText.oneLine(failure.getMessage());
    return UiText.FOLDER_FAILED.format(
        message == null ? UiText.SEARCH_WORKER_FAILED.text() : message);
  }

  /**
   * Closes the results of the oldest unpinned set other than {@code keep}. Called on a run's
   * thread.
   *
   * @return the set, or null when none is left
   */
  private FolderResultSet reclaim(FolderResultSet keep) {
    synchronized (sets) {
      for (int i = sets.size() - 1; i >= 0; i--) {
        FolderResultSet set = sets.get(i);
        if (set != keep && !set.pinned() && !set.results().isClosed()) {
          set.results().close();
          return set;
        }
      }
    }
    return null;
  }

  /** One run's events, posted to the UI thread; a dropped set's events are ignored. */
  private final class Events implements FolderListener {
    private final FolderResultSet set;
    private boolean noticed;

    Events(FolderResultSet set) {
      this.set = set;
    }

    @Override
    public void progressed(final FolderProgress progress) {
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              if (!set.dropped() && set.state().phase() == FolderSearchState.Phase.RUNNING) {
                set.setState(
                    new FolderSearchState(FolderSearchState.Phase.RUNNING, progress, null));
                publish();
              }
            }
          });
    }

    @Override
    public void finished(
        final OperationOutcome outcome, final SearchLimit limit, final Exception failure) {
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              if (set.dropped()) {
                return;
              }
              if (running == set) {
                running = null;
              }
              FolderProgress progress = set.state().progress();
              set.setState(
                  switch (outcome) {
                    case COMPLETED ->
                        new FolderSearchState(FolderSearchState.Phase.DONE, progress, null);
                    case CANCELED ->
                        new FolderSearchState(FolderSearchState.Phase.CANCELED, progress, null);
                    case FAILED ->
                        new FolderSearchState(
                            FolderSearchState.Phase.FAILED, progress, failureText(failure));
                  });
              publish();
            }
          });
    }

    /** Closes the oldest unpinned other set; the UI thread drops it. On the run's thread. */
    @Override
    public boolean resultsRefused() {
      final FolderResultSet freed = reclaim(set);
      if (freed == null) {
        return false;
      }
      final boolean first = !noticed;
      noticed = true;
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              if (!freed.dropped()) {
                remove(freed);
                publish();
              }
              if (first) {
                notices.showNotice(UiText.NOTICE_FOLDER_SET_DROPPED.text());
              }
            }
          });
      return true;
    }
  }
}
