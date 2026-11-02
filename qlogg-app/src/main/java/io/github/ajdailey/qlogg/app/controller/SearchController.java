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
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.Visibility;
import io.github.ajdailey.qlogg.core.search.ExpressionException;
import io.github.ajdailey.qlogg.core.search.ExpressionParser;
import io.github.ajdailey.qlogg.core.search.LineRange;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchExpression;
import io.github.ajdailey.qlogg.core.search.SearchLimit;
import io.github.ajdailey.qlogg.core.search.SearchListener;
import io.github.ajdailey.qlogg.core.search.SearchQuery;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

  /**
   * A finished search kept as a pinned result set.
   *
   * @param text the search text
   * @param options the toggles it ran with
   * @param lines the matching lines, charged to {@code RESULTS}
   * @param limit what stopped it early, or {@code NONE}
   */
  public record KeptResults(
      String text, SearchOptions options, MutableLineSet lines, SearchLimit limit) {}

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
      Exception failure,
      long end,
      long bytes,
      boolean refresh) {}

  /**
   * The search in progress: its activity, which ends once, and the flag that makes its own {@code
   * finished} call empty its result set once a newer generation replaced it.
   */
  private record Running(OperationHandle handle, AtomicBoolean active, AtomicBoolean discarded) {}

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
   * Returns the result set the filtered view shows: the current search's, or a kept set.
   *
   * @return the set
   */
  public LineSet shownResults() {
    return shown < 0 ? results : kept.get(shown).lines();
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
    kept.remove(index).lines().clear();
    if (shown == index) {
      shown = -1;
    } else if (shown > index) {
      shown--;
    }
    fireResults();
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
    if (text.isEmpty()) {
      publish(
          new SearchViewState(
              text, toggles, SearchViewState.Phase.IDLE, 0, 0, SearchLimit.NONE, null));
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
    SearchQuery query =
        new SearchQuery(
            expression,
            toggles.caseSensitive(),
            toggles.wholeWord(),
            toggles.inverse(),
            scope(toggles.inSelection()),
            document.document().hideAnsi());
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
            text, toggles, SearchViewState.Phase.RUNNING, 0, 0, SearchLimit.NONE, null));
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
            refresh);
    try {
      OperationHandle handle = engine.search(document.document(), index, query, results, events);
      running = new Running(handle, active, discarded);
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
            lastQuery.hideAnsi());
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

  /** Cancels the running search; its results so far stay. */
  public void cancel() {
    if (running != null) {
      running.handle().cancel();
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
      document.goToLine(next + 1);
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
      document.goToLine(previous + 1);
    } else {
      document.notifications().showNotice(UiText.NOTICE_START_OF_FILE.text());
    }
  }

  /** Cancels the search, ends its activity and returns every set's reservation; no later flush. */
  void detach() {
    closed = true;
    updates.close();
    discard();
    if (running != null) {
      end(running.active());
      running = null;
    }
    results.clear();
    for (KeptResults set : kept) {
      set.lines().clear();
    }
    kept.clear();
    listeners.clear();
  }

  /**
   * Starts a new generation with an empty result set. The finished current set is kept when asked
   * and it has a search behind it; otherwise any running search is canceled and its set emptied.
   */
  private void replaceResults(boolean keep) {
    boolean finished =
        state.phase() == SearchViewState.Phase.DONE
            || state.phase() == SearchViewState.Phase.CANCELED;
    if (keep && finished && running == null && !state.text().isEmpty()) {
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
    private final boolean refresh;
    private volatile long searched;
    private volatile long total;
    private volatile long matches;

    Events(
        long searchGeneration,
        MutableLineSet set,
        AtomicBoolean active,
        AtomicBoolean discarded,
        long first,
        long bytes,
        long baseMatches,
        boolean refresh) {
      this.searchGeneration = searchGeneration;
      this.set = set;
      this.active = active;
      this.discarded = discarded;
      this.first = first;
      this.bytes = bytes;
      this.baseMatches = baseMatches;
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
    public void finished(OperationOutcome outcome, SearchLimit limit, Exception failure) {
      try {
        if (discarded.get()) {
          set.clear();
        }
        if (LOG.isInfoEnabled()) {
          LOG.info(
              "Search of {} {}: {} matches over {} lines in {} ms",
              LogText.oneLine(document.document().name()),
              LogText.oneLine(outcome),
              set.size(),
              total,
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
          failure,
          first + total,
          bytes,
          refresh);
    }
  }

  private void flush() {
    Report report = latest.get();
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
              null);
    } else {
      running = null;
      if (report.outcome() == OperationOutcome.COMPLETED && report.limit() == SearchLimit.NONE) {
        searchedEnd = report.end();
        searchedBytes = report.bytes();
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
              null);
      case CANCELED ->
          new SearchViewState(
              text,
              toggles,
              SearchViewState.Phase.CANCELED,
              percent,
              report.matches(),
              SearchLimit.NONE,
              null);
      case FAILED -> failed(text, toggles, report.matches(), failureText(report.failure()));
    };
  }

  private String failureText(Exception failure) {
    if (failure instanceof PatternSyntaxException syntax) {
      return UiText.SEARCH_REGEX_ERROR.format(
          Math.max(0, syntax.getIndex()) + 1, LogText.oneLine(syntax.getDescription()));
    }
    String message = failure == null ? null : LogText.oneLine(failure.getMessage());
    LOG.warn("Search in {} failed: {}", LogText.oneLine(document.document().path()), message);
    return UiText.SEARCH_FAILED.format(
        message == null ? UiText.SEARCH_WORKER_FAILED.text() : message);
  }

  private static SearchViewState failed(
      String text, SearchOptions toggles, long matches, String message) {
    return new SearchViewState(
        text, toggles, SearchViewState.Phase.FAILED, 0, matches, SearchLimit.NONE, message);
  }

  private static String reason(ExpressionException e) {
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
