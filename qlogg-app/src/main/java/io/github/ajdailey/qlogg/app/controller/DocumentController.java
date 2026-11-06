package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.highlight.HighlightComposer;
import io.github.ajdailey.qlogg.app.highlight.LabelState;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.ActivityIndicator;
import io.github.ajdailey.qlogg.app.view.ClipboardService;
import io.github.ajdailey.qlogg.app.view.DocumentViewState;
import io.github.ajdailey.qlogg.app.view.NotificationService;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewListener;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.app.view.UiUpdateQueue;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.DocumentListener;
import io.github.ajdailey.qlogg.core.model.DocumentStatus;
import io.github.ajdailey.qlogg.core.model.FoldMap;
import io.github.ajdailey.qlogg.core.model.FoldRules;
import io.github.ajdailey.qlogg.core.model.Highlighter;
import io.github.ajdailey.qlogg.core.model.LineReader;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.MarkComments;
import io.github.ajdailey.qlogg.core.model.MarkToggle;
import io.github.ajdailey.qlogg.core.model.RowCursor;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.text.DetectionReason;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One open document's main view, where row n is line n unless entries are folded, plus its search
 * and filtered view. Confined to the UI thread and never blocks it.
 *
 * <p>Index progress arrives on the indexing thread, which only posts a flush; flushes run at most
 * every {@link #FLUSH_INTERVAL_MILLIS} ms on the UI thread and read the latest snapshot. Indexing
 * holds the {@link ActivityIndicator} active until it ends. Marks live on the {@link LogDocument};
 * a change repaints both views. The document's {@link SearchController}, {@link FilteredController}
 * and {@link OverviewController} detach with it.
 *
 * <p>Both views color their rows with one {@link Highlighter} built from the rules {@link
 * HighlightComposer} composes from the settings, the last search and the document's color labels.
 *
 * <p>{@link #toggleMarks} runs {@link MarkToggle} on a {@code qlogg-search-} worker with the {@link
 * ActivityIndicator} active and progress notices at most every {@link #PROGRESS_MILLIS} ms; {@code
 * Esc} ({@link CommandId#STOP}) cancels it and stops loading; while a go to time runs, loading goes
 * on until the next {@code Esc}. One toggle runs at a time per document. The filtered view rebuilds
 * its marks snapshot after the toggle ends.
 *
 * <p>Follow: while following, each flush scrolls to the last line. A user move that leaves the
 * bottom releases follow; with {@code follow.onOverscroll}, scrolling down {@link #OVERSCROLL_ROWS}
 * rows past the bottom engages it. Growth the document indexes re-runs an auto-refresh search over
 * the new lines. A truncated, replaced or modified file goes to the {@link DocumentHost}, which
 * reopens it; a missing file shows a notice and keeps its content.
 *
 * <p>Folding ({@link FoldController}): a collapsed entry is one row, so rows map to lines through
 * the {@link FoldMap}. A fold change keeps the top line and the selection on their lines. Go to
 * line, find next and previous, mark jumps, a filtered-view or overview click and quick find expand
 * the entry hiding their line first ({@link #revealRow}). Copy, marks and save skip hidden lines. A
 * highlighter set chosen for the file by a {@link
 * io.github.ajdailey.qlogg.app.highlight.HighlightSelector} replaces the active sets for this
 * document until the user picks sets.
 *
 * <p>The {@link TriggerController} applies the triggers of the same rules to appended lines.
 */
public final class DocumentController extends LineViewController {
  /** Least interval between flushes of index progress. */
  public static final int FLUSH_INTERVAL_MILLIS = 50;

  /** Least interval between mark progress notices. */
  public static final int PROGRESS_MILLIS = 250;

  /** Rows scrolled down past the bottom that engage follow. */
  public static final int OVERSCROLL_ROWS = 5;

  /** Rows above which a finished mark toggle reports what it changed. */
  static final int QUIET_MARK_ROWS = MarkToggle.BATCH;

  private static final Logger LOG = LoggerFactory.getLogger(DocumentController.class);

  private final LogDocument document;
  private final AtomicBoolean indexActive = new AtomicBoolean();

  /** {@code System.nanoTime()} at {@link #start}; 0 once the first indexing pass is logged. */
  private final AtomicLong indexStarted = new AtomicLong();

  private final AtomicBoolean trimMarks = new AtomicBoolean();
  private final Events events = new Events();
  private final UiUpdateQueue updates;
  private final SearchController search;
  private final FilteredController filtered;
  private final OverviewController overview;
  private final DocumentHost host;
  private volatile String failure;
  private LabelState labels = LabelState.EMPTY;
  private long labelCharge;
  private CancellationToken marking;
  private String highlightedText = "";
  private SearchOptions highlightedOptions;
  private PendingMove pending;
  private boolean following;
  private long overscroll;
  private DocumentViewState state;
  // Folding and the automatic highlighter set.
  private final FoldMap folds;
  private final FoldController folding;
  private String autoSet;
  // Keyword triggers.
  private final TriggerController triggers;
  // Timestamps.
  private final TimeController time;

  /**
   * A move that waits for indexing to reach its line.
   *
   * @param line the 0-based line
   * @param top the first visible line of a restored position, or -1 to center the line
   * @param column the caret's column of a restored position
   * @param quiet true when a time sync group does not hear of the move
   */
  private record PendingMove(long line, long top, long column, boolean quiet) {}

  /**
   * Creates the controller for an open document; {@link #start} begins indexing.
   *
   * @param document the document; this controller closes it
   * @param workers runs reads and the close
   * @param ui the UI-thread dispatcher
   * @param clipboard receives copied text
   * @param notifications shows copy and indexing failures and notices
   * @param activity shows indexing, copy and search as running
   * @param budget charged while copied text is built and for search results
   * @param engine runs this document's searches
   * @param host the search history and the initial search toggles
   */
  public DocumentController(
      LogDocument document,
      Workers workers,
      UiDispatcher ui,
      ClipboardService clipboard,
      NotificationService notifications,
      ActivityIndicator activity,
      MemoryBudget budget,
      SearchEngine engine,
      DocumentHost host) {
    super(document, workers, ui, clipboard, notifications, activity, budget);
    this.document = document;
    this.time = new TimeController(this);
    this.folds = new FoldMap(budget);
    this.folding = new FoldController(this, folds);
    this.state = buildState(document.status());
    this.updates =
        new UiUpdateQueue(
            ui,
            FLUSH_INTERVAL_MILLIS,
            new Runnable() {
              @Override
              public void run() {
                refresh();
              }
            });
    this.host = host;
    this.search = new SearchController(this, engine, ui, activity, budget, host);
    this.filtered =
        new FilteredController(
            this, search, workers, ui, clipboard, notifications, activity, budget);
    this.overview = new OverviewController(this, workers, ui, budget);
    this.triggers = new TriggerController(this, document);
    search.addListener(
        new SearchViewListener() {
          @Override
          public void stateChanged(SearchViewState state) {
            searchStateChanged(state);
          }

          @Override
          public void resultsChanged() {
            overview.refresh();
          }
        });
    refreshHighlights();
  }

  /** Forwards document events to a flush on the UI thread. */
  private final class Events implements DocumentListener {
    @Override
    public void indexProgressed(IndexProgress progress) {
      updates.post();
    }

    @Override
    public void indexFinished(OperationOutcome outcome, IOException cause) {
      if (outcome == OperationOutcome.FAILED) {
        String message =
            cause == null ? UiText.INDEXING_FAILED.text() : LogText.oneLine(cause.getMessage());
        failure = message;
        LOG.warn("Indexing {} failed: {}", LogText.oneLine(document.path()), message);
      }
      long started = indexStarted.getAndSet(0);
      if (outcome == OperationOutcome.COMPLETED && started != 0 && LOG.isInfoEnabled()) {
        LineIndex index = document.index();
        LOG.info(
            "Indexing {} done: {} lines, {} bytes in {} ms{}",
            LogText.oneLine(document.name()),
            index.lineCount(),
            index.indexedBytes(),
            (System.nanoTime() - started) / 1_000_000,
            index.sparse() ? ", sparse" : "");
      }
      if (outcome == OperationOutcome.COMPLETED && trimMarks.compareAndSet(true, false)) {
        document.marks().removeFrom(document.index().lineCount());
        document.markComments().removeFrom(document.index().lineCount());
        ui().asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    if (!isClosed()) {
                      marksChanged();
                    }
                  }
                });
      }
      indexEnded();
      updates.post();
    }

    @Override
    public void fileChanged(final FileChange change) {
      updates.post();
      if (change == FileChange.APPENDED || change == FileChange.UNCHANGED) {
        return;
      }
      ui().asyncExec(
              new Runnable() {
                @Override
                public void run() {
                  changedOnDisk(change);
                }
              });
    }

    @Override
    public void closed() {
      // The controller closes the document; nothing to refresh.
    }
  }

  private void changedOnDisk(FileChange change) {
    if (isClosed()) {
      return;
    }
    if (change == FileChange.MISSING) {
      notifications().showNotice(UiText.NOTICE_FILE_MISSING.format(state.title()));
      refresh();
    } else {
      host.reopenChanged(this, change);
    }
  }

  /**
   * Reports whether the view follows the end of the file.
   *
   * @return true while following
   */
  public boolean following() {
    return following;
  }

  /**
   * Starts or stops following; starting scrolls to the last line.
   *
   * @param follow true to follow
   */
  public void setFollowing(boolean follow) {
    overscroll = 0;
    if (follow == following || isClosed() || (follow && document.inMemory())) {
      return;
    }
    following = follow;
    if (follow) {
      viewport().scrollToLine(viewport().maxTop());
      changed();
    }
    state = buildState(document.status());
    fireState(state);
    host.documentStateChanged();
  }

  @Override
  public void scrollLines(long delta) {
    boolean atBottom = viewport().atBottom();
    super.scrollLines(delta);
    if (delta <= 0 || !atBottom || following || !host.followOnOverscroll()) {
      overscroll = 0;
      return;
    }
    overscroll += delta;
    if (overscroll >= OVERSCROLL_ROWS) {
      setFollowing(true);
    }
  }

  @Override
  void userMoved() {
    if (following && !viewport().atBottom()) {
      setFollowing(false);
    }
  }

  @Override
  void caretMoved() {
    time.caretMoved();
  }

  /**
   * Takes over a reloaded document's color labels and the marks copied into this document before
   * {@link #start}; the first completed indexing drops the marks past the last line. Call on the UI
   * thread.
   *
   * @param kept the old controller's labels
   * @param marksCopied true when marks were copied into this document
   */
  void restore(LabelState kept, boolean marksCopied) {
    trimMarks.set(marksCopied);
    setLabels(kept);
  }

  /**
   * Cancels a go to time, a mark toggle and the copies and saves of both views. Unless a go to time
   * was running, also stops loading; growth stays unindexed until a reload.
   */
  public void stop() {
    boolean searching = time.running();
    time.cancel();
    cancelMarks();
    // Copy and save cancel.
    cancelExports();
    filtered.cancelExports();
    if (!searching && document.stopIndexing()) {
      notifications()
          .showNotice(UiText.NOTICE_LOADING_STOPPED.format(document.index().lineCount()));
    }
    updates.post();
  }

  /** Starts indexing and the first read. */
  public void start() {
    indexActive.set(true);
    activity().setActive();
    document.addListener(events);
    indexStarted.set(System.nanoTime());
    document.startIndexing();
    triggers.started();
    refresh();
  }

  /** Ends the indexing activity once, on whichever comes first: the end of indexing or detach. */
  private void indexEnded() {
    if (indexActive.compareAndSet(true, false)) {
      activity().setIdle();
    }
  }

  @Override
  public DocumentController owner() {
    return this;
  }

  @Override
  public long sourceLine(long row) {
    return row >= 0 && row < viewport().lineCount() ? folds.lineAt(row) : -1;
  }

  @Override
  public long rowOf(long line) {
    return folds.rowOf(line);
  }

  @Override
  public RowCursor rows() {
    return folds.cursor(document.index().lineCount());
  }

  @Override
  public long revealRow(long line) {
    if (folds.collapsedEntry(line) != null && !folds.isCollapsed(line)) {
      final long hidden = line;
      changeFolds(
          new Runnable() {
            @Override
            public void run() {
              folds.expandContaining(hidden);
            }
          });
    }
    return folds.rowOf(line);
  }

  /**
   * Returns the line after the last line a row stands for.
   *
   * @param row a 0-based row
   * @return the line after the row's line, or after its collapsed entry
   */
  public long endOfRow(long row) {
    long line = sourceLine(row);
    long[] entry = folds.collapsedEntry(line);
    return entry == null ? line + 1 : entry[1];
  }

  /**
   * Reports whether a row's line starts a collapsed entry, for the gutter's fold marker.
   *
   * @param line a 0-based line
   * @return true when lines are hidden under it
   */
  @Override
  public boolean folded(long line) {
    return folds.isCollapsed(line);
  }

  /**
   * Returns the fold map, for tests.
   *
   * @return the map
   */
  FoldMap folds() {
    return folds;
  }

  /** Collapses or expands the entry at the caret's line. */
  public void toggleFold() {
    folding.toggle(sourceLine(selection().caret().line()));
  }

  /** Collapses every multi-line entry as it is shown. */
  public void collapseAllFolds() {
    folding.collapseAll();
  }

  /** Expands every entry. */
  public void expandAllFolds() {
    folding.expandAll();
  }

  /**
   * Sets the rules that find entries; every entry expands.
   *
   * @param rules the rules
   */
  public void setFoldRules(FoldRules rules) {
    folding.setRules(rules);
  }

  /**
   * Applies a change to the fold map, keeping the top line and the selection on their lines.
   *
   * @param change mutates the map
   */
  void changeFolds(Runnable change) {
    long topLine = sourceLine(viewport().topLine());
    Selection current = selection();
    long anchor = sourceLine(current.anchor().line());
    long caret = sourceLine(current.caret().line());
    change.run();
    long rows = document.index().lineCount() - folds.hiddenLines();
    viewport().setLineCount(rows);
    if (topLine >= 0) {
      viewport().scrollToLine(folds.rowOf(topLine));
    }
    if (anchor >= 0 && caret >= 0) {
      Selection moved =
          new Selection(
              new TextPosition(folds.rowOf(anchor), current.anchor().column()),
              new TextPosition(folds.rowOf(caret), current.caret().column()));
      if (blockSelection()) {
        selectBlock(moved);
      } else {
        select(moved);
      }
    }
    refreshRows(rows);
    overview.foldsChanged();
  }

  /** Asks the fold controller to classify the visible region, with collapse-all on. */
  void foldsMayNeedClassifying() {
    if (!folding.collapsingAll()) {
      return;
    }
    long count = viewport().lineCount();
    if (count == 0) {
      return;
    }
    long top = viewport().topLine();
    long bottom = Math.min(count - 1, top + viewport().rows());
    folding.visible(sourceLine(top), sourceLine(bottom), viewport().rows());
  }

  @Override
  void changed() {
    super.changed();
    foldsMayNeedClassifying();
  }

  /**
   * Turns text wrap on or off in both views.
   *
   * @param on true to wrap
   */
  public void setWrapBoth(boolean on) {
    setWrap(on);
    filtered.setWrap(on);
  }

  /**
   * Turns whitespace markers on or off in both views.
   *
   * @param on true to mark
   */
  public void setWhitespaceBoth(boolean on) {
    setWhitespace(on);
    filtered.setWhitespace(on);
  }

  /**
   * Returns the highlighter set a selector chose for this document.
   *
   * @return the set's id, or null when the active sets apply
   */
  public String autoHighlightSet() {
    return autoSet;
  }

  /**
   * Colors this document with one set instead of the active sets, or with the active sets again.
   *
   * @param setId the set's id, or null
   */
  public void setAutoHighlightSet(String setId) {
    autoSet = setId;
    refreshHighlights();
  }

  /**
   * Returns the search over this document.
   *
   * @return the controller
   */
  public SearchController search() {
    return search;
  }

  /**
   * Returns the overview bar's model.
   *
   * @return the controller
   */
  public OverviewController overview() {
    return overview;
  }

  /** Returns the host given at construction; a tab move changes the window behind it. */
  DocumentHost host() {
    return host;
  }

  /**
   * Returns the filtered view of this document's results and marks.
   *
   * @return the controller
   */
  public FilteredController filtered() {
    return filtered;
  }

  @Override
  public DocumentViewState state() {
    return state;
  }

  @Override
  public void execute(CommandId command) {
    switch (command) {
      case NEXT_MARK -> nextMark();
      case PREVIOUS_MARK -> previousMark();
      case STOP -> stop();
      case FOLLOW -> setFollowing(!following);
      default -> super.execute(command);
    }
  }

  /**
   * Puts the caret on a line and scrolls it to the middle of the view. Before indexing reaches the
   * line the request waits and runs once it does; a line past the end goes to the last line.
   *
   * @param oneBasedLine the line as the user numbers it, from 1
   */
  public void goToLine(long oneBasedLine) {
    long line = Math.max(0, oneBasedLine - 1);
    if (reachable(line)) {
      pending = null;
      moveTo(revealRow(Math.min(line, Math.max(0, shownLines() - 1))));
    } else {
      pending = new PendingMove(line, -1, 0, false);
    }
  }

  /**
   * Goes to a line as {@link #goToLine} does, for a position kept rather than chosen: a time sync
   * group does not hear of it.
   *
   * @param oneBasedLine the line as the user numbers it, from 1
   */
  void keepLine(long oneBasedLine) {
    time.setQuiet(true);
    goToLine(oneBasedLine);
    time.setQuiet(false);
    if (pending != null) {
      pending = new PendingMove(pending.line(), -1, 0, true);
    }
  }

  /**
   * Selects the lines {@code [first, last]}, expanding a collapsed entry that hides either end.
   *
   * @param first the 0-based first line
   * @param last the 0-based last line
   */
  void selectLines(long first, long last) {
    revealRow(last);
    long firstRow = revealRow(first);
    selectRows(firstRow, revealRow(last));
  }

  /**
   * Lines the viewport holds as of the last flush. The live index runs ahead of it until the next
   * flush, and {@link #moveTo} clamps to the viewport, so a move decided on the live index lands on
   * the viewport's last row.
   */
  private long shownLines() {
    return viewport().lineCount() + folds.hiddenLines();
  }

  /** True when the last flush showed the line, or showed indexing ended. */
  private boolean reachable(long line) {
    return line < shownLines() || !state.indexing();
  }

  /**
   * Restores a remembered position: the caret's line and column and the first visible line. Before
   * indexing reaches the line the request waits, as {@link #goToLine} does; past the end the caret
   * goes to the last line.
   *
   * @param top the first visible line, 0-based
   * @param line the caret's line, 0-based
   * @param column the caret's column
   */
  public void restorePosition(long top, long line, long column) {
    if (reachable(line)) {
      pending = null;
      applyPosition(top, line, column);
    } else {
      pending = new PendingMove(line, top, column, false);
    }
  }

  private void applyPosition(long top, long line, long column) {
    time.setQuiet(true);
    moveTo(line);
    long caret = selection().caret().line();
    if (caret == line) {
      select(Selection.caretAt(new TextPosition(caret, column)));
    }
    time.setQuiet(false);
    viewport().scrollToLine(Math.min(top, caret));
    changed();
  }

  /**
   * Shows a line: the caret moves to it and it scrolls to the middle when it is off screen.
   *
   * @param line a 0-based line
   */
  public void showLine(long line) {
    if (line < 0) {
      return;
    }
    long row = revealRow(line);
    long top = viewport().topLine();
    if (row >= top && row < top + viewport().rows()) {
      select(Selection.caretAt(new TextPosition(row, 0)));
    } else {
      moveTo(row);
    }
  }

  /**
   * Reports a go to line that waits for indexing.
   *
   * @return the 0-based line, or -1
   */
  public long pendingLine() {
    return pending == null ? -1 : pending.line();
  }

  /** Goes to the first line. */
  public void goToTop() {
    moveTo(0);
  }

  /** Goes to the last line indexed so far. */
  public void goToBottom() {
    moveTo(Long.MAX_VALUE);
  }

  /** Moves the caret to the first mark after its line, or shows a notice when there is none. */
  public void nextMark() {
    long caret = Math.max(0, sourceLine(selection().caret().line()));
    long next = document.marks().ceiling(caret + 1);
    if (next >= 0) {
      showLine(next);
    } else {
      notifications().showNotice(UiText.NOTICE_NO_NEXT_MARK.text());
    }
  }

  /** Moves the caret to the last mark before its line, or shows a notice when there is none. */
  public void previousMark() {
    long caret = Math.max(0, sourceLine(selection().caret().line()));
    long previous = caret == 0 ? -1 : document.marks().floor(caret - 1);
    if (previous >= 0) {
      showLine(previous);
    } else {
      notifications().showNotice(UiText.NOTICE_NO_PREVIOUS_MARK.text());
    }
  }

  /**
   * Returns the marked lines.
   *
   * @return the document's marks
   */
  public LineSet marks() {
    return document.marks();
  }

  /** Repaints both views and the overview bar after the marks changed. */
  void marksChanged() {
    filtered.refresh();
    overview.refresh();
    fireContent();
    host.documentStateChanged();
  }

  /**
   * Reports whether a mark toggle runs.
   *
   * @return true until it ends
   */
  public boolean markingRunning() {
    return marking != null;
  }

  /**
   * Toggles the marks of consecutive rows of a view on a worker; while one runs, a second is
   * refused with a notice.
   *
   * @param rows walks the view's lines; thread-safe
   * @param firstLine the first row's line
   * @param count rows
   */
  void toggleMarks(final RowCursor rows, final long firstLine, final long count) {
    if (marking != null) {
      notifications().showNotice(UiText.MARK_RUNNING.text());
      return;
    }
    final CancellationToken token = new CancellationToken();
    marking = token;
    activity().setActive();
    final MutableLineSet marks = document.marks();
    boolean started =
        workers()
            .tryStart(
                Workers.Kind.SEARCH,
                new Runnable() {
                  @Override
                  public void run() {
                    mark(marks, rows, firstLine, count, token);
                  }
                });
    if (!started) {
      marking = null;
      activity().setIdle();
    }
  }

  /** Runs on a worker; posts back on every outcome, so the activity always ends. */
  private void mark(
      MutableLineSet marks,
      RowCursor rows,
      long firstLine,
      final long count,
      final CancellationToken token) {
    final AtomicLong lastNotice = new AtomicLong(System.nanoTime());
    MarkToggle.Result result = null;
    boolean admitted = document.enter();
    try {
      if (!admitted) {
        return;
      }
      result =
          MarkToggle.run(
              marks,
              document.markComments(),
              rows,
              firstLine,
              count,
              token,
              new MarkToggle.Progress() {
                @Override
                public void progressed(long done, long total) {
                  long now = System.nanoTime();
                  long last = lastNotice.get();
                  if (now - last >= PROGRESS_MILLIS * 1_000_000L
                      && lastNotice.compareAndSet(last, now)) {
                    final int percent = (int) (done * 100 / Math.max(1, total));
                    ui().asyncExec(
                            new Runnable() {
                              @Override
                              public void run() {
                                if (marking == token) {
                                  notifications().showNotice(UiText.MARK_PROGRESS.format(percent));
                                }
                              }
                            });
                  }
                }
              });
    } finally {
      if (admitted) {
        document.exit();
      }
      final MarkToggle.Result done = result;
      ui().asyncExec(
              new Runnable() {
                @Override
                public void run() {
                  marked(done, count, token);
                }
              });
    }
  }

  private void marked(MarkToggle.Result result, long count, CancellationToken token) {
    if (marking == token) {
      marking = null;
    }
    activity().setIdle();
    if (isClosed()) {
      return;
    }
    marksChanged();
    if (result == null) {
      return;
    }
    if (result.memoryLimit()) {
      notifications().showNotice(UiText.MARK_MEMORY_LIMIT.format(result.changed()));
    } else if (result.canceled()) {
      notifications().showNotice(UiText.MARK_CANCELED.format(result.changed()));
    } else if (count > QUIET_MARK_ROWS) {
      notifications()
          .showNotice(
              result.marked()
                  ? UiText.MARK_DONE.format(result.changed())
                  : UiText.UNMARK_DONE.format(result.changed()));
    }
  }

  /** Cancels a running mark toggle; what it changed stays. */
  public void cancelMarks() {
    if (marking != null) {
      marking.cancel();
    }
  }

  // Mark comments and marks list.

  /**
   * Sets a line's mark comment, marking the line first when it is unmarked; an empty comment keeps
   * the mark without a comment. A mark or comment the {@code RESULTS} share refuses shows a notice.
   *
   * @param line a 0-based indexed line
   * @param text the comment, cleaned by {@link MarkComments#clean}
   */
  public void setMarkComment(long line, String text) {
    if (isClosed() || line < 0 || line >= document.index().lineCount()) {
      return;
    }
    MutableLineSet marks = document.marks();
    if (!marks.contains(line) && !marks.addSorted(new long[] {line}, 1)) {
      notifications().showNotice(UiText.NOTICE_MARKS_NOT_KEPT.text());
      return;
    }
    if (!document.markComments().put(line, text)) {
      notifications().showNotice(UiText.NOTICE_COMMENT_NOT_KEPT.format(MarkComments.MAX_COMMENTS));
    }
    marksChanged();
  }

  /**
   * Removes the marks of lines and their comments.
   *
   * @param lines 0-based lines, in any order
   */
  public void removeMarks(long[] lines) {
    if (isClosed() || lines.length == 0) {
      return;
    }
    long[] sorted = lines.clone();
    Arrays.sort(sorted);
    document.marks().removeSorted(sorted, sorted.length);
    document.markComments().removeAll(sorted, sorted.length);
    marksChanged();
  }

  /**
   * Returns the color label texts.
   *
   * @return the state
   */
  public LabelState labels() {
    return labels;
  }

  /**
   * Applies a numbered color label to a text, or removes it when the text has that label.
   *
   * @param label 0 to 8
   * @param text the selected text
   */
  public void applyLabel(int label, String text) {
    setLabels(labels.apply(label, text));
  }

  /**
   * Applies the next color label of the cycle to a text, or removes its label.
   *
   * @param text the selected text
   */
  public void nextLabel(String text) {
    setLabels(labels.next(text, host.highlightSettings().labels()));
  }

  /**
   * Removes a text's color label.
   *
   * @param text the selected text
   */
  public void removeLabel(String text) {
    setLabels(labels.remove(text));
  }

  /** Removes every color label and restarts the cycle. */
  public void clearLabels() {
    setLabels(LabelState.EMPTY);
  }

  /** Charges the texts to {@code HIGHLIGHT}; a state the share refuses is not applied. */
  private void setLabels(LabelState next) {
    if (next == labels) {
      if (labels.size() >= LabelState.MAX_TEXTS) {
        notifications().showNotice(UiText.LABEL_TOO_MANY.format(LabelState.MAX_TEXTS));
      }
      return;
    }
    long bytes = next.retainedBytes();
    MemoryBudget budget = budget();
    budget.release(MemoryShare.HIGHLIGHT, labelCharge);
    if (!budget.reserve(MemoryShare.HIGHLIGHT, bytes)) {
      budget.charge(MemoryShare.HIGHLIGHT, labelCharge);
      notifications().showNotice(UiText.LABEL_MEMORY_LIMIT.text());
      return;
    }
    labelCharge = bytes;
    labels = next;
    refreshHighlights();
  }

  /** Recomposes the views' highlighter from the settings, the last search and the labels. */
  public void refreshHighlights() {
    if (isClosed()) {
      return;
    }
    Highlighter highlighter =
        Highlighter.of(
            HighlightComposer.compose(
                host.highlightSettings(), autoSet, lastSearch(), labels, host.palette()));
    setHighlighter(highlighter);
    filtered.setHighlighter(highlighter);
    triggers.setRules(HighlightComposer.triggerRules(host.highlightSettings(), autoSet));
  }

  // Keyword triggers.

  /**
   * Returns the keyword triggers applied to appended lines.
   *
   * @return the controller
   */
  public TriggerController triggers() {
    return triggers;
  }

  /**
   * Turns follow off for a trigger and reports it to the host; nothing happens while not following.
   *
   * @param rule the rule's pattern, cut for display
   * @param line the 0-based line it matched
   */
  void followStoppedByTrigger(String rule, long line) {
    if (!following || isClosed()) {
      return;
    }
    setFollowing(false);
    host.followStoppedByTrigger(this, rule, line);
  }

  private HighlightComposer.LastSearch lastSearch() {
    SearchOptions options = highlightedOptions;
    if (options == null) {
      return HighlightComposer.LastSearch.NONE;
    }
    return new HighlightComposer.LastSearch(
        highlightedText,
        options.mode(),
        options.caseSensitive(),
        options.wholeWord(),
        options.combining(),
        options.inverse());
  }

  private void searchStateChanged(SearchViewState state) {
    boolean shown =
        state.phase() != SearchViewState.Phase.IDLE
            && state.phase() != SearchViewState.Phase.FAILED;
    String text = shown ? state.text() : "";
    SearchOptions options = shown ? state.options() : null;
    if (!text.equals(highlightedText)
        || (options == null ? highlightedOptions != null : !options.equals(highlightedOptions))) {
      highlightedText = text;
      highlightedOptions = options;
      refreshHighlights();
    }
  }

  // Timestamps.

  /**
   * Returns the time state and go to time.
   *
   * @return the controller
   */
  public TimeController time() {
    return time;
  }

  /** The time state changed; both views read or reformat their times. */
  void timeChanged(boolean reread) {
    timeSettingsChanged(reread);
    filtered.timeSettingsChanged(reread);
    // Time spread.
    if (reread) {
      overview.timeFormatChanged();
    }
  }

  /**
   * Returns the search toggles new documents start with.
   *
   * @return the toggles of this document's search
   */
  public SearchOptions searchOptions() {
    return search.options();
  }

  /**
   * Detaches from the document: no more flushes, reads or copies are applied. Call on the UI
   * thread; the caller then closes the document on a worker.
   */
  public void detach() {
    cancelMarks();
    updates.close();
    folding.detach();
    search.detach();
    filtered.detach();
    overview.detach();
    triggers.detach();
    time.detach();
    budget().release(MemoryShare.HIGHLIGHT, labelCharge);
    labelCharge = 0;
    document.removeListener(events);
    detachView();
    indexEnded();
  }

  /** Detaches and closes the document on a {@code qlogg-lifecycle-} worker. */
  public void close() {
    if (isClosed()) {
      return;
    }
    detach();
    if (!workers().tryStart(Workers.Kind.LIFECYCLE, document::close)) {
      LOG.warn("Cannot close {}: workers are closed", LogText.oneLine(document.path()));
    }
  }

  private void refresh() {
    // Status before the index: indexing ends after its last snapshot, so a status that shows it
    // ended pairs with a complete index.
    DocumentStatus status = document.status();
    LineIndex index = document.index();
    viewport().setMaxColumns(Math.max(estimatedColumns(index), lines().maxWidth()));
    long rows = index.lineCount() - folds.hiddenLines();
    viewport().setLineCount(rows);
    if (pending != null && (pending.line() < index.lineCount() || !status.indexing())) {
      PendingMove move = pending;
      pending = null;
      long line = Math.min(move.line(), Math.max(0, index.lineCount() - 1));
      if (move.top() >= 0) {
        applyPosition(move.top(), line, move.column());
      } else {
        time.setQuiet(move.quiet());
        moveTo(revealRow(line));
        time.setQuiet(false);
      }
    }
    if (following) {
      viewport().scrollToLine(viewport().maxTop());
    }
    state = buildState(status);
    fireState(state);
    refreshRows(rows);
    overview.refresh();
    search.appended();
    filtered.grew(index.lineCount());
    triggers.flushed(status.indexing());
  }

  private long estimatedColumns(LineIndex index) {
    return Math.min(index.maxLineBytes(), LineReader.MAX_LINE_BYTES)
        / document.encoding().unitWidth();
  }

  private DocumentViewState buildState(DocumentStatus status) {
    return new DocumentViewState(
        document.name(),
        document.inMemory() ? "" : document.path().toString(),
        document.encoding().charset().name(),
        document.detection().reason() == DetectionReason.FORCED,
        status.lineCount(),
        status.indexPercent(),
        status.indexing(),
        status.sparseIndex(),
        following,
        status.fileBytes(),
        status.modifiedMillis(),
        status.missing(),
        status.loadingStopped(),
        failure);
  }
}
