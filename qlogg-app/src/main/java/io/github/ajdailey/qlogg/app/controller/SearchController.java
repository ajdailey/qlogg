package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.search.SearchEdits;
import io.github.ajdailey.qlogg.app.view.ActivityIndicator;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewListener;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.app.view.UiUpdateQueue;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.Visibility;
import io.github.ajdailey.qlogg.core.search.ExpressionException;
import io.github.ajdailey.qlogg.core.search.ExpressionParser;
import io.github.ajdailey.qlogg.core.search.LineRange;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.search.PatternTooComplexException;
import io.github.ajdailey.qlogg.core.search.RangeFinder;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchExpression;
import io.github.ajdailey.qlogg.core.search.SearchLimit;
import io.github.ajdailey.qlogg.core.search.SearchListener;
import io.github.ajdailey.qlogg.core.search.SearchQuery;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One document's search: the search text and toggles, the running search, the result sets it keeps
 * and the walk over the shown set's matches. Confined to the UI thread and never blocks it.
 *
 * <p>Each {@link #run} starts a new generation with a new result set charged to the {@code RESULTS}
 * share. Progress arrives on the search thread, which keeps the newest report in an {@link
 * AtomicReference} and posts a flush through a {@link UiUpdateQueue}; a flush drops a report from
 * an older generation, so a canceled search cannot overwrite a newer one. Each search holds the
 * {@link ActivityIndicator} active until its {@code finished} call, or until {@link #detach}.
 *
 * <p>With {@code keepResults} on, a run moves the finished current set into the kept sets instead
 * of emptying it. A kept set stays charged to {@code RESULTS} until {@link #removeKept} or {@link
 * #detach}. A search still running is replaced as usual, because its set is still growing.
 *
 * <p>{@link #findNext} and {@link #findPrevious} query the shown set on the UI thread; the set's
 * read lock waits at most for one chunk merge, which holds no I/O.
 *
 * <p>Each search holds a {@link io.github.ajdailey.qlogg.core.model.LogDocument#enter} entry until
 * its {@code finished} call, so closing the document waits for it. With {@code autoRefresh} on,
 * {@link #appended} searches the lines a finished search did not cover, from its last line, which
 * may have grown, into the same set and generation. The refresh shows no progress and holds no
 * activity; the state stays {@code DONE} with the growing match count. A search that stopped at a
 * limit, or whose range ends before the end of the file, is not refreshed.
 *
 * <p>With {@code range} on, the field's text starts a range and {@code rangeEnd} ends it ({@link
 * SearchQuery#rangeEnd}). A refresh of a range search starts in the state the engine reported
 * before the last searched line, so a range still open at the end of the file extends over appended
 * lines. {@link #selectRange} selects the range around the main view's caret on a worker ({@link
 * RangeFinder}).
 */
public final class SearchController {
  /** Least interval between search state flushes. */
  public static final int FLUSH_INTERVAL_MILLIS = 50;

  private static final Logger LOG = LoggerFactory.getLogger(SearchController.class);

  /** What an edit with selected text does. */
  public enum Edit {
    /** Add the text as an alternative. */
    ADD,
    /** Exclude lines holding the text. */
    EXCLUDE,
    /** Replace the search with the text. */
    REPLACE
  }

  /** A finished search kept as a pinned result set. */
  public static final class KeptResults {
    private final String text;
    private final SearchOptions options;
    private final MutableLineSet lines;
    private final SearchLimit limit;

    KeptResults(String text, SearchOptions options, MutableLineSet lines, SearchLimit limit) {
      this.text = text;
      this.options = options;
      this.lines = lines;
      this.limit = limit;
    }

    /**
     * Returns the search text.
     *
     * @return the text
     */
    public String text() {
      return text;
    }

    /**
     * Returns the toggles the search ran with.
     *
     * @return the toggles
     */
    public SearchOptions options() {
      return options;
    }

    /**
     * Returns the matching lines, charged to {@code RESULTS}.
     *
     * @return the set
     */
    public LineSet lines() {
      return lines;
    }

    /**
     * Returns the size of {@link #lines}.
     *
     * @return the number of matching lines
     */
    public long lineCount() {
      return lines.size();
    }

    /**
     * Returns what stopped the search early.
     *
     * @return the limit, or {@code NONE}
     */
    public SearchLimit limit() {
      return limit;
    }

    /** Empties the set and returns its reservation. */
    void clear() {
      lines.clear();
    }

    /** Returns the heap the set retains, in bytes. */
    long retainedBytes() {
      return lines.retainedBytes();
    }
  }

  private final DocumentController document;
  private final SearchEngine engine;
  private final ActivityIndicator activity;
  private final MemoryBudget budget;
  private final DocumentHost host;
  private final UiUpdateQueue updates;
  private final AtomicReference<Report> latest = new AtomicReference<Report>();
  private final List<SearchViewListener> listeners = new ArrayList<SearchViewListener>();
  private final List<KeptResults> kept = new ArrayList<KeptResults>();
  private MutableLineSet results;
  private SearchViewState state = SearchViewState.IDLE;
  private SearchOptions options;
  private String fieldText = "";
  private Running running;
  private long generation;
  private int shown = -1;
  private long limitStart = -1;
  private long limitEnd = -1;
  private boolean closed;
  private SearchQuery lastQuery;
  private long searchedEnd;
  private long searchedBytes;
  private boolean searchedRangeOpen;
  private CancellationToken findingRange;

  /**
   * One search's progress or end, as the search thread reports it. {@code end} and {@code bytes}
   * are the range end and the indexed bytes of the snapshot searched; {@code refresh} marks a
   * search over appended lines.
   */
  private record Report(
      long generation,
      long searched,
      long total,
      long matches,
      boolean finished,
      OperationOutcome outcome,
      SearchLimit limit,
      long skipped,
      Exception failure,
      long end,
      long bytes,
      boolean refresh,
      boolean rangeOpen) {}

  /**
   * The search in progress: its activity, which ends once, the flag that makes its own {@code
   * finished} call empty its result set once a newer generation replaced it, and whether it
   * searches appended lines into a finished set.
   */
  private record Running(
      OperationHandle handle, AtomicBoolean active, AtomicBoolean discarded, boolean refresh) {}

  /**
   * Creates the controller.
   *
   * @param document the document's controller: selection, caret moves and the document
   * @param engine runs searches
   * @param ui the UI-thread dispatcher
   * @param activity shows a running search
   * @param budget result sets reserve from its {@code RESULTS} share
   * @param host the history and the toggles to start with
   */
  SearchController(
      DocumentController document,
      SearchEngine engine,
      UiDispatcher ui,
      ActivityIndicator activity,
      MemoryBudget budget,
      DocumentHost host) {
    this.document = document;
    this.engine = engine;
    this.activity = activity;
    this.budget = budget;
    this.host = host;
    this.options = host.initialOptions();
    this.results = new MutableLineSet(budget);
    this.updates =
        new UiUpdateQueue(
            ui,
            FLUSH_INTERVAL_MILLIS,
            new Runnable() {
              @Override
              public void run() {
                flush();
              }
            });
  }

  /**
   * Returns the state the search bar shows.
   *
   * @return the latest state
   */
  public SearchViewState state() {
    return state;
  }

  /**
   * Returns the current search's results, in line order; they grow while it runs.
   *
   * @return the result set
   */
  public LineSet results() {
    return results;
  }

  /**
   * Returns the size of {@link #results}.
   *
   * @return the number of matching lines so far
   */
  public long resultCount() {
    return results.size();
  }

  /**
   * Returns the result set the filtered view shows: the current search's, or a kept set.
   *
   * @return the set
   */
  public LineSet shownResults() {
    return shown < 0 ? results : kept.get(shown).lines();
  }

  /**
   * Returns the toggles of the shown set's search: the current search's as it ran, or a kept set's.
   *
   * @return the toggles; the context counts apply to the filtered view
   */
  public SearchOptions shownOptions() {
    return shown < 0 ? state.options() : kept.get(shown).options();
  }

  /**
   * Returns which set shows.
   *
   * @return -1 for the current search, else an index into {@link #kept}
   */
  public int shown() {
    return shown;
  }

  /**
   * Shows the current search's results or a kept set.
   *
   * @param index -1 for the current search, else an index into {@link #kept}
   */
  public void show(int index) {
    int next = index < -1 || index >= kept.size() ? -1 : index;
    if (next != shown) {
      shown = next;
      fireResults();
    }
  }

  /**
   * Shows a kept set, matched by identity; a set not in {@link #kept} is ignored.
   *
   * @param set a set of {@link #kept}
   */
  public void show(KeptResults set) {
    int index = kept.indexOf(set);
    if (index >= 0) {
      show(index);
    }
  }

  /**
   * Returns the kept result sets, oldest first.
   *
   * @return an unmodifiable view
   */
  public List<KeptResults> kept() {
    return Collections.unmodifiableList(kept);
  }

  /**
   * Removes a kept set and returns its reservation.
   *
   * @param index an index into {@link #kept}
   */
  public void removeKept(int index) {
    if (index < 0 || index >= kept.size()) {
      return;
    }
    kept.remove(index).clear();
    if (shown == index) {
      shown = -1;
    } else if (shown > index) {
      shown--;
    }
    fireResults();
  }

  /**
   * Removes a kept set, matched by identity, and returns its reservation; a set not in {@link
   * #kept} is ignored.
   *
   * @param set a set of {@link #kept}
   */
  public void removeKept(KeptResults set) {
    int index = kept.indexOf(set);
    if (index >= 0) {
      removeKept(index);
    }
  }

  /**
   * Returns the toggles.
   *
   * @return the toggles the next search runs with
   */
  public SearchOptions options() {
    return options;
  }

  /**
   * Replaces the toggles, as the search bar's check boxes set them.
   *
   * @param next the toggles
   */
  public void setOptions(SearchOptions next) {
    options = next;
  }

  /**
   * Returns the search field's text.
   *
   * @return the text as last reported by the field or set by an edit
   */
  public String fieldText() {
    return fieldText;
  }

  /**
   * Records the search field's text as the user types.
   *
   * @param text the field's text
   */
  public void setFieldText(String text) {
    fieldText = text;
  }

  /**
   * Toggles one option for keys {@code 4} to {@code 9} and tells the views.
   *
   * @param command one of the {@code TOGGLE_} search commands
   */
  public void toggle(CommandId command) {
    SearchOptions next =
        switch (command) {
          case TOGGLE_CASE -> options.withCaseSensitive(!options.caseSensitive());
          case TOGGLE_REGEX ->
              options.withMode(
                  options.mode() == MatchMode.REGEX ? MatchMode.LITERAL : MatchMode.REGEX);
          case TOGGLE_INVERSE -> options.withInverse(!options.inverse());
          case TOGGLE_COMBINING -> options.withCombining(!options.combining());
          case TOGGLE_AUTO_REFRESH -> options.withAutoRefresh(!options.autoRefresh());
          case TOGGLE_KEEP_RESULTS -> options.withKeepResults(!options.keepResults());
          case TOGGLE_RANGE -> options.withRange(!options.range());
          default -> options;
        };
    options = next;
    fireOptions();
  }

  /**
   * Returns the search generation; each {@link #run} and {@link #clear} starts a new one.
   *
   * @return the generation
   */
  public long generation() {
    return generation;
  }

  /**
   * Adds a listener.
   *
   * @param listener called on the UI thread
   */
  public void addListener(SearchViewListener listener) {
    listeners.add(listener);
  }

  /**
   * Removes a listener.
   *
   * @param listener a listener added earlier
   */
  public void removeListener(SearchViewListener listener) {
    listeners.remove(listener);
  }

  /** Runs the field's text with the current toggles. */
  public void run() {
    run(fieldText, options);
  }

  /**
   * Cancels any running search and starts a new one over the indexed lines, within the search
   * limits and, with {@code inSelection}, the selected lines. Empty text clears the results. A
   * combined expression that does not parse, or a regex that does not compile, ends as {@code
   * FAILED} with a message. A search that starts switches the filtered view to marks and matches.
   * Every run unwraps the filtered view's line wrapped in place.
   *
   * @param text the search text
   * @param toggles the toggles
   */
  public void run(String text, SearchOptions toggles) {
    if (closed) {
      return;
    }
    fieldText = text;
    options = toggles;
    replaceResults(toggles.keepResults());
    document.filtered().unwrapLine();
    if (text.isEmpty()) {
      publish(
          new SearchViewState(
              text, toggles, SearchViewState.Phase.IDLE, 0, 0, SearchLimit.NONE, 0, null));
      return;
    }
    host.searchRan(text, toggles);
    SearchExpression expression;
    try {
      expression = ExpressionParser.parse(text, toggles.mode(), toggles.combining());
    } catch (ExpressionException e) {
      publish(
          failed(text, toggles, 0, UiText.SEARCH_EXPRESSION_ERROR.format(e.column(), reason(e))));
      return;
    }
    SearchExpression end = null;
    if (toggles.range()) {
      try {
        end = rangeEnd(toggles);
      } catch (ExpressionException e) {
        publish(failed(text, toggles, 0, rangeEndError(e)));
        return;
      }
    }
    SearchQuery query =
        new SearchQuery(
            expression,
            toggles.caseSensitive(),
            toggles.wholeWord(),
            toggles.inverse(),
            scope(toggles.inSelection()),
            document.document().hideAnsi(),
            end,
            false);
    document.filtered().setVisibility(Visibility.MARKS_AND_MATCHES);
    lastQuery = query.scope() == null || query.scope().end() == Long.MAX_VALUE ? query : null;
    AtomicBoolean active = new AtomicBoolean(true);
    activity.setActive();
    if (!start(query, active, false)) {
      end(active);
      publish(
          failed(text, toggles, 0, UiText.SEARCH_FAILED.format(UiText.SEARCH_SHUT_DOWN.text())));
      return;
    }
    publish(
        new SearchViewState(
            text, toggles, SearchViewState.Phase.RUNNING, 0, 0, SearchLimit.NONE, 0, null));
  }

  /**
   * Starts the engine over the document's current snapshot inside a document entry.
   *
   * @return false when the document is closing or the workers are closed
   */
  private boolean start(SearchQuery query, AtomicBoolean active, boolean refresh) {
    if (!document.document().enter()) {
      return false;
    }
    LineIndex index = document.document().index();
    long first = query.scope() == null ? 0 : Math.min(query.scope().first(), index.lineCount());
    AtomicBoolean discarded = new AtomicBoolean();
    Events events =
        new Events(
            generation,
            results,
            active,
            discarded,
            first,
            index.indexedBytes(),
            refresh ? results.size() : 0,
            refresh ? state.skippedLines() : 0,
            refresh);
    try {
      OperationHandle handle = engine.search(document.document(), index, query, results, events);
      running = new Running(handle, active, discarded, refresh);
      return true;
    } catch (RejectedExecutionException e) {
      document.document().exit();
      return false;
    }
  }

  /**
   * With {@code autoRefresh} on, searches the lines the file gained since the finished search, from
   * its last line, into the same results. Does nothing while a search runs, after a limit stopped
   * the last one, or when its range ends before the end of the file.
   */
  void appended() {
    if (closed
        || running != null
        || lastQuery == null
        || !options.autoRefresh()
        || state.phase() != SearchViewState.Phase.DONE
        || state.limit() != SearchLimit.NONE) {
      return;
    }
    LineIndex index = document.document().index();
    if (index.indexedBytes() <= searchedBytes || index.lineCount() == 0) {
      return;
    }
    long first = lastQuery.scope() == null ? 0 : lastQuery.scope().first();
    long from = Math.max(first, searchedEnd - 1);
    if (from >= index.lineCount()) {
      return;
    }
    results.removeFrom(from);
    SearchQuery query =
        new SearchQuery(
            lastQuery.expression(),
            lastQuery.caseSensitive(),
            lastQuery.wholeWord(),
            lastQuery.inverse(),
            new LineRange(from, Long.MAX_VALUE),
            lastQuery.hideAnsi(),
            lastQuery.rangeEnd(),
            from == searchedEnd - 1 && searchedRangeOpen);
    start(query, new AtomicBoolean(false), true);
  }

  /**
   * Edits the search text with selected text and, when the host says so, runs the search.
   *
   * @param edit what to do
   * @param selection the selected text
   */
  public void edit(Edit edit, String selection) {
    if (closed || selection.isEmpty()) {
      return;
    }
    SearchEdits.Edit result =
        switch (edit) {
          case ADD -> SearchEdits.add(fieldText, selection, options.mode(), options.combining());
          case EXCLUDE ->
              SearchEdits.exclude(fieldText, selection, options.mode(), options.combining());
          case REPLACE -> SearchEdits.replace(selection, options.mode(), options.combining());
        };
    fieldText = result.text();
    options = options.withCombining(result.combining());
    fireOptions();
    if (host.runSearchOnAdd()) {
      run();
    }
  }

  /**
   * Replaces the search text, as a predefined filter or the history does, and tells the views.
   *
   * @param text the text
   * @param toggles the toggles
   */
  public void setText(String text, SearchOptions toggles) {
    fieldText = text;
    options = toggles;
    fireOptions();
  }

  /**
   * Starts later searches at a line.
   *
   * @param line a 0-based line
   */
  public void setSearchStart(long line) {
    limitStart = line;
    document.notifications().showNotice(UiText.NOTICE_SEARCH_START.format(line + 1));
  }

  /**
   * Ends later searches at a line, inclusive.
   *
   * @param line a 0-based line
   */
  public void setSearchEnd(long line) {
    limitEnd = line;
    document.notifications().showNotice(UiText.NOTICE_SEARCH_END.format(line + 1));
  }

  /** Searches every line again. */
  public void clearSearchLimits() {
    limitStart = -1;
    limitEnd = -1;
    document.notifications().showNotice(UiText.NOTICE_SEARCH_LIMITS_CLEARED.text());
  }

  /**
   * Returns the lines later searches cover.
   *
   * @param inSelection true to narrow to the selected lines
   * @return the range, or null for every line
   */
  LineRange scope(boolean inSelection) {
    long first = Math.max(0, limitStart);
    long end = limitEnd < 0 ? Long.MAX_VALUE : limitEnd + 1;
    Selection selection = document.selection();
    if (inSelection && !selection.isEmpty()) {
      first = Math.max(first, document.sourceLine(selection.start().line()));
      end = Math.min(end, document.endOfRow(selection.end().line()));
    }
    if (first == 0 && end == Long.MAX_VALUE) {
      return null;
    }
    return new LineRange(first, Math.max(first, end));
  }

  /**
   * Reports whether a refresh searches appended lines into the finished set.
   *
   * @return true from {@link #appended} until its end is applied
   */
  boolean refreshing() {
    return running != null && running.refresh();
  }

  /** Cancels the running search and a range selection; the search's results so far stay. */
  public void cancel() {
    if (running != null) {
      running.handle().cancel();
    }
    if (findingRange != null) {
      findingRange.cancel();
    }
  }

  // Range filter.

  /**
   * Parses the end expression of a range search.
   *
   * @throws ExpressionException for an empty or malformed end expression
   */
  private static SearchExpression rangeEnd(SearchOptions toggles) throws ExpressionException {
    if (toggles.rangeEnd().isEmpty()) {
      throw new ExpressionException(ExpressionException.Reason.EXPECTED_TERM, 1);
    }
    return ExpressionParser.parse(toggles.rangeEnd(), toggles.mode(), toggles.combining());
  }

  private static String rangeEndError(ExpressionException e) {
    return UiText.SEARCH_RANGE_END_ERROR.format(e.column(), reason(e));
  }

  /**
   * Selects the range around the main view's caret: the nearest line at or above it matching the
   * search text through the first later line matching the range end, with the current toggles. The
   * lines are matched on a {@code qlogg-search-} worker inside a document entry; a newer request or
   * {@link #cancel} stops an older one. With no range there, a notice shows and the selection
   * stays.
   */
  public void selectRange() {
    if (closed) {
      return;
    }
    final SearchOptions toggles = options;
    SearchExpression start;
    SearchExpression end;
    try {
      if (fieldText.isEmpty()) {
        throw new ExpressionException(ExpressionException.Reason.EXPECTED_TERM, 1);
      }
      start = ExpressionParser.parse(fieldText, toggles.mode(), toggles.combining());
    } catch (ExpressionException e) {
      document
          .notifications()
          .showNotice(UiText.SEARCH_EXPRESSION_ERROR.format(e.column(), reason(e)));
      return;
    }
    try {
      end = rangeEnd(toggles);
    } catch (ExpressionException e) {
      document.notifications().showNotice(rangeEndError(e));
      return;
    }
    final SearchQuery query =
        new SearchQuery(
            start,
            toggles.caseSensitive(),
            toggles.wholeWord(),
            false,
            null,
            document.document().hideAnsi(),
            end,
            false);
    final long line = document.sourceLine(document.selection().caret().line());
    if (line < 0) {
      return;
    }
    if (findingRange != null) {
      findingRange.cancel();
    }
    final CancellationToken token = new CancellationToken();
    findingRange = token;
    activity.setActive();
    boolean started =
        document
            .workers()
            .tryStart(
                Workers.Kind.SEARCH,
                new Runnable() {
                  @Override
                  public void run() {
                    findRange(query, line, token);
                  }
                });
    if (!started) {
      findingRange = null;
      activity.setIdle();
    }
  }

  /** Runs on a worker; posts back on every outcome, which ends the activity. */
  @WorkerThread
  private void findRange(SearchQuery query, long line, final CancellationToken token) {
    RangeFinder.Range found = null;
    String failure = null;
    boolean admitted = document.document().enter();
    try {
      if (admitted) {
        found =
            RangeFinder.around(
                document.document(),
                document.document().index(),
                query,
                engine.matchers(),
                line,
                token);
      }
    } catch (CancellationException e) {
      token.cancel();
    } catch (UncheckedIOException e) {
      failure = UiText.SEARCH_FAILED.format(LogText.oneLine(e.getCause().getMessage()));
    } catch (IOException e) {
      failure = UiText.SEARCH_FAILED.format(LogText.oneLine(e.getMessage()));
    } catch (PatternSyntaxException | PatternTooComplexException e) {
      failure = failureText(e);
    } finally {
      if (admitted) {
        document.document().exit();
      }
      final RangeFinder.Range range = found;
      final String message = failure;
      document
          .ui()
          .asyncExec(
              new Runnable() {
                @Override
                public void run() {
                  rangeFound(range, message, token);
                }
              });
    }
  }

  private void rangeFound(RangeFinder.Range range, String failure, CancellationToken token) {
    activity.setIdle();
    if (findingRange == token) {
      findingRange = null;
    }
    if (closed || token.isCanceled()) {
      return;
    }
    if (failure != null) {
      document.notifications().showNotice(failure);
    } else if (range == null) {
      document.notifications().showNotice(UiText.NOTICE_NO_RANGE.text());
    } else {
      document.selectLines(range.first(), range.last());
    }
  }

  /** Cancels the running search and marks its result set for emptying when it ends. */
  private void discard() {
    if (running != null) {
      running.discarded().set(true);
      running.handle().cancel();
    }
  }

  /** Cancels any running search and empties the results; kept sets stay. */
  public void clear() {
    if (closed) {
      return;
    }
    replaceResults(false);
    publish(SearchViewState.IDLE);
  }

  /**
   * Moves the caret to the first match of the shown set after the caret's line; with none, a notice
   * shows and the caret stays.
   */
  public void findNext() {
    long caret = document.sourceLine(document.selection().caret().line());
    long next = shownResults().ceiling(caret + 1);
    if (next >= 0) {
      document.jumpToLine(next + 1);
    } else {
      document.notifications().showNotice(UiText.NOTICE_END_OF_FILE.text());
    }
  }

  /**
   * Moves the caret to the last match of the shown set before the caret's line; with none, a notice
   * shows and the caret stays.
   */
  public void findPrevious() {
    long caret = document.sourceLine(document.selection().caret().line());
    long previous = caret == 0 ? -1 : shownResults().floor(caret - 1);
    if (previous >= 0) {
      document.jumpToLine(previous + 1);
    } else {
      document.notifications().showNotice(UiText.NOTICE_START_OF_FILE.text());
    }
  }

  /** Cancels the search, ends its activity and returns every set's reservation; no later flush. */
  void detach() {
    closed = true;
    updates.close();
    discard();
    if (findingRange != null) {
      findingRange.cancel();
    }
    if (running != null) {
      end(running.active());
      running = null;
    }
    results.clear();
    for (KeptResults set : kept) {
      set.clear();
    }
    kept.clear();
    listeners.clear();
  }

  /**
   * Starts a new generation with an empty result set. The finished current set is kept when asked
   * and it has a search behind it; a refresh running into it is canceled and the set keeps the
   * lines it reached. Otherwise any running search is canceled and its set emptied.
   */
  private void replaceResults(boolean keep) {
    boolean finished =
        state.phase() == SearchViewState.Phase.DONE
            || state.phase() == SearchViewState.Phase.CANCELED;
    boolean idle = running == null || running.refresh();
    if (keep && finished && idle && !state.text().isEmpty()) {
      cancel();
      kept.add(new KeptResults(state.text(), state.options(), results, state.limit()));
    } else {
      discard();
      results.clear();
    }
    running = null;
    generation++;
    results = new MutableLineSet(budget);
    shown = -1;
    fireResults();
  }

  private void fireResults() {
    for (SearchViewListener listener : new ArrayList<SearchViewListener>(listeners)) {
      listener.resultsChanged();
    }
  }

  private void fireOptions() {
    for (SearchViewListener listener : new ArrayList<SearchViewListener>(listeners)) {
      listener.optionsChanged(fieldText, options);
    }
  }

  private void end(AtomicBoolean active) {
    if (active.compareAndSet(true, false)) {
      activity.setIdle();
    }
  }

  /** Keeps the newest generation's report; a report never replaces a newer generation's. */
  private void report(Report report) {
    while (true) {
      Report current = latest.get();
      if (current != null && current.generation() > report.generation()) {
        return;
      }
      if (latest.compareAndSet(current, report)) {
        updates.post();
        return;
      }
    }
  }

  /**
   * Receives one search's events on the search thread. The search holds a document entry until
   * {@link #finished}.
   */
  private final class Events implements SearchListener {
    private final long searchGeneration;
    private final MutableLineSet set;
    private final AtomicBoolean active;
    private final AtomicBoolean discarded;
    private final long first;
    private final long bytes;
    private final long started = System.nanoTime();
    private final long baseMatches;
    private final long baseSkipped;
    private final boolean refresh;
    private volatile long searched;
    private volatile long total;
    private volatile long matches;
    private volatile long skipped;
    private volatile boolean rangeOpen;

    Events(
        long searchGeneration,
        MutableLineSet set,
        AtomicBoolean active,
        AtomicBoolean discarded,
        long first,
        long bytes,
        long baseMatches,
        long baseSkipped,
        boolean refresh) {
      this.searchGeneration = searchGeneration;
      this.set = set;
      this.active = active;
      this.discarded = discarded;
      this.first = first;
      this.bytes = bytes;
      this.baseMatches = baseMatches;
      this.baseSkipped = baseSkipped;
      this.skipped = baseSkipped;
      this.refresh = refresh;
    }

    @Override
    public void progressed(long searchedLines, long totalLines, long matchCount) {
      searched = searchedLines;
      total = totalLines;
      matches = baseMatches + matchCount;
      report(build(false, null, SearchLimit.NONE, null));
    }

    @Override
    public void rangeOpenBeforeLast(boolean open) {
      rangeOpen = open;
    }

    @Override
    public void finished(
        OperationOutcome outcome, SearchLimit limit, long skippedLines, Exception failure) {
      skipped = baseSkipped + skippedLines;
      try {
        if (discarded.get()) {
          set.clear();
        }
        if (LOG.isInfoEnabled()) {
          LOG.info(
              "Search of {} {}: {} matches over {} lines, {} skipped, in {} ms",
              LogText.oneLine(document.document().name()),
              LogText.oneLine(outcome),
              set.size(),
              total,
              skippedLines,
              (System.nanoTime() - started) / 1_000_000);
        }
        report(build(true, outcome, limit, failure));
        end(active);
      } finally {
        document.document().exit();
      }
    }

    private Report build(
        boolean finished, OperationOutcome outcome, SearchLimit limit, Exception failure) {
      return new Report(
          searchGeneration,
          searched,
          total,
          matches,
          finished,
          outcome,
          limit,
          skipped,
          failure,
          first + total,
          bytes,
          refresh,
          rangeOpen);
    }
  }

  private void flush() {
    // Taken, not read: a second flush of a finished report would forget a refresh it started.
    Report report = latest.getAndSet(null);
    if (closed || report == null || report.generation() != generation) {
      return;
    }
    String text = state.text();
    SearchOptions toggles = state.options();
    int percent = report.total() == 0 ? 100 : (int) (report.searched() * 100 / report.total());
    SearchViewState next;
    if (report.refresh() && !report.finished()) {
      next =
          new SearchViewState(
              text,
              toggles,
              SearchViewState.Phase.DONE,
              100,
              report.matches(),
              SearchLimit.NONE,
              report.skipped(),
              null);
    } else if (!report.finished()) {
      next =
          new SearchViewState(
              text,
              toggles,
              SearchViewState.Phase.RUNNING,
              percent,
              report.matches(),
              SearchLimit.NONE,
              report.skipped(),
              null);
    } else {
      running = null;
      if (report.outcome() == OperationOutcome.COMPLETED && report.limit() == SearchLimit.NONE) {
        searchedEnd = report.end();
        searchedBytes = report.bytes();
        searchedRangeOpen = report.rangeOpen();
      }
      next = finishedState(text, toggles, report.refresh() ? 100 : percent, report);
    }
    publish(next);
    fireResults();
    if (report.finished()) {
      appended();
    }
  }

  private SearchViewState finishedState(
      String text, SearchOptions toggles, int percent, Report report) {
    return switch (report.outcome()) {
      case COMPLETED ->
          new SearchViewState(
              text,
              toggles,
              SearchViewState.Phase.DONE,
              percent,
              report.matches(),
              report.limit(),
              report.skipped(),
              null);
      case CANCELED ->
          new SearchViewState(
              text,
              toggles,
              SearchViewState.Phase.CANCELED,
              percent,
              report.matches(),
              SearchLimit.NONE,
              report.skipped(),
              null);
      case FAILED -> failed(text, toggles, report.matches(), failureText(report.failure()));
    };
  }

  private String failureText(Exception failure) {
    if (failure instanceof PatternSyntaxException syntax) {
      return UiText.SEARCH_REGEX_ERROR.format(
          Math.max(0, syntax.getIndex()) + 1, LogText.oneLine(syntax.getDescription()));
    }
    if (failure instanceof PatternTooComplexException complex) {
      return UiText.SEARCH_PATTERN_TOO_COMPLEX.format(complex.line() + 1);
    }
    String message = failure == null ? null : LogText.oneLine(failure.getMessage());
    LOG.warn("Search in {} failed: {}", LogText.oneLine(document.document().path()), message);
    return UiText.SEARCH_FAILED.format(
        message == null ? UiText.SEARCH_WORKER_FAILED.text() : message);
  }

  private static SearchViewState failed(
      String text, SearchOptions toggles, long matches, String message) {
    return new SearchViewState(
        text, toggles, SearchViewState.Phase.FAILED, 0, matches, SearchLimit.NONE, 0, message);
  }

  static String reason(ExpressionException e) {
    UiText text =
        switch (e.reason()) {
          case UNTERMINATED_QUOTE -> UiText.EXPRESSION_UNTERMINATED_QUOTE;
          case EXPECTED_TERM -> UiText.EXPRESSION_EXPECTED_TERM;
          case EXPECTED_CLOSE -> UiText.EXPRESSION_EXPECTED_CLOSE;
          case EXPECTED_OPERATOR -> UiText.EXPRESSION_EXPECTED_OPERATOR;
          case UNKNOWN_WORD -> UiText.EXPRESSION_UNKNOWN_WORD;
          case TOO_DEEP -> UiText.EXPRESSION_TOO_DEEP;
        };
    return text.text();
  }

  private void publish(SearchViewState next) {
    state = next;
    for (SearchViewListener listener : new ArrayList<SearchViewListener>(listeners)) {
      listener.stateChanged(next);
    }
  }
}
