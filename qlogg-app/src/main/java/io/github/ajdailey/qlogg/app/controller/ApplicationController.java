package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.cli.CommandLineParser;
import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.Keymap;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.FollowSettings;
import io.github.ajdailey.qlogg.app.config.FontSettings;
import io.github.ajdailey.qlogg.app.config.Options;
import io.github.ajdailey.qlogg.app.config.PositionStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SearchToggles;
import io.github.ajdailey.qlogg.app.config.Session;
import io.github.ajdailey.qlogg.app.config.SessionStore;
import io.github.ajdailey.qlogg.app.config.UiState;
import io.github.ajdailey.qlogg.app.config.ViewOptions;
import io.github.ajdailey.qlogg.app.config.WindowState;
import io.github.ajdailey.qlogg.app.highlight.ColorLabel;
import io.github.ajdailey.qlogg.app.highlight.HighlightFile;
import io.github.ajdailey.qlogg.app.highlight.HighlightSettings;
import io.github.ajdailey.qlogg.app.highlight.LabelState;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.scratch.TransformListener;
import io.github.ajdailey.qlogg.app.scratch.Transforms;
import io.github.ajdailey.qlogg.app.search.FilterFile;
import io.github.ajdailey.qlogg.app.search.PredefinedFilter;
import io.github.ajdailey.qlogg.app.search.SearchHistory;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.theme.ThemeController;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.theme.ThemeService;
import io.github.ajdailey.qlogg.app.view.MainView;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.model.Visibility;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.time.TimestampFormat;
import io.github.ajdailey.qlogg.core.time.UserFormat;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import io.github.ajdailey.qlogg.core.watch.FileMonitor;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One main window: its tabs and window commands, with the settings, saves and shutdown of the
 * {@link WindowSet} it belongs to. Confined to the UI thread; workers report back through the
 * {@link UiDispatcher}. Each open file is one tab with its own {@link DocumentController}; one tab
 * is shown at a time.
 *
 * <p>Files open on a {@code qlogg-index-} worker, one after another in the order given ({@link
 * LogDocument#open} validates the path, reads the head and detects the encoding); each opens a tab
 * after the existing ones and is shown, so the last file given ends shown. A file already open in a
 * tab shows that tab instead. Indexing and the {@link FileMonitor} watch start when the tab opens.
 * A failure is logged at WARN and shown as a one-line error.
 *
 * <p>Reload ({@code F5}) and a file truncated, replaced or modified on disk reopen the path on a
 * worker; the new controller takes the old one's tab, follow state and search text, and the old one
 * closes. Results start empty. A reload keeps the caret's line, the color labels and the marks
 * (copied on the worker; the first completed indexing drops marks past the last line); a reopen
 * after a change on disk starts without marks and labels. A reopen that fails keeps the tab and its
 * content and resumes the document's checks, so it runs again at the next check that finds a file
 * to reopen; a run of failures shows one notice, and each failed reload its own.
 *
 * <p>Open from clipboard reads the clipboard's text on the UI thread, then {@link
 * LogDocument#ofText} encodes and charges it on a worker, at most {@link #MAX_PASTED_BYTES}. A
 * pasted document has no path: it is never watched, followed or reloaded, and the path commands do
 * nothing on it. Tab names set by Rename Tab are kept per path for the session.
 *
 * <p>Commands of a view go to the focused view of the shown tab: the main view or the filtered
 * view, whichever the user last focused. While the quick find bar is open, find next and previous
 * walk quick find, otherwise the search's matches.
 *
 * <p>A change of the settings, the tabs, a tab's follow state or marks saves every window {@link
 * #SAVE_DELAY_MILLIS} after the last one ({@link WindowSet#flushSettings}). A file opens at {@code
 * +line:N}, else following, else at its remembered position, with its remembered forced encoding.
 * The search history, the predefined filters, the highlighters, the theme, the view options and the
 * follow options live in the settings. Highlight settings apply to every tab at once; the theme's
 * palette goes to the views through {@link #theme}.
 *
 * <p>Closing a tab detaches its controllers on the UI thread, which cancels their search, quick
 * find, reads and mark toggle, then {@link LogDocument#close} on a worker waits for every operation
 * before it closes the file. Closing the window and quitting are {@link WindowSet#close} and {@link
 * WindowSet#quit}. The UI thread never waits.
 */
public final class ApplicationController implements DocumentHost {
  /** Points of the window's height that {@code +} and {@code -} move the split. */
  public static final int SPLIT_STEP = 5;

  /** Largest text Open from Clipboard accepts, in UTF-8 bytes. */
  public static final long MAX_PASTED_BYTES = 16L << 20;

  /** Longest tab name. */
  public static final int TAB_NAME_CHARS = 128;

  /** File name extensions Windows runs rather than opens; lower case, with the dot. */
  static final List<String> WINDOWS_EXECUTABLE =
      List.of(
          ".bat", ".cmd", ".com", ".cpl", ".exe", ".hta", ".js", ".jse", ".lnk", ".msc", ".msi",
          ".pif", ".ps1", ".reg", ".scr", ".url", ".vbe", ".vbs", ".wsf", ".wsh");

  /** Longest predefined filter name made by Save as Filter. */
  public static final int FILTER_NAME_CHARS = 64;

  private static final Logger LOG = LoggerFactory.getLogger(ApplicationController.class);

  private final WindowSet set;
  private long droppedComments;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final ConfigStore config;
  private final AtomicReference<Preferences> preferences;
  private final List<ApplicationListener> listeners = new ArrayList<ApplicationListener>();
  private final QuickFindController quickFind;
  private final ThemeController theme;
  private final FileMonitor monitor;
  private final List<DocumentController> documents = new ArrayList<DocumentController>();
  private final List<DocumentController> reopening = new ArrayList<DocumentController>();
  private DocumentController active;
  private LineViewController focused;
  private final Map<Path, String> tabNames = new HashMap<Path, String>();
  private int pastes;
  private boolean shuttingDown;
  // Open, paste, reload and session restore workers, and the claim of what they open.
  private final DocumentOpener opener;
  // About, licenses, log, debug log file, folders, issue report and user guide.
  private final HelpController help;
  // Wrap, folding, block selection, save, scratchpad, search across files, selectors.
  private final ViewFeatures features;
  // Timestamps.
  private final TimeFeatures time;
  // Tab data dot.
  private final TabDataModel tabData = new TabDataModel();
  // Recently Closed, Open All and the command palette.
  private final RecentFiles recent;
  private CommandPalette palette;
  // Always on Top.
  private final AlwaysOnTop alwaysOnTop;

  /**
   * Creates the only window of a new {@link WindowSet} with a system setting that is always light.
   *
   * @param workers the thread owner
   * @param ui the UI-thread dispatcher
   * @param view the main window
   * @param services shared document services
   * @param config the settings store; its directory is shown in About
   * @param preferences the settings loaded at startup
   */
  public ApplicationController(
      Workers workers,
      UiDispatcher ui,
      MainView view,
      DocumentServices services,
      ConfigStore config,
      Preferences preferences) {
    this(workers, ui, view, services, config, preferences, ThemeService.LIGHT);
  }

  /**
   * Creates the only window of a new {@link WindowSet}.
   *
   * @param workers the thread owner
   * @param ui the UI-thread dispatcher
   * @param view the main window
   * @param services shared document services
   * @param config the settings store; its directory is shown in About
   * @param preferences the settings loaded at startup
   * @param system the system's light or dark setting
   */
  public ApplicationController(
      Workers workers,
      UiDispatcher ui,
      MainView view,
      DocumentServices services,
      ConfigStore config,
      Preferences preferences,
      ThemeService system) {
    this(new WindowSet(workers, ui, services, config, preferences, system), view);
  }

  /**
   * Creates a window in a set.
   *
   * @param set the windows it shares the settings with
   * @param view the window
   */
  public ApplicationController(WindowSet set, MainView view) {
    this.set = set;
    this.workers = set.workers();
    this.ui = set.ui();
    this.view = view;
    this.config = set.config();
    this.preferences = set.preferences();
    Preferences current = preferences.get();
    this.quickFind = new QuickFindController(this, workers, ui, view, view);
    quickFind.applyPreferences(current.quickFindIncremental(), current.quickFindRegex());
    this.theme = set.theme();
    this.features = new ViewFeatures(this, workers, ui, view);
    this.time = new TimeFeatures(this, workers, ui, view, config);
    this.monitor = set.monitor();
    this.opener =
        new DocumentOpener(
            workers,
            ui,
            config,
            new Claims(),
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return preferences.get().follow().keepClosed();
              }
            });
    this.help = new HelpController(set, view, this::isShuttingDown);
    // Recently Closed and Open All.
    this.recent = new RecentFiles(this, workers, ui, view);
    // Tab data dot.
    addListener(tabData);
    // Always on Top.
    this.alwaysOnTop = new AlwaysOnTop(view, this::scheduleSave);
    set.register(this);
  }

  /**
   * Returns the windows this one belongs to.
   *
   * @return the set
   */
  public WindowSet windowSet() {
    return set;
  }

  private void refreshAllHighlights() {
    set.refreshAllHighlights();
  }

  /** Recolors this window's tabs. */
  void refreshHighlights() {
    for (DocumentController document : documents) {
      document.refreshHighlights();
    }
  }

  /**
   * Returns the theme, which pushes palette changes to the views.
   *
   * @return the controller
   */
  public ThemeController theme() {
    return theme;
  }

  /**
   * Sets {@code ui.theme}, saves it and applies the palette. When native controls show the theme
   * only from the next start, says so.
   *
   * @param mode the theme
   */
  public void setTheme(ThemeMode mode) {
    setPreferences(preferences.get().withTheme(mode));
    if (!theme.setMode(mode)) {
      view.showInfo(UiText.THEME_RESTART_TITLE.text(), UiText.THEME_RESTART_MESSAGE.text());
    }
  }

  @Override
  public Palette palette() {
    return theme.palette();
  }

  @Override
  public HighlightSettings highlightSettings() {
    return preferences.get().highlights();
  }

  /**
   * Replaces the highlight settings, saves them and recolors the shown document.
   *
   * @param settings the settings
   */
  public void setHighlightSettings(HighlightSettings settings) {
    setPreferences(preferences.get().withHighlights(settings));
    refreshAllHighlights();
  }

  /**
   * Switches a highlighter set on or off.
   *
   * @param id the set's id
   */
  public void toggleHighlightSet(String id) {
    clearAutoHighlightSet();
    setHighlightSettings(highlightSettings().toggle(id));
  }

  /**
   * Applies a numbered color label to the focused view's selected text.
   *
   * @param label 0 to {@link ColorLabel#COUNT} - 1
   */
  public void applyColorLabel(final int label) {
    final DocumentController document = active;
    LineViewController target = focusedView();
    if (document == null || target == null) {
      return;
    }
    target.selectedText(
        new TextListener() {
          @Override
          public void textRead(String text) {
            document.applyLabel(label, text);
          }
        });
  }

  /** Removes the color label of the focused view's selected text. */
  public void removeColorLabel() {
    final DocumentController document = active;
    LineViewController target = focusedView();
    if (document == null || target == null) {
      return;
    }
    target.selectedText(
        new TextListener() {
          @Override
          public void textRead(String text) {
            document.removeLabel(text);
          }
        });
  }

  /**
   * Reads highlighter sets from a user-chosen file on a worker.
   *
   * @param path the file
   * @param listener receives the sets or the failure
   */
  public void importHighlights(Path path, SettingsFileListener<HighlightSet> listener) {
    runHighlightFile(path, null, listener);
  }

  /**
   * Writes highlighter sets to a user-chosen file on a worker, atomically.
   *
   * @param path the file
   * @param sets the sets
   * @param listener receives the end or the failure
   */
  public void exportHighlights(
      Path path, List<HighlightSet> sets, SettingsFileListener<HighlightSet> listener) {
    runHighlightFile(path, List.copyOf(sets), listener);
  }

  /** Imports when {@code sets} is null, else exports them. */
  private void runHighlightFile(
      final Path path,
      final List<HighlightSet> sets,
      final SettingsFileListener<HighlightSet> listener) {
    boolean started =
        workers.tryStart(
            Workers.Kind.LIFECYCLE,
            new Runnable() {
              @Override
              public void run() {
                List<HighlightSet> result = sets;
                String failure = null;
                try {
                  if (sets == null) {
                    result = HighlightFile.read(path);
                  } else {
                    HighlightFile.write(path, sets);
                  }
                } catch (IOException e) {
                  failure = LogText.oneLine(e.getMessage());
                  LOG.warn(
                      "Highlighter file {} not {}: {}",
                      LogText.oneLine(path),
                      sets == null ? "read" : "written",
                      failure);
                }
                postFileResult(result, failure, listener);
              }
            });
    if (!started) {
      LOG.debug("Highlighter file not processed: workers are closed");
    }
  }

  /** Reports a settings file task's end on the UI thread; a shutdown drops it. */
  private <T> void postFileResult(
      final List<T> done, final String failure, final SettingsFileListener<T> listener) {
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (shuttingDown) {
              return;
            }
            if (failure == null) {
              listener.done(done);
            } else {
              listener.failed(failure);
            }
          }
        });
  }

  /**
   * Returns the current settings.
   *
   * @return the settings
   */
  public Preferences preferences() {
    return preferences.get();
  }

  /**
   * Replaces the settings; a worker saves them {@link #SAVE_DELAY_MILLIS} after the last change.
   *
   * @param changed the new settings
   */
  public void setPreferences(Preferences changed) {
    set.setPreferences(changed);
  }

  /**
   * Returns quick find.
   *
   * @return the controller
   */
  public QuickFindController quickFind() {
    return quickFind;
  }

  /**
   * Adds a listener.
   *
   * @param listener called on the UI thread
   */
  public void addListener(ApplicationListener listener) {
    listeners.add(listener);
  }

  /**
   * Returns each tab's data state.
   *
   * @return this window's model
   */
  public TabDataModel tabData() {
    return tabData;
  }

  /**
   * Returns the shown document.
   *
   * @return its controller, or null
   */
  public DocumentController activeDocument() {
    return active;
  }

  /**
   * Returns the open documents in tab order.
   *
   * @return an unmodifiable view
   */
  public List<DocumentController> documents() {
    return Collections.unmodifiableList(documents);
  }

  /**
   * Records which view has focus, so its commands go to it.
   *
   * @param view the main or filtered view of the shown document
   */
  public void setFocusedView(LineViewController view) {
    focused = view;
  }

  /**
   * Returns the view commands go to: the focused view of the shown document, else its main view.
   *
   * @return the view, or null with no document
   */
  public LineViewController focusedView() {
    if (active == null) {
      return null;
    }
    if (focused != null && !focused.isClosed() && focused.owner() == active) {
      return focused;
    }
    return active;
  }

  @Override
  public SearchOptions initialOptions() {
    return options(preferences.get().state().search());
  }

  @Override
  public void searchRan(String text, SearchOptions options) {
    rememberSearchOptions(options);
    Preferences current = preferences.get();
    List<String> history = current.searchHistory();
    if (options.range()) {
      history = SearchHistory.add(history, options.rangeEnd(), current.historySize());
    }
    history = SearchHistory.add(history, text, current.historySize());
    if (!history.equals(current.searchHistory())) {
      setPreferences(current.withSearchHistory(history));
    }
  }

  @Override
  public boolean runSearchOnAdd() {
    return preferences.get().runSearchOnAdd();
  }

  @Override
  public boolean followOnOverscroll() {
    return preferences.get().follow().onOverscroll();
  }

  /**
   * Replaces the follow options and saves them.
   *
   * @param settings the options
   */
  public void setFollowSettings(FollowSettings settings) {
    setPreferences(preferences.get().withFollow(settings));
  }

  /**
   * Replaces the search history and its size.
   *
   * @param history most recent first
   * @param size 1 to {@link SearchHistory#MAX_SIZE}
   */
  public void setSearchHistory(List<String> history, int size) {
    int bounded = Math.max(1, Math.min(SearchHistory.MAX_SIZE, size));
    setPreferences(preferences.get().withHistorySize(bounded).withSearchHistory(history));
  }

  /**
   * Replaces the predefined filters.
   *
   * @param filters in menu order
   */
  public void setFilters(List<PredefinedFilter> filters) {
    setPreferences(preferences.get().withFilters(filters));
  }

  /**
   * Sets the quick find options and saves them.
   *
   * @param incremental {@code quickFind.incremental}
   * @param regex {@code quickFind.regex}
   */
  public void setQuickFindOptions(boolean incremental, boolean regex) {
    setPreferences(preferences.get().withQuickFind(incremental, regex));
    quickFind.applyPreferences(incremental, regex);
  }

  /**
   * Reads filters from a user-chosen file on a worker.
   *
   * @param path the file
   * @param listener receives the filters or the failure
   */
  public void importFilters(
      final Path path, final SettingsFileListener<PredefinedFilter> listener) {
    runFilterFile(path, null, listener);
  }

  /**
   * Writes filters to a user-chosen file on a worker, atomically.
   *
   * @param path the file
   * @param filters the filters
   * @param listener receives the end or the failure
   */
  public void exportFilters(
      final Path path,
      final List<PredefinedFilter> filters,
      final SettingsFileListener<PredefinedFilter> listener) {
    runFilterFile(path, List.copyOf(filters), listener);
  }

  /** Imports when {@code filters} is null, else exports them. */
  private void runFilterFile(
      final Path path,
      final List<PredefinedFilter> filters,
      final SettingsFileListener<PredefinedFilter> listener) {
    boolean started =
        workers.tryStart(
            Workers.Kind.LIFECYCLE,
            new Runnable() {
              @Override
              public void run() {
                List<PredefinedFilter> result = filters;
                String failure = null;
                try {
                  if (filters == null) {
                    result = FilterFile.read(path);
                  } else {
                    FilterFile.write(path, filters);
                  }
                } catch (IOException e) {
                  failure = LogText.oneLine(e.getMessage());
                  LOG.warn(
                      "Filter file {} not {}: {}",
                      LogText.oneLine(path),
                      filters == null ? "read" : "written",
                      failure);
                }
                postFileResult(result, failure, listener);
              }
            });
    if (!started) {
      LOG.debug("Filter file not processed: workers are closed");
    }
  }

  /**
   * Opens the command-line files, a tab each, at their {@code +line:N} when given.
   *
   * @param files the command-line files
   */
  public void open(List<FileRequest> files) {
    open(files, false);
  }

  /**
   * Opens files on a worker, a tab each in the order given, at their {@code +line:N} when given.
   *
   * @param files the files
   * @param follow true to follow each one, as {@code -f} asks; {@code follow.onLoad} also does
   */
  public void open(final List<FileRequest> files, final boolean follow) {
    open(files, follow, false);
  }

  /**
   * Opens files on a worker; with {@code handed}, a file another window shows already is shown
   * there instead of opening a second tab.
   */
  private void open(final List<FileRequest> files, final boolean follow, final boolean handed) {
    if (!shuttingDown) {
      opener.open(files, follow, handed, set.services());
    }
  }

  /**
   * Opens a file on a worker in a new tab, or shows its tab when it is open already.
   *
   * @param path the path as given
   * @param line the 1-based line to go to once indexed, or 0
   */
  public void openFile(Path path, long line) {
    open(List.of(new FileRequest(path, line)), false);
  }

  /**
   * Shows a document the opener delivered: a new tab, or the tab or other window that shows its
   * file already. The opening position is {@code +line:N}, else follow, else the remembered
   * position.
   */
  private void opened(LogDocument document, DocumentOpener.Opening opening) {
    long line = opening.line();
    boolean follow = opening.follow();
    DocumentController shown = find(document.path());
    ApplicationController holder =
        opening.handed() && shown == null ? set.holderOf(document.path()) : null;
    if (holder != null) {
      closeOnWorker(document);
      holder.showHanded(document.path(), line, follow);
      return;
    }
    if (shown != null) {
      closeOnWorker(document);
    } else {
      shown = create(document);
      documents.add(shown);
      for (ApplicationListener listener : new ArrayList<ApplicationListener>(listeners)) {
        listener.documentOpened(shown);
      }
      if (opening.marksCopied()) {
        shown.restore(LabelState.EMPTY, document.marks().size() > 0);
        if (!opening.marksComplete()) {
          view.showNotice(UiText.NOTICE_MARKS_NOT_KEPT.text());
        }
      }
      start(shown);
      rememberRecent(document);
      shown.time().setOffset(opening.timeOffset());
      set.timeSync().restore(opening.syncGroup(), shown);
    }
    present(shown, line, follow);
  }

  /**
   * Receives what the opener delivers; every call runs on the UI thread while the window is open.
   */
  private final class Claims implements DocumentOpener.OpenListener {
    @Override
    public void opened(LogDocument document, DocumentOpener.Opening opening) {
      ApplicationController.this.opened(document, opening);
    }

    @Override
    public void openFailed(String message) {
      view.showError(UiText.OPEN_FAILED_TITLE.text(), message);
    }

    @Override
    public void pasteFailed(String message) {
      view.showNotice(message);
    }

    @Override
    public void foldersSkipped() {
      view.showNotice(UiText.NOTICE_FOLDERS_NOT_OPENED.text());
    }

    @Override
    public void reopened(
        DocumentController old, LogDocument document, FileChange change, boolean marksComplete) {
      ApplicationController.this.reopened(old, document, change, marksComplete);
    }

    @Override
    public void reopenFailed(DocumentController old, FileChange change, String message) {
      ApplicationController.this.reopenFailed(old, change, message);
    }

    @Override
    public void restoredName(Path path, String name) {
      DocumentController controller = find(path);
      if (controller != null) {
        setTabName(controller, name);
      }
    }

    @Override
    public void restoredActive(Path path) {
      DocumentController controller = find(path);
      if (controller != null) {
        activate(controller);
      }
    }

    @Override
    public void restoreFinished() {
      noticeDroppedComments();
    }
  }

  /** Shows a tab, at a line when given, following when asked or by {@code follow.onLoad}. */
  private void present(DocumentController shown, long line, boolean follow) {
    LogDocument document = shown.document();
    activate(shown);
    boolean following = follow || preferences.get().follow().onLoad();
    if (line > 0) {
      shown.jumpToLine(line);
    } else if (!following && !document.inMemory()) {
      PositionStore.Entry position = config.positions().get(document.path());
      if (!position.equals(PositionStore.Entry.NONE)) {
        shown.restorePosition(position.top(), position.line(), position.column());
      }
    }
    if (following) {
      shown.setFollowing(true);
    }
    scheduleSave();
  }

  /**
   * Opens the clipboard's text in a new tab; the text is encoded and charged on a worker. A
   * clipboard without text, or text over {@link #MAX_PASTED_BYTES} or the {@code PASTED} share,
   * shows a notice.
   */
  public void openClipboard() {
    final String text = view.clipboardText();
    if (shuttingDown) {
      return;
    }
    if (text == null || text.isEmpty()) {
      view.showNotice(UiText.NOTICE_CLIPBOARD_EMPTY.text());
      return;
    }
    opener.paste(UiText.CLIPBOARD_TAB.format(++pastes), text, MAX_PASTED_BYTES, set.services());
  }

  /**
   * Opens files dropped on the window on a worker, a tab each; folders are skipped with a notice.
   *
   * @param paths the dropped paths, not yet validated
   */
  public void openDropped(List<Path> paths) {
    if (!shuttingDown) {
      opener.openDropped(paths, set.services());
    }
  }

  /**
   * Checks on a worker whether every path dragged over the window is a folder, for a drop target
   * that shows no-drop over a drag it would not open.
   *
   * @param paths the dragged paths
   * @param result receives true when every path is a folder, on the UI thread
   */
  public void checkDragged(List<Path> paths, final Consumer<Boolean> result) {
    final List<Path> dragged = List.copyOf(paths);
    workers.tryStart(
        Workers.Kind.INDEX,
        new Runnable() {
          @Override
          public void run() {
            boolean folders = !dragged.isEmpty();
            for (Path path : dragged) {
              folders &= Files.isDirectory(path);
            }
            final boolean foldersOnly = folders;
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    result.accept(foldersOnly);
                  }
                });
          }
        });
  }

  /**
   * Returns the text a tab shows: the name set by Rename Tab, else the document's name.
   *
   * @param document an open document's controller
   * @return the name
   */
  public String tabTitle(DocumentController document) {
    Path path = document.document().path();
    String renamed = path == null ? null : tabNames.get(path);
    return renamed == null ? document.state().title() : renamed;
  }

  /**
   * Asks for a tab's name; an empty name resets it.
   *
   * @param document an open document's controller
   */
  public void renameTab(final DocumentController document) {
    if (document.document().inMemory()) {
      view.showNotice(UiText.NOTICE_NO_FILE.text());
      return;
    }
    view.askTabName(
        tabTitle(document),
        new MainView.NameListener() {
          @Override
          public void nameChosen(String name) {
            setTabName(document, name);
          }
        });
  }

  /**
   * Sets a tab's name for the session, kept per path across reloads and reopening.
   *
   * @param document an open document's controller
   * @param name the name; blank resets it to the file name
   */
  public void setTabName(DocumentController document, String name) {
    Path path = document.document().path();
    if (path == null || !documents.contains(document)) {
      return;
    }
    String label = name.strip();
    if (label.length() > TAB_NAME_CHARS) {
      label = label.substring(0, TAB_NAME_CHARS);
    }
    if (label.isEmpty()) {
      tabNames.remove(path);
    } else {
      tabNames.put(path, label);
    }
    for (ApplicationListener listener : new ArrayList<ApplicationListener>(listeners)) {
      listener.tabRenamed(document);
    }
    scheduleSave();
  }

  /**
   * Resets a tab's name to the file name.
   *
   * @param document an open document's controller
   */
  public void resetTabName(DocumentController document) {
    setTabName(document, "");
  }

  /**
   * Closes every tab except one.
   *
   * @param kept the tab left open
   */
  public void closeOthers(DocumentController kept) {
    for (DocumentController document : new ArrayList<DocumentController>(documents)) {
      if (document != kept) {
        closeDocument(document);
      }
    }
  }

  /**
   * Closes the tabs on one side of a tab.
   *
   * @param from the tab
   * @param left true closes the tabs before it, false the tabs after it
   */
  public void closeSide(DocumentController from, boolean left) {
    int index = documents.indexOf(from);
    if (index < 0) {
      return;
    }
    List<DocumentController> side =
        left
            ? new ArrayList<DocumentController>(documents.subList(0, index))
            : new ArrayList<DocumentController>(documents.subList(index + 1, documents.size()));
    for (DocumentController document : side) {
      closeDocument(document);
    }
  }

  /**
   * Puts a tab's full path on the clipboard.
   *
   * @param document an open document's controller
   */
  public void copyPath(DocumentController document) {
    Path path = document.document().path();
    if (path == null) {
      view.showNotice(UiText.NOTICE_NO_FILE.text());
      return;
    }
    view.setText(path.toString());
  }

  /**
   * Puts a tab's file name on the clipboard.
   *
   * @param document an open document's controller
   */
  public void copyFileName(DocumentController document) {
    Path path = document.document().path();
    Path name = path == null ? null : path.getFileName();
    if (name == null) {
      view.showNotice(UiText.NOTICE_NO_FILE.text());
      return;
    }
    view.setText(name.toString());
  }

  /**
   * Opens the folder holding a tab's file in the system file manager.
   *
   * @param document an open document's controller
   */
  public void openContainingFolder(DocumentController document) {
    Path path = document.document().path();
    Path folder = path == null ? null : path.getParent();
    if (folder == null) {
      view.showNotice(UiText.NOTICE_NO_FILE.text());
      return;
    }
    help.launch(folder);
  }

  /**
   * Opens a tab's file in the application the system associates with it. A file the system would
   * run instead is refused: an executable file, or on Windows a {@link #WINDOWS_EXECUTABLE}
   * extension. The check reads the file's attributes on a worker.
   *
   * @param document an open document's controller
   */
  public void openInEditor(DocumentController document) {
    final Path path = document.document().path();
    if (path == null) {
      view.showNotice(UiText.NOTICE_NO_FILE.text());
      return;
    }
    final boolean windows = view.launchesByExtension();
    boolean started =
        workers.tryStart(
            Workers.Kind.LIFECYCLE,
            new Runnable() {
              @Override
              public void run() {
                final boolean runnable = runsInsteadOfOpening(path, windows);
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        if (shuttingDown) {
                          return;
                        }
                        if (runnable) {
                          view.showNotice(
                              UiText.NOTICE_EDITOR_REFUSED.format(
                                  String.valueOf(path.getFileName())));
                        } else {
                          help.launch(path);
                        }
                      }
                    });
              }
            });
    if (!started) {
      LOG.debug("Editor not opened: workers are closed");
    }
  }

  /**
   * Reports whether the system would run a file rather than open it.
   *
   * @param path the file
   * @param windows true on Windows, which decides by extension
   * @return true for an executable file or extension
   */
  @WorkerThread
  static boolean runsInsteadOfOpening(Path path, boolean windows) {
    if (windows) {
      Path name = path.getFileName();
      String text = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
      for (String extension : WINDOWS_EXECUTABLE) {
        if (text.endsWith(extension)) {
          return true;
        }
      }
      return false;
    }
    return Files.isExecutable(path);
  }

  private DocumentController find(Path path) {
    for (DocumentController document : documents) {
      if (path != null && path.equals(document.document().path())) {
        return document;
      }
    }
    return null;
  }

  private DocumentController create(LogDocument document) {
    TabHost host = new TabHost(this, view);
    DocumentController created =
        new DocumentController(
            document,
            workers,
            ui,
            host,
            host,
            host,
            set.services().budget(),
            set.services().search(),
            host);
    features.configure(created);
    time.configure(created);
    UiState state = preferences.get().state();
    created.setLineNumbers(state.lineNumbers());
    created.filtered().setLineNumbers(state.filteredLineNumbers());
    return created;
  }

  /** Starts indexing and the watch. */
  private void start(DocumentController document) {
    tabData.started(document);
    document.start();
    document.document().startWatching(monitor);
  }

  private void closeOnWorker(LogDocument document) {
    if (!workers.tryStart(Workers.Kind.LIFECYCLE, document::close)) {
      LOG.warn("Cannot close {}: workers are closed", LogText.oneLine(document.path()));
    }
  }

  /**
   * Shows a tab.
   *
   * @param document an open document's controller
   */
  public void activate(DocumentController document) {
    if (document == active || !documents.contains(document)) {
      return;
    }
    quickFind.cancelRunning();
    active = document;
    focused = document;
    view.setTitle(UiText.WINDOW_TITLE_FILE.format(document.state().title()));
    for (ApplicationListener listener : new ArrayList<ApplicationListener>(listeners)) {
      listener.activeDocumentChanged(document);
    }
    scheduleSave();
  }

  /**
   * Moves a tab, as dragging it does.
   *
   * @param document an open document's controller
   * @param index its new position, 0 first; clamped to the tabs
   */
  public void moveDocument(DocumentController document, int index) {
    if (documents.remove(document)) {
      documents.add(Math.max(0, Math.min(index, documents.size())), document);
      scheduleSave();
    }
  }

  // Tab move between windows.

  /**
   * Moves a tab to a new window; see {@link WindowSet#moveDocument}.
   *
   * @param document an open document's controller
   * @param x the new window's left edge in display coordinates, or null
   * @param y the new window's top edge in display coordinates, or null
   */
  public void moveToNewWindow(DocumentController document, Integer x, Integer y) {
    set.moveDocument(this, document, null, new WindowSet.Placement(0, x, y));
  }

  /**
   * Moves a tab to another window at a tab position; see {@link WindowSet#moveDocument}.
   *
   * @param document an open document's controller
   * @param target the other window's controller
   * @param index the tab position there, 0 first; clamped to its tabs
   */
  public void moveToWindow(DocumentController document, ApplicationController target, int index) {
    set.moveDocument(this, document, target, new WindowSet.Placement(index, null, null));
  }

  /** Reports whether a tab's reload or reopen is running, which a move waits out. */
  boolean isReopening(DocumentController document) {
    return reopening.contains(document);
  }

  /**
   * Removes a tab whose document moves to another window, without detaching or closing it, and
   * shows a neighbor as a close does.
   *
   * @return the tab's Rename Tab name, or null
   */
  String detachMoved(DocumentController document) {
    int index = documents.indexOf(document);
    documents.remove(index);
    quickFind.cancelRunning(document);
    features.documentClosed(document);
    if (focused == document || focused == document.filtered()) {
      focused = null;
    }
    Path path = document.document().path();
    String name = path == null ? null : tabNames.remove(path);
    for (ApplicationListener listener : new ArrayList<ApplicationListener>(listeners)) {
      listener.documentMovedOut(document);
    }
    showAfterRemoval(document, index);
    return name;
  }

  /**
   * Adds a tab moved from another window and shows it; its controllers report to this window from
   * now on.
   *
   * @param name its Rename Tab name, or null
   * @param index its tab position, clamped to the tabs
   * @param open the open windows
   */
  void attachMoved(
      DocumentController document, String name, int index, List<ApplicationController> open) {
    if (document.host() instanceof TabHost host) {
      host.moveTo(this, view, open);
    }
    int at = Math.max(0, Math.min(index, documents.size()));
    documents.add(at, document);
    Path path = document.document().path();
    if (name != null && path != null) {
      tabNames.put(path, name);
    }
    for (ApplicationListener listener : new ArrayList<ApplicationListener>(listeners)) {
      listener.documentMovedIn(document, at);
    }
    activate(document);
  }

  /** Shows a notice in this window's status bar. */
  void showNotice(String message) {
    view.showNotice(message);
  }

  private void showNeighbor(int step) {
    int index = documents.indexOf(active);
    if (index < 0 || documents.size() < 2) {
      return;
    }
    activate(documents.get(Math.floorMod(index + step, documents.size())));
  }

  /** Closes the shown tab, if any. */
  public void closeDocument() {
    if (active != null) {
      closeDocument(active);
    }
  }

  /**
   * Closes a tab: its controllers detach, and its document closes on a worker once every operation
   * on it ended. The tab after it, else the one before, is shown when it was the shown one. With
   * {@code tabs.closeWindowWithLastTab} on, closing the last tab closes the window.
   *
   * @param document an open document's controller
   */
  public void closeDocument(DocumentController document) {
    if (removeDocument(document)) {
      closeWindowAfterLastTab();
    }
  }

  // Last tab.

  /** Closes the window once its last tab closed, when the setting asks for it. */
  private void closeWindowAfterLastTab() {
    if (documents.isEmpty() && !shuttingDown && preferences().options().closeWindowWithLastTab()) {
      closeWindow();
    }
  }

  /** Closes a tab as {@link #closeDocument} does, leaving the window open; false when not open. */
  private boolean removeDocument(DocumentController document) {
    int index = documents.indexOf(document);
    if (index < 0) {
      return false;
    }
    documents.remove(index);
    reopening.remove(document);
    quickFind.cancelRunning(document);
    features.documentClosed(document);
    rememberPosition(document);
    // Recently Closed.
    if (!shuttingDown) {
      recent.closed(document.document());
    }
    scheduleSave();
    document.close();
    for (ApplicationListener listener : new ArrayList<ApplicationListener>(listeners)) {
      listener.documentClosed(document);
    }
    showAfterRemoval(document, index);
    return true;
  }

  /** Shows the tab after a removed one, else the one before, when the removed one was shown. */
  private void showAfterRemoval(DocumentController document, int index) {
    if (active != document) {
      return;
    }
    active = null;
    focused = null;
    if (documents.isEmpty()) {
      view.setTitle(UiText.WINDOW_TITLE.text());
      for (ApplicationListener listener : new ArrayList<ApplicationListener>(listeners)) {
        listener.activeDocumentChanged(null);
      }
    } else {
      activate(documents.get(Math.min(index, documents.size() - 1)));
    }
  }

  /**
   * Closes every tab; with {@code tabs.closeWindowWithLastTab} on and a tab open, then the window.
   */
  public void closeAll() {
    boolean closed = false;
    for (DocumentController document : new ArrayList<DocumentController>(documents)) {
      closed |= removeDocument(document);
    }
    if (closed) {
      closeWindowAfterLastTab();
    }
  }

  /**
   * Reopens a tab's file on a worker and indexes it from the start; the caret keeps its line.
   *
   * @param document an open document's controller
   */
  public void reload(DocumentController document) {
    reopen(document, null);
  }

  @Override
  public void reopenChanged(DocumentController document, FileChange change) {
    reopen(document, change);
  }

  /** Reopens on a worker; {@code change} is null for a reload. */
  private void reopen(final DocumentController old, final FileChange change) {
    if (shuttingDown || old.isClosed() || reopening.contains(old) || !documents.contains(old)) {
      return;
    }
    if (old.document().inMemory()) {
      view.showNotice(UiText.NOTICE_NO_FILE.text());
      return;
    }
    reopening.add(old);
    if (!opener.reopen(old, change, set.services())) {
      reopening.remove(old);
    }
  }

  private void reopenFailed(DocumentController old, FileChange change, String message) {
    reopening.remove(old);
    if (!old.isClosed() && (old.reopenFailed() || change == null)) {
      view.showNotice(UiText.NOTICE_RELOAD_FAILED.format(old.state().title(), message));
    }
  }

  private void reopened(
      DocumentController old, LogDocument document, FileChange change, boolean marksComplete) {
    reopening.remove(old);
    int index = documents.indexOf(old);
    if (index < 0) {
      closeOnWorker(document);
      return;
    }
    DocumentController replacement = create(document);
    replacement.setLineNumbers(old.lineNumbers());
    replacement.filtered().setLineNumbers(old.filtered().lineNumbers());
    replacement.search().setText(old.search().fieldText(), old.search().options());
    // Navigation history.
    replacement.takeHistory(old);
    SearchViewState ran = old.search().state();
    boolean rerun =
        change != null
            && old.search().options().autoRefresh()
            && ran.phase() != SearchViewState.Phase.IDLE
            && ran.phase() != SearchViewState.Phase.CANCELED;
    boolean follow = old.following();
    long timeOffset = old.time().offset();
    long caret = Math.max(0, old.sourceLine(old.selection().caret().line()));
    LabelState labels = old.labels();
    documents.set(index, replacement);
    quickFind.cancelRunning(old);
    features.documentClosed(old);
    set.timeSync().replace(old, replacement);
    old.close();
    if (active == old) {
      active = replacement;
      focused = replacement;
    }
    for (ApplicationListener listener : new ArrayList<ApplicationListener>(listeners)) {
      listener.documentReplaced(old, replacement);
    }
    if (change == null) {
      replacement.restore(labels, document.marks().size() > 0);
      if (!marksComplete) {
        view.showNotice(UiText.NOTICE_MARKS_NOT_KEPT.text());
      }
    }
    start(replacement);
    if (rerun) {
      // The search that ran goes again; the field and toggles stay as the user left them.
      replacement.search().run(ran.text(), ran.options());
      replacement.search().setText(old.search().fieldText(), old.search().options());
    }
    replacement.time().setOffset(timeOffset);
    if (follow) {
      replacement.setFollowing(true);
    } else if (change == null) {
      replacement.keepLine(caret + 1);
    }
    if (rerun) {
      view.showNotice(UiText.NOTICE_FILE_SEARCH_RERUN.format(replacement.state().title()));
    } else if (change != null) {
      UiText notice =
          switch (change) {
            case TRUNCATED -> UiText.NOTICE_FILE_TRUNCATED;
            case MODIFIED -> UiText.NOTICE_FILE_MODIFIED;
            default -> UiText.NOTICE_FILE_REPLACED;
          };
      view.showNotice(notice.format(replacement.state().title()));
    }
  }

  /**
   * Runs a command from a menu or a key. View commands go to the focused view.
   *
   * @param command the command
   */
  public void execute(CommandId command) {
    if (shuttingDown) {
      return;
    }
    switch (command) {
      case OPEN -> chooseAndOpen();
      case OPEN_CLIPBOARD -> openClipboard();
      case SWITCH_FILE -> {
        if (!documents.isEmpty()) {
          view.showOpenedFiles();
        }
      }
      case CLOSE -> closeDocument();
      case CLOSE_ALL -> closeAll();
      case COPY_PATH, COPY_FILE_NAME, OPEN_FOLDER, OPEN_IN_EDITOR -> {
        if (active != null) {
          pathCommand(command, active);
        }
      }
      case RELOAD -> {
        if (active != null) {
          reload(active);
        }
      }
      case NEXT_TAB -> showNeighbor(1);
      case PREVIOUS_TAB -> showNeighbor(-1);
      case FOLLOW_ON_LOAD -> {
        FollowSettings follow = preferences.get().follow();
        setFollowSettings(
            new FollowSettings(!follow.onLoad(), follow.onOverscroll(), follow.keepClosed()));
      }
      case FOLLOW_ON_OVERSCROLL -> {
        FollowSettings follow = preferences.get().follow();
        setFollowSettings(
            new FollowSettings(follow.onLoad(), !follow.onOverscroll(), follow.keepClosed()));
      }
      case QUIT -> shutdown();
      case GO_TO_LINE -> askLine();
      case FOCUS_SEARCH -> view.focusSearch();
      case RELEASE_FOCUS -> view.focusView(focusedView() == active);
      case THEME_LIGHT -> setTheme(ThemeMode.LIGHT);
      case THEME_DARK -> setTheme(ThemeMode.DARK);
      case THEME_SYSTEM -> setTheme(ThemeMode.SYSTEM);
      case TOGGLE_OVERVIEW -> setOverview(!preferences.get().overview());
      case TOGGLE_MATCH_HIGHLIGHT ->
          setHighlightSettings(
              highlightSettings()
                  .withMatch(
                      highlightSettings()
                          .match()
                          .withEnabled(!highlightSettings().match().enabled())));
      case EDIT_HIGHLIGHTERS -> view.showHighlightersDialog();
      case NO_HIGHLIGHTERS -> {
        clearAutoHighlightSet();
        setHighlightSettings(highlightSettings().withActive(List.of()));
      }
      case FIND, QUICK_FIND_FORWARD -> openQuickFind(true);
      case QUICK_FIND_BACKWARD -> openQuickFind(false);
      case FIND_SELECTION_FORWARD -> quickFind.findSelection(true);
      case FIND_SELECTION_BACKWARD -> quickFind.findSelection(false);
      case FIND_NEXT -> findNext(true);
      case FIND_PREVIOUS -> findNext(false);
      case GROW_MAIN_VIEW -> view.resizeMainView(SPLIT_STEP);
      case SHRINK_MAIN_VIEW -> view.resizeMainView(-SPLIT_STEP);
      case SAVE_SPLIT -> saveSplit();
      case RUN_SEARCH_ON_ADD ->
          setPreferences(preferences.get().withRunSearchOnAdd(!runSearchOnAdd()));
      case EDIT_HISTORY -> view.showHistoryDialog();
      case CLEAR_HISTORY -> setPreferences(preferences.get().withSearchHistory(List.of()));
      case PREDEFINED_FILTERS -> view.showFiltersDialog();
      case KEYBOARD_SHORTCUTS -> view.showKeyboardShortcuts();
      case COMMAND_LINE_OPTIONS ->
          view.showText(CommandId.COMMAND_LINE_OPTIONS.title(), CommandLineParser.usage());
      case ABOUT -> showAbout();
      case USER_GUIDE -> help.showGuide();
      // Preferences dialog, recent files, favorites, encoding and zoom.
      case PREFERENCES -> view.showPreferences();
      case CLEAN_RECENT -> cleanRecent();
      case ADD_FAVORITE -> addFavorite();
      case REMOVE_FAVORITE -> removeFavorite();
      case ENCODING_AUTO -> {
        if (active != null) {
          setEncoding(active, null);
        }
      }
      case ZOOM_IN -> zoomFont(1);
      case ZOOM_OUT -> zoomFont(-1);
      case ZOOM_RESET -> setFont(preferences.get().options().font().withoutZoom());
      // Multiple windows, logging and support items.
      case NEW_WINDOW -> set.newWindow();
      // Tab move between windows.
      case MOVE_TO_NEW_WINDOW -> {
        if (active != null) {
          moveToNewWindow(active, null, null);
        }
      }
      case SHOW_LOG -> help.showLog();
      case DEBUG_LOG_FILE -> setDebugLogFile(!set.logFiles().attached());
      case OPEN_LOG_FOLDER -> help.openFolder(set.logFiles().directory());
      case OPEN_SETTINGS_FOLDER -> help.openFolder(config.location().directory());
      case REPORT_ISSUE -> help.reportIssue();
      // Mark comments and marks list.
      case MARKS_LIST -> view.showMarks();
      case EDIT_MARK_COMMENT -> editMarkComment();
      // Open All, Recently Closed and the command palette.
      case OPEN_ALL_RECENT -> recent.openAll(preferences.get().state().recentFiles());
      case OPEN_ALL_CLOSED -> recent.openAll(preferences.get().state().closedFiles());
      case CLEAR_CLOSED -> recent.clearClosed();
      // Clear Recent Files.
      case CLEAR_RECENT -> recent.clearRecent();
      case COMMAND_PALETTE -> view.showCommandPalette();
      // Reopen Closed Tab.
      case REOPEN_CLOSED -> recent.reopenClosed();
      // Always on Top.
      case ALWAYS_ON_TOP -> alwaysOnTop.toggle();
      // Close Multiple Tabs.
      case CLOSE_OTHERS -> {
        if (active != null) {
          closeOthers(active);
        }
      }
      case CLOSE_LEFT, CLOSE_RIGHT -> {
        if (active != null) {
          closeSide(active, command == CommandId.CLOSE_LEFT);
        }
      }
      default -> {
        if (!features.execute(command) && !time.execute(command)) {
          documentCommand(command);
        }
      }
    }
  }

  // Timestamps.

  /**
   * Lists the formats View > Timestamps > Format offers: the built-ins, then the user formats.
   *
   * @return the formats
   */
  public List<TimestampFormat> timeFormats() {
    return time.formats();
  }

  /**
   * Applies a timestamp format to a document and records it for its file.
   *
   * @param document an open document
   * @param format the format, or null for none
   */
  public void setTimeFormat(DocumentController document, TimestampFormat format) {
    if (documents.contains(document)) {
      time.setFormat(document, format);
    }
  }

  /**
   * Replaces the user timestamp formats and saves them.
   *
   * @param formats the formats in order
   */
  public void setUserTimeFormats(List<UserFormat> formats) {
    time.setUserFormats(formats);
  }

  // Mark comments and marks list.

  /**
   * Asks for the comment of the focused view's caret line and sets it; the line's mark is created
   * when it has none.
   */
  private void editMarkComment() {
    LineViewController target = focusedView();
    if (target == null) {
      return;
    }
    final DocumentController document = target.owner();
    final long line = target.sourceLine(target.selection().caret().line());
    if (line < 0 || line >= document.document().index().lineCount()) {
      return;
    }
    String current = document.markComment(line);
    view.askMarkComment(
        line,
        current == null ? "" : current,
        new MainView.NameListener() {
          @Override
          public void nameChosen(String text) {
            document.setMarkComment(line, text);
          }
        });
  }

  /**
   * Removes a listener added earlier.
   *
   * @param listener the listener
   */
  public void removeListener(ApplicationListener listener) {
    listeners.remove(listener);
  }

  // Wrap, folding, block selection, save, scratchpad, search across files, selectors.

  /**
   * Returns the search across open files.
   *
   * @return the controller
   */
  public MultiSearchController multiSearch() {
    return features.multiSearch();
  }

  // Search in a folder.

  /**
   * Returns the search in a folder.
   *
   * @return the controller
   */
  public FolderSearchController folderSearch() {
    return features.folderSearch();
  }

  /**
   * Reports whether a plain drag in a log view selects a block of columns.
   *
   * @return true with Edit > Block Selection on
   */
  public boolean blockMode() {
    return features.blockMode();
  }

  /**
   * Replaces the view settings and saves them: selectors apply to files opened later, fold rules at
   * once.
   *
   * @param options the settings
   */
  public void setViewOptions(ViewOptions options) {
    features.setView(options);
  }

  /** Drops the shown document's automatic highlighter set, as a manual choice of sets does. */
  private void clearAutoHighlightSet() {
    if (active != null && active.autoHighlightSet() != null) {
      active.setAutoHighlightSet(null);
    }
  }

  private void pathCommand(CommandId command, DocumentController document) {
    switch (command) {
      case COPY_PATH -> copyPath(document);
      case COPY_FILE_NAME -> copyFileName(document);
      case OPEN_FOLDER -> openContainingFolder(document);
      default -> openInEditor(document);
    }
  }

  /** Runs a command of the shown document's search, filtered view or focused view. */
  private void documentCommand(CommandId command) {
    final DocumentController document = active;
    if (document == null) {
      return;
    }
    final SearchController search = document.search();
    LineViewController target = focusedView();
    switch (command) {
      case TOGGLE_CASE,
          TOGGLE_REGEX,
          TOGGLE_INVERSE,
          TOGGLE_COMBINING,
          TOGGLE_AUTO_REFRESH,
          TOGGLE_KEEP_RESULTS,
          TOGGLE_RANGE -> {
        search.toggle(command);
        rememberSearchOptions(search.options());
      }
      case SELECT_RANGE -> search.selectRange();
      // Clear Results.
      case CLEAR_RESULTS -> search.clear();
      case SHOW_MARKS_AND_MATCHES ->
          document.filtered().setVisibility(Visibility.MARKS_AND_MATCHES);
      case SHOW_MARKS -> document.filtered().setVisibility(Visibility.MARKS);
      case SHOW_MATCHES -> document.filtered().setVisibility(Visibility.MATCHES);
      case NEXT_VISIBILITY ->
          document.filtered().setVisibility(document.filtered().visibility().next());
      case PREVIOUS_VISIBILITY ->
          document.filtered().setVisibility(document.filtered().visibility().previous());
      case TOGGLE_FILTERED_LINE_NUMBERS -> {
        document.filtered().toggleLineNumbers();
        rememberLineNumbers(document);
      }
      case TOGGLE_LINE_NUMBERS -> {
        document.toggleLineNumbers();
        rememberLineNumbers(document);
      }
      // Navigation history: Go Back and Go Forward.
      case NEXT_MARK, PREVIOUS_MARK, FOLLOW, STOP, GO_BACK, GO_FORWARD -> document.execute(command);
      case ADD_TO_SEARCH -> editSearch(target, search, SearchController.Edit.ADD);
      case EXCLUDE_FROM_SEARCH -> editSearch(target, search, SearchController.Edit.EXCLUDE);
      case REPLACE_SEARCH -> editSearch(target, search, SearchController.Edit.REPLACE);
      case SET_SEARCH_START -> search.setSearchStart(caretLine(target));
      case SET_SEARCH_END -> search.setSearchEnd(caretLine(target));
      case CLEAR_SEARCH_LIMITS -> search.clearSearchLimits();
      case SAVE_AS_FILTER -> askFilterName(search.fieldText(), search.options().mode());
      case NEXT_COLOR_LABEL ->
          target.selectedText(
              new TextListener() {
                @Override
                public void textRead(String text) {
                  document.nextLabel(text);
                }
              });
      case CLEAR_COLOR_LABELS -> document.clearLabels();
      case COLOR_LABEL_1,
          COLOR_LABEL_2,
          COLOR_LABEL_3,
          COLOR_LABEL_4,
          COLOR_LABEL_5,
          COLOR_LABEL_6,
          COLOR_LABEL_7,
          COLOR_LABEL_8,
          COLOR_LABEL_9 ->
          applyColorLabel(command.ordinal() - CommandId.COLOR_LABEL_1.ordinal());
      default -> target.execute(command);
    }
  }

  private static long caretLine(LineViewController target) {
    return Math.max(0, target.sourceLine(target.selection().caret().line()));
  }

  private void editSearch(
      LineViewController target, final SearchController search, final SearchController.Edit edit) {
    target.selectedText(
        new TextListener() {
          @Override
          public void textRead(String text) {
            search.edit(edit, text);
          }
        });
  }

  private void findNext(boolean forward) {
    if (quickFind.isOpen()) {
      if (forward) {
        quickFind.next();
      } else {
        quickFind.previous();
      }
    } else if (active != null) {
      if (forward) {
        active.search().findNext();
      } else {
        active.search().findPrevious();
      }
    }
  }

  private void openQuickFind(boolean forward) {
    quickFind.open(forward);
    view.focusQuickFind();
  }

  /** Called by quick find when it opened itself for a selection. */
  void quickFindOpened() {
    view.focusQuickFind();
  }

  private void saveSplit() {
    int percent =
        Math.max(Preferences.MIN_SPLIT, Math.min(Preferences.MAX_SPLIT, view.splitPercent()));
    setPreferences(preferences.get().withSplitPercent(percent));
    view.showNotice(UiText.NOTICE_SPLIT_SAVED.format(percent));
  }

  /** Shows or hides the overview bar and saves the choice. */
  private void setOverview(boolean shown) {
    setPreferences(preferences.get().withOverview(shown));
    view.showOverview(shown);
  }

  /** Asks for a filter name, prefilled with the pattern cut at {@link #FILTER_NAME_CHARS}. */
  private void askFilterName(final String text, final MatchMode mode) {
    if (text.isEmpty()) {
      return;
    }
    String suggested =
        text.length() > FILTER_NAME_CHARS ? text.substring(0, FILTER_NAME_CHARS) : text;
    view.askFilterName(
        suggested,
        new MainView.NameListener() {
          @Override
          public void nameChosen(String name) {
            saveAsFilter(name, text, mode);
          }
        });
  }

  /**
   * Adds the search text as a predefined filter; an equal filter is not added twice.
   *
   * @param name the filter's name; blank names it after the pattern
   * @param text the search text
   * @param mode the search mode
   */
  public void saveAsFilter(String name, String text, MatchMode mode) {
    if (text.isEmpty()) {
      return;
    }
    List<PredefinedFilter> filters = new ArrayList<PredefinedFilter>(preferences.get().filters());
    String label = name.strip();
    if (label.isEmpty()) {
      label = text;
    }
    if (label.length() > FILTER_NAME_CHARS) {
      label = label.substring(0, FILTER_NAME_CHARS);
    }
    PredefinedFilter filter = new PredefinedFilter(label, text, mode);
    for (PredefinedFilter existing : filters) {
      if (existing.pattern().equals(text) && existing.mode() == mode) {
        view.showNotice(UiText.NOTICE_FILTER_EXISTS.text());
        return;
      }
    }
    if (filters.size() >= FilterFile.MAX_FILTERS) {
      view.showNotice(UiText.FILTER_FILE_TOO_MANY.format(FilterFile.MAX_FILTERS));
      return;
    }
    filters.add(filter);
    setFilters(filters);
    view.showNotice(UiText.NOTICE_FILTER_SAVED.format(label));
  }

  private void chooseAndOpen() {
    Path initial = null;
    if (active != null && !active.document().inMemory()) {
      initial = active.document().path().getParent();
    } else {
      initial = rememberedDirectory();
    }
    List<Path> chosen = view.chooseFilesToOpen(initial);
    List<FileRequest> requests = new ArrayList<FileRequest>();
    for (Path path : chosen) {
      requests.add(new FileRequest(path, 0));
    }
    Path parent = chosen.isEmpty() ? null : chosen.get(0).getParent();
    if (parent != null) {
      UiState state = preferences.get().state();
      String directory = parent.toString();
      if (!directory.equals(state.openDirectory())) {
        setPreferences(preferences.get().withState(state.withOpenDirectory(directory)));
      }
    }
    open(requests, false);
  }

  private void askLine() {
    final DocumentController document = active;
    if (document == null) {
      return;
    }
    view.askLineNumber(
        document.viewport().lineCount(),
        new MainView.LineNumberListener() {
          @Override
          public void lineChosen(long line) {
            if (active == document) {
              document.jumpToLine(line);
            }
          }
        });
  }

  // Command palette.

  /**
   * Returns the command palette over this window's commands.
   *
   * @return the palette, created on first use
   */
  public CommandPalette commandPalette() {
    if (palette == null) {
      palette = new CommandPalette(this);
    }
    return palette;
  }

  /** Collects the About facts on a worker, then shows the dialog. */
  public void showAbout() {
    help.showAbout();
  }

  /** Reads the license notices from the jar on a worker, then shows them. */
  public void showLicenses() {
    help.showLicenses();
  }

  /**
   * Reports whether shutdown has begun.
   *
   * @return true after {@link #shutdown}
   */
  public boolean isShuttingDown() {
    return shuttingDown;
  }

  /** Quits: closes every window of the set. A second call does nothing. Call on the UI thread. */
  public void shutdown() {
    set.quit();
  }

  /** Closes this window; the last open window quits. Call on the UI thread. */
  public void closeWindow() {
    set.close(this);
  }

  /** Records that the user activated this window, so handed files go to it. */
  public void windowActivated() {
    set.activated(this);
  }

  /** Brings the window to the front for files handed to it. */
  void toFront() {
    view.toFront();
  }

  /**
   * Stops accepting operations, remembers each tab's position, detaches every tab and hides the
   * window; the caller closes the returned documents on a worker.
   *
   * @return the documents of the tabs
   */
  List<LogDocument> detachAll() {
    shuttingDown = true;
    opener.close();
    quickFind.cancelRunning();
    features.folderSearch().clear();
    for (DocumentController document : documents) {
      rememberPosition(document);
    }
    final List<DocumentController> closing = new ArrayList<DocumentController>(documents);
    documents.clear();
    reopening.clear();
    active = null;
    focused = null;
    for (DocumentController document : closing) {
      document.detach();
    }
    view.hide();
    List<LogDocument> files = new ArrayList<LogDocument>();
    for (DocumentController document : closing) {
      files.add(document.document());
    }
    return files;
  }

  /**
   * Closes documents an open finished but this window never claimed; runs on a worker after {@link
   * #detachAll}.
   */
  @WorkerThread
  void closeUnclaimed() {
    opener.closeUnclaimed();
  }

  /** Disposes the window. */
  void disposeView() {
    view.dispose();
  }

  // Remembered state, sessions and options.

  /** Delay between the last change of the settings or the session and the save. */
  public static final int SAVE_DELAY_MILLIS = 1000;

  /**
   * Restores a session's tabs on a worker, each with its name, follow state and marks, and shows
   * its active tab. This instance then saves the tab list. Mark comments the last save left out
   * show one notice once the tabs are restored, and a save follows, which clears their count.
   *
   * @param session the session
   */
  public void restoreSession(final Session session) {
    set.setSessionOwner(true);
    droppedComments = session.droppedComments();
    // Always on Top.
    alwaysOnTop.restore(session.onTop());
    if (session.tabs().isEmpty()) {
      noticeDroppedComments();
    } else if (!shuttingDown) {
      opener.restore(session, set.services());
    }
  }

  private void noticeDroppedComments() {
    if (droppedComments > 0 && !shuttingDown) {
      view.showNotice(
          UiText.NOTICE_COMMENTS_DROPPED.format(droppedComments, SessionStore.MAX_COMMENTS));
      scheduleSave();
    }
    droppedComments = 0;
  }

  /**
   * Sets whether this instance saves its tab list as the session; {@link #restoreSession} sets it.
   *
   * @param owner true to save the tabs
   */
  public void setSessionOwner(boolean owner) {
    set.setSessionOwner(owner);
  }

  @Override
  public void documentStateChanged() {
    scheduleSave();
  }

  // Keyword triggers.

  /** The shown document shows a notice; a background tab takes {@code FOLLOW_STOPPED}. */
  @Override
  public void followStoppedByTrigger(DocumentController document, String rule, long line) {
    if (document == active) {
      view.showNotice(UiText.NOTICE_TRIGGER_FOLLOW_STOPPED.format(rule, line + 1));
    } else {
      tabData.followStopped(document);
    }
  }

  // Always on Top.

  /**
   * Returns the window's Always on Top state.
   *
   * @return the state
   */
  public AlwaysOnTop alwaysOnTop() {
    return alwaysOnTop;
  }

  /** Saves {@link #SAVE_DELAY_MILLIS} after the last call. */
  private void scheduleSave() {
    if (!shuttingDown) {
      set.scheduleSave();
    }
  }

  /**
   * Captures the settings, the open tabs' positions and the session of every window on the UI
   * thread and writes them on a worker. Quit does the same on its own worker.
   */
  public void flushSettings() {
    set.flushSettings();
  }

  /** A tab joined or left a time sync group; the views show its membership again. */
  void timeSyncChanged() {
    for (ApplicationListener listener : new ArrayList<ApplicationListener>(listeners)) {
      listener.timeSyncChanged();
    }
  }

  /** Remembers each tab's position for the next save. */
  void rememberPositions() {
    for (DocumentController document : documents) {
      rememberPosition(document);
    }
  }

  /** Captures this window's tabs for the session; pasted documents are left out. */
  WindowSet.WindowDraft draft() {
    List<WindowSet.DraftTab> tabs = new ArrayList<WindowSet.DraftTab>();
    int shown = -1;
    for (DocumentController document : documents) {
      Path path = document.document().path();
      if (document.document().inMemory()) {
        continue;
      }
      if (document == active) {
        shown = tabs.size();
      }
      String name = tabNames.get(path);
      tabs.add(
          new WindowSet.DraftTab(
              path.toString(),
              name == null ? "" : name,
              document.following(),
              document.time().offset(),
              set.timeSync().sessionNumber(document),
              document.document()));
    }
    return new WindowSet.WindowDraft(tabs, shown, alwaysOnTop.saved());
  }

  private void rememberPosition(DocumentController document) {
    if (document.document().inMemory()) {
      return;
    }
    TextPosition caret = document.selection().caret();
    config
        .positions()
        .put(
            document.document().path(),
            document.viewport().topLine(),
            caret.line(),
            caret.column());
  }

  private void rememberRecent(LogDocument document) {
    if (document.inMemory()) {
      return;
    }
    Preferences current = preferences.get();
    UiState state =
        current
            .state()
            .addRecent(document.path().toString(), current.options().recentCount())
            .removeClosed(document.path().toString());
    if (!state.equals(current.state())) {
      setPreferences(current.withState(state));
    }
  }

  private void rememberSearchOptions(SearchOptions options) {
    UiState state = preferences.get().state();
    SearchToggles toggles =
        new SearchToggles(
            options.mode() == MatchMode.REGEX,
            options.caseSensitive(),
            options.wholeWord(),
            options.inverse(),
            options.combining(),
            options.autoRefresh(),
            options.keepResults(),
            options.range(),
            options.rangeEnd(),
            options.contextBefore(),
            options.contextAfter());
    if (!toggles.equals(state.search())) {
      setPreferences(preferences.get().withState(state.withSearch(toggles)));
    }
  }

  private static SearchOptions options(SearchToggles toggles) {
    return new SearchOptions(
        toggles.regex() ? MatchMode.REGEX : MatchMode.LITERAL,
        toggles.caseSensitive(),
        toggles.wholeWord(),
        toggles.inverse(),
        toggles.combining(),
        false,
        toggles.autoRefresh(),
        toggles.keepResults(),
        toggles.range(),
        toggles.rangeEnd(),
        toggles.contextBefore(),
        toggles.contextAfter());
  }

  private void rememberLineNumbers(DocumentController document) {
    UiState state = preferences.get().state();
    UiState next = state.withLineNumbers(document.lineNumbers(), document.filtered().lineNumbers());
    if (!next.equals(state)) {
      setPreferences(preferences.get().withState(next));
    }
  }

  private Path rememberedDirectory() {
    String text = preferences.get().state().openDirectory();
    if (text.isEmpty()) {
      return null;
    }
    try {
      return Path.of(text);
    } catch (InvalidPathException e) {
      return null;
    }
  }

  /**
   * Records the window's placement and split for the next start; shutdown calls it.
   *
   * @param window the normal bounds and maximized state
   * @param split the main view's share, clamped to the split range
   */
  public void rememberWindow(WindowState window, int split) {
    int percent = Math.max(Preferences.MIN_SPLIT, Math.min(Preferences.MAX_SPLIT, split));
    UiState state = preferences.get().state();
    UiState next = state.withWindow(window, percent);
    if (!next.equals(state)) {
      set.preferencesChanged(preferences.get().withState(next));
    }
  }

  /** Records this window's placement and split for the next start and the next new window. */
  void rememberWindow() {
    WindowGeometry.Request bounds = view.windowBounds();
    rememberWindow(
        new WindowState(
            bounds.x(), bounds.y(), bounds.width(), bounds.height(), view.windowMaximized()),
        view.splitPercent());
  }

  /**
   * Opens a path from the recent list or the favorites on a worker.
   *
   * @param text the path
   */
  public void openListed(String text) {
    try {
      openFile(Path.of(text), 0);
    } catch (InvalidPathException e) {
      view.showError(
          UiText.OPEN_FAILED_TITLE.text(), UiText.OPEN_INVALID_PATH.format(e.getReason()));
    }
  }

  /** Drops recent files that are not regular files any more; the check runs on a worker. */
  private void cleanRecent() {
    final List<String> listed = preferences.get().state().recentFiles();
    boolean started =
        workers.tryStart(
            Workers.Kind.LIFECYCLE,
            new Runnable() {
              @Override
              public void run() {
                final List<String> missing = new ArrayList<String>();
                for (String text : listed) {
                  boolean present;
                  try {
                    present = Files.isRegularFile(Path.of(text));
                  } catch (InvalidPathException e) {
                    present = false;
                  }
                  if (!present) {
                    missing.add(text);
                  }
                }
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        if (shuttingDown) {
                          return;
                        }
                        UiState state = preferences.get().state();
                        List<String> kept = new ArrayList<String>(state.recentFiles());
                        kept.removeAll(missing);
                        setPreferences(preferences.get().withState(state.withRecentFiles(kept)));
                        view.showNotice(UiText.NOTICE_RECENT_CLEANED.format(missing.size()));
                      }
                    });
              }
            });
    if (!started) {
      LOG.debug("Recent files not checked: workers are closed");
    }
  }

  /**
   * Reports whether a tab's file is a favorite.
   *
   * @param document an open document's controller, or null
   * @return true when its path is in the favorites
   */
  public boolean isFavorite(DocumentController document) {
    return document != null
        && !document.document().inMemory()
        && preferences.get().options().favorites().contains(document.document().path().toString());
  }

  private void addFavorite() {
    if (active == null || active.document().inMemory()) {
      view.showNotice(UiText.NOTICE_NO_FILE.text());
      return;
    }
    Options options = preferences.get().options();
    if (options.favorites().size() >= Options.MAX_FAVORITES) {
      view.showNotice(UiText.NOTICE_FAVORITES_FULL.format(Options.MAX_FAVORITES));
      return;
    }
    setPreferences(
        preferences.get().withOptions(options.addFavorite(active.document().path().toString())));
    view.showNotice(UiText.NOTICE_FAVORITE_ADDED.format(active.state().title()));
  }

  private void removeFavorite() {
    if (active == null || active.document().inMemory()) {
      view.showNotice(UiText.NOTICE_NO_FILE.text());
      return;
    }
    removeFavorite(active.document().path().toString());
    view.showNotice(UiText.NOTICE_FAVORITE_REMOVED.format(active.state().title()));
  }

  /**
   * Removes a path from the favorites.
   *
   * @param text the path
   */
  public void removeFavorite(String text) {
    Options options = preferences.get().options();
    setPreferences(preferences.get().withOptions(options.removeFavorite(text)));
  }

  /**
   * Forces a tab's encoding, or returns it to detection, remembers the choice for the file and
   * reopens it on a worker, which indexes it again.
   *
   * @param document an open document's controller
   * @param charset the charset, or null for detection
   */
  public void setEncoding(DocumentController document, Charset charset) {
    if (document.document().inMemory()) {
      view.showNotice(UiText.NOTICE_NO_FILE.text());
      return;
    }
    config.positions().setEncoding(document.document().path(), charset);
    scheduleSave();
    view.showNotice(
        UiText.NOTICE_ENCODING.format(
            document.state().title(),
            charset == null ? UiText.ENCODING_AUTO_NAME.text() : charset.name()));
    reopen(document, null);
  }

  /**
   * Changes the font size by points from the size drawn now, clamped to {@link
   * FontSettings#MIN_SIZE} and {@link FontSettings#MAX_SIZE}, and saves it as the zoom.
   *
   * @param steps points to add; negative makes it smaller
   */
  public void zoomFont(int steps) {
    FontSettings font = preferences.get().options().font();
    setFont(font.withZoom(font.effectiveSize(view.platformFontSize()) + steps));
  }

  private void setFont(FontSettings font) {
    Options options = preferences.get().options();
    if (font.equals(options.font())) {
      return;
    }
    setPreferences(preferences.get().withOptions(options.withFont(font)));
    view.showNotice(UiText.NOTICE_FONT_SIZE.format(font.effectiveSize(view.platformFontSize())));
    set.fireOptionsChanged(false, true, false);
  }

  /**
   * Replaces the options from the Preferences dialog and applies them: key bindings, font, file
   * monitoring, the default encoding for files opened afterwards and the recent-files count.
   *
   * @param next the options
   */
  public void setOptions(Options next) {
    Preferences current = preferences.get();
    Options old = current.options();
    UiState state = current.state();
    if (state.recentFiles().size() > next.recentCount()) {
      state = state.withRecentFiles(state.recentFiles().subList(0, next.recentCount()));
    }
    setPreferences(current.withOptions(next).withState(state));
    if (!next.shortcuts().equals(old.shortcuts())) {
      set.setKeymap(Keymap.defaults().withOverrides(next.shortcuts()));
    }
    if (!next.monitor().equals(old.monitor())) {
      monitor.setSettings(next.monitor());
    }
    if (!next.defaultEncoding().equals(old.defaultEncoding())) {
      DocumentServices services = set.services();
      set.setServices(
          new DocumentServices(
              services.workers(),
              services.indexer(),
              services.search(),
              services.cache(),
              services.budget(),
              next.defaultCharset()));
    }
    boolean keymap = !next.shortcuts().equals(old.shortcuts());
    boolean font = !next.font().equals(old.font());
    boolean other = !next.withShortcuts(old.shortcuts()).withFont(old.font()).equals(old);
    set.fireOptionsChanged(keymap, font, other);
  }

  /**
   * Tells this window's listeners which options changed.
   *
   * @param keymap the key bindings changed
   * @param font the font changed
   * @param other another option changed
   */
  void fireOptionsChanged(boolean keymap, boolean font, boolean other) {
    for (ApplicationListener listener : new ArrayList<ApplicationListener>(listeners)) {
      if (keymap) {
        listener.keymapChanged();
      }
      if (font) {
        listener.fontChanged();
      }
      if (other) {
        listener.optionsChanged();
      }
    }
  }

  /**
   * Returns the key bindings in effect: the defaults with the user's overrides.
   *
   * @return the keymap
   */
  public Keymap keymap() {
    return set.keymap();
  }

  // Multiple windows, logging and support items.

  /**
   * Shows or hides this window's warning marker.
   *
   * @param shown true when a warning arrived after Show Log was last opened
   */
  void showWarningMarker(boolean shown) {
    if (!shuttingDown) {
      view.showWarningMarker(shown);
    }
  }

  /**
   * Reports whether the status bar shows the warning marker.
   *
   * @return true when a warning arrived after Show Log was last opened
   */
  public boolean newWarnings() {
    return set.newWarnings();
  }

  /**
   * Reports whether log lines go to the debug log file.
   *
   * @return true while the file is open
   */
  public boolean debugLogFile() {
    return set.logFiles().attached();
  }

  /**
   * Opens or closes the debug log file on a worker and saves the choice; the check mark follows the
   * file, so it stays clear when the file does not open.
   *
   * @param on true to open it
   */
  public void setDebugLogFile(boolean on) {
    help.setDebugLogFile(on);
  }

  /**
   * Returns a dialog's remembered size.
   *
   * @param name the dialog's name
   * @return the size, or null
   */
  public UiState.Size dialogSize(String name) {
    return preferences.get().state().dialogs().get(name);
  }

  /**
   * Remembers a dialog's size for the next time it opens.
   *
   * @param name the dialog's name, {@code [A-Za-z0-9]+}
   * @param width pixels
   * @param height pixels
   */
  public void rememberDialogSize(String name, int width, int height) {
    if (width < 1 || height < 1) {
      return;
    }
    UiState.Size size = new UiState.Size(width, height);
    UiState state = preferences.get().state();
    if (!size.equals(state.dialogs().get(name))) {
      setPreferences(preferences.get().withState(state.withDialog(name, size)));
    }
  }

  // User guide.

  /**
   * Opens the user guide's copy in the application the system opens HTML with, else names the file
   * in a notice. The view calls it on the UI thread when its guide window fails after {@link
   * MainView#showGuide} returned true.
   *
   * @param guide the guide's copy
   */
  public void showGuideElsewhere(Path guide) {
    help.showGuideElsewhere(guide);
  }

  // Handed files already open.

  /**
   * Opens files another launch handed over; a file a window shows already is shown in its tab, and
   * that window comes to the front.
   *
   * @param files the files
   * @param follow true to follow each one
   */
  void openHanded(List<FileRequest> files, boolean follow) {
    open(files, follow, true);
  }

  /**
   * Reports whether a tab shows a file.
   *
   * @param path the opened document's path
   * @return true when a tab of this window shows it
   */
  boolean shows(Path path) {
    return find(path) != null;
  }

  /** Shows the tab of a handed file at its line, following when asked, and raises the window. */
  private void showHanded(Path path, long line, boolean follow) {
    DocumentController shown = find(path);
    if (shuttingDown || shown == null) {
      return;
    }
    present(shown, line, follow);
    view.toFront();
  }

  // Scratchpad transforms.

  /** Inputs longer than this many chars transform on a CPU worker. */
  public static final int INLINE_TRANSFORM_CHARS = 1 << 16;

  /**
   * Applies a scratchpad transform and reports the outcome on the UI thread: at once for an input
   * of at most {@link #INLINE_TRANSFORM_CHARS} chars, else from a CPU worker through the
   * dispatcher. Every call reports exactly once.
   *
   * @param kind the transform
   * @param input the input
   * @param listener receives the output or the notice naming the failure
   */
  public void transform(
      final Transforms.Kind kind, final String input, final TransformListener listener) {
    if (input.length() <= INLINE_TRANSFORM_CHARS) {
      transformed(transformNow(kind, input), listener);
      return;
    }
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              final String[] outcome = transformNow(kind, input);
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      transformed(outcome, listener);
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      listener.failed(UiText.SCRATCHPAD_FAILED.format(String.valueOf(e.getMessage())));
    }
  }

  /** Returns the output and a null notice, or a null output and the notice. */
  private static String[] transformNow(Transforms.Kind kind, String input) {
    try {
      return new String[] {Transforms.apply(kind, input), null};
    } catch (Transforms.TooLongException e) {
      return new String[] {null, e.getMessage()};
    } catch (IllegalArgumentException e) {
      return new String[] {null, UiText.SCRATCHPAD_FAILED.format(String.valueOf(e.getMessage()))};
    }
  }

  private static void transformed(String[] outcome, TransformListener listener) {
    if (outcome[0] != null) {
      listener.transformed(outcome[0]);
    } else {
      listener.failed(outcome[1]);
    }
  }

  // Encoding menu lists.

  /**
   * Gives the Encoding menu's lists to a listener on the UI thread: at once when computed, else
   * from a {@code qlogg-lifecycle-} worker that computes them. Nothing arrives after shutdown
   * starts.
   *
   * @param listener receives the choices
   */
  public void loadEncodingChoices(final EncodingChoices.Listener listener) {
    EncodingChoices done = EncodingChoices.loaded();
    if (done != null) {
      listener.loaded(done);
      return;
    }
    boolean started =
        workers.tryStart(
            Workers.Kind.LIFECYCLE,
            new Runnable() {
              @Override
              public void run() {
                final EncodingChoices choices = EncodingChoices.available();
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        if (!shuttingDown) {
                          listener.loaded(choices);
                        }
                      }
                    });
              }
            });
    if (!started) {
      LOG.debug("Encoding list not loaded: workers are closed");
    }
  }
}
