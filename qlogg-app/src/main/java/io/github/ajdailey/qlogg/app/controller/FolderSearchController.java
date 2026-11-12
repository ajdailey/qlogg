package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.config.FolderSearchSettings;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.FolderRequest;
import io.github.ajdailey.qlogg.app.view.FolderSearchState;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
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
 * Edit > Search in Folder: runs a {@link FolderSearch} over a folder's files without opening them
 * as tabs, and opens a listed line in a tab. Confined to the UI thread; the search's events reach
 * it through the {@link UiDispatcher}.
 *
 * <p>One search runs at a time: a new run cancels the last and closes its {@link FolderResults},
 * which returns their {@code RESULTS} reservation. The toggles that apply are mode, case, whole
 * word, inverse and combining; range, in selection, context and auto-refresh do not. Lines are
 * matched with ANSI colors removed when {@code view.hideAnsi} is on.
 */
public final class FolderSearchController {
  /** Receives state changes. */
  public interface Listener {
    /** The state or the results changed. Called on the UI thread. */
    void changed();
  }

  private final ApplicationController application;
  private final UiDispatcher ui;
  private final List<Listener> listeners = new ArrayList<Listener>();
  private FolderRequest request;
  private FolderSearchState state = FolderSearchState.IDLE;
  private FolderResults results;
  private OperationHandle running;
  private int generation;

  FolderSearchController(ApplicationController application, UiDispatcher ui) {
    this.application = application;
    this.ui = ui;
  }

  /**
   * Returns what the window shows on open: the last run's request; else the remembered folder,
   * names and subfolders ({@code folderSearch.*}) with the shown tab's search text and toggles, the
   * folder falling back to the shown tab's folder when none is remembered.
   *
   * @return the request
   */
  public FolderRequest request() {
    if (request != null) {
      return request;
    }
    FolderSearchSettings saved = application.preferences().state().folderSearch();
    DocumentController document = application.activeDocument();
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
        document == null ? SearchOptions.DEFAULTS : document.search().options());
  }

  /** Remembers a request's folder, names and subfolders in {@code qlogg.conf}. */
  private void remember(FolderRequest next) {
    if (next.folder().length() > FolderSearchSettings.MAX_CHARS
        || next.names().length() > FolderSearchSettings.MAX_CHARS) {
      return;
    }
    FolderSearchSettings value =
        new FolderSearchSettings(next.folder(), next.names(), next.recursive());
    Preferences current = application.preferences();
    if (!value.equals(current.state().folderSearch())) {
      application.setPreferences(current.withState(current.state().withFolderSearch(value)));
    }
  }

  /**
   * Cancels the running search, drops its results and starts a new one. An empty folder or text, a
   * name pattern or combined expression that does not parse, or closed workers end it as {@code
   * FAILED} with a message; the folder is validated on the search thread.
   *
   * @param next what to search
   */
  public void run(FolderRequest next) {
    clear();
    request = next;
    remember(next);
    String failure = check(next);
    if (failure != null) {
      publish(new FolderSearchState(FolderSearchState.Phase.FAILED, FolderProgress.START, failure));
      return;
    }
    SearchOptions toggles = next.options();
    SearchExpression expression;
    try {
      expression = ExpressionParser.parse(next.text(), toggles.mode(), toggles.combining());
    } catch (ExpressionException e) {
      publish(
          new FolderSearchState(
              FolderSearchState.Phase.FAILED,
              FolderProgress.START,
              UiText.SEARCH_EXPRESSION_ERROR.format(e.column(), SearchController.reason(e))));
      return;
    }
    SearchQuery query =
        new SearchQuery(
            expression,
            toggles.caseSensitive(),
            toggles.wholeWord(),
            toggles.inverse(),
            null,
            application.preferences().view().hideAnsi());
    DocumentServices services = application.windowSet().services();
    FolderResults started = new FolderResults(services.budget());
    int run = ++generation;
    try {
      running =
          new FolderSearch(services)
              .start(
                  new FolderQuery(
                      next.folder().strip(), NamePattern.of(next.names()), next.recursive(), query),
                  started,
                  new Events(run));
    } catch (RejectedExecutionException e) {
      publish(
          new FolderSearchState(
              FolderSearchState.Phase.FAILED,
              FolderProgress.START,
              UiText.SEARCH_FAILED.format(UiText.SEARCH_SHUT_DOWN.text())));
      return;
    }
    results = started;
    publish(new FolderSearchState(FolderSearchState.Phase.RUNNING, FolderProgress.START, null));
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
    } catch (IllegalArgumentException e) {
      return UiText.FOLDER_BAD_NAMES.format(LogText.oneLine(e.getMessage()));
    }
    return null;
  }

  /** Cancels the running search; the files listed so far stay. */
  public void cancel() {
    if (running != null) {
      running.cancel();
    }
  }

  /** Cancels the running search and drops the results, returning their reservation. */
  public void clear() {
    cancel();
    running = null;
    generation++;
    if (results != null) {
      results.close();
      results = null;
    }
    if (state.phase() == FolderSearchState.Phase.RUNNING) {
      state = new FolderSearchState(FolderSearchState.Phase.CANCELED, state.progress(), null);
    }
  }

  /**
   * Returns the state.
   *
   * @return the last published state
   */
  public FolderSearchState state() {
    return state;
  }

  /**
   * Returns the listed files of the current run; the search adds to them while it runs.
   *
   * @return the files, empty before a run
   */
  public List<FileHits> files() {
    return results == null ? List.<FileHits>of() : results.files();
  }

  /**
   * Counts the matching lines of the current run.
   *
   * @return lines
   */
  public long matches() {
    return results == null ? 0 : results.matches();
  }

  /**
   * Reports whether the results share stopped the current run.
   *
   * @return true once it did
   */
  public boolean limited() {
    return results != null && results.limited();
  }

  /**
   * Shows a listed line: selects the tab that shows the file, by the path searched or its real
   * path, or opens the file in a new tab, and goes to the line.
   *
   * @param file a listed file
   * @param hit the listed line, from 0 to {@link FileHits#size} - 1
   */
  public void open(FileHits file, int hit) {
    long line = file.line(hit) + 1;
    Path path = file.path();
    for (DocumentController document : application.documents()) {
      if (path.equals(document.document().path()) || path.equals(document.document().realPath())) {
        application.activate(document);
        document.goToLine(line);
        return;
      }
    }
    application.openFile(path, line);
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

  private void publish(FolderSearchState next) {
    state = next;
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

  /** One run's events, posted to the UI thread; a later run's events replace them. */
  private final class Events implements FolderListener {
    private final int run;

    Events(int run) {
      this.run = run;
    }

    @Override
    public void progressed(final FolderProgress progress) {
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              if (run == generation && state.phase() == FolderSearchState.Phase.RUNNING) {
                publish(new FolderSearchState(FolderSearchState.Phase.RUNNING, progress, null));
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
              if (run != generation) {
                return;
              }
              running = null;
              FolderProgress progress = state.progress();
              publish(
                  switch (outcome) {
                    case COMPLETED ->
                        new FolderSearchState(FolderSearchState.Phase.DONE, progress, null);
                    case CANCELED ->
                        new FolderSearchState(FolderSearchState.Phase.CANCELED, progress, null);
                    case FAILED ->
                        new FolderSearchState(
                            FolderSearchState.Phase.FAILED, progress, failureText(failure));
                  });
            }
          });
    }
  }
}
