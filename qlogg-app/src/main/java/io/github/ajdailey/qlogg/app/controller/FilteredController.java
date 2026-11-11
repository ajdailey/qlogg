package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.ActivityIndicator;
import io.github.ajdailey.qlogg.app.view.ClipboardService;
import io.github.ajdailey.qlogg.app.view.DocumentViewState;
import io.github.ajdailey.qlogg.app.view.NotificationService;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewListener;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.model.ContextRows;
import io.github.ajdailey.qlogg.core.model.FilteredView;
import io.github.ajdailey.qlogg.core.model.RowCursor;
import io.github.ajdailey.qlogg.core.model.Visibility;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import java.util.concurrent.CancellationException;

/**
 * The filtered view under the main view: one row per matching or marked line ({@link Visibility}),
 * from the result set the search shows. Rows read their text from the document, so copy and display
 * use the source lines. Moving the caret to a row shows its line in the main view. Line numbers are
 * the source lines' and show by default, toggled apart from the main view's. A click, Enter, Go to
 * Top and Bottom and quick find record the main view's line from before this view moved it in the
 * document's navigation history. Confined to the UI thread.
 *
 * <p>In marks and matches mode the rows use a snapshot of the marks outside the matches ({@link
 * FilteredView.Extras}), built on a {@code qlogg-search-} worker whenever the marks or the matches
 * changed, at most one at a time and not while a mark toggle runs. Until the snapshot arrives the
 * rows stay on the previous one, so the view never walks the marks on the UI thread. A snapshot the
 * results share cannot hold shows a notice.
 *
 * <p>Context lines come from the shown set's toggles ({@link SearchController#shownOptions}), so a
 * kept set keeps the counts it ran with. The {@link ContextRows} are extended on a {@code
 * qlogg-search-} worker after the matches change, before the marks snapshot, at most one build at a
 * time; the rows stay on the installed build until the next one arrives. The last result's context
 * extends as the file grows ({@link #grew}). A build the results share cannot hold shows a notice.
 */
public final class FilteredController extends LineViewController {
  private final DocumentController owner;
  private final SearchController search;
  private final FilteredView view;
  private CancellationToken building;
  private CancellationToken extending;
  private boolean noticeShown;
  private boolean contextNoticeShown;
  private long seenLines;
  private final SearchViewListener results =
      new SearchViewListener() {
        @Override
        public void stateChanged(SearchViewState state) {
          SearchOptions shown = search.shownOptions();
          if (shown.contextBefore() != view.contextBefore()
              || shown.contextAfter() != view.contextAfter()) {
            applyContext();
            refresh();
          }
        }

        @Override
        public void resultsChanged() {
          view.setMatches(search.shownResults());
          applyContext();
          refresh();
        }
      };

  FilteredController(
      DocumentController owner,
      SearchController search,
      Workers workers,
      UiDispatcher ui,
      ClipboardService clipboard,
      NotificationService notifications,
      ActivityIndicator activity,
      MemoryBudget budget) {
    super(owner.document(), workers, ui, clipboard, notifications, activity, budget);
    this.owner = owner;
    this.search = search;
    this.view = new FilteredView(search.shownResults(), owner.document().marks(), budget);
    applyContext();
    search.addListener(results);
  }

  @Override
  public DocumentController owner() {
    return owner;
  }

  @Override
  public DocumentViewState state() {
    return owner.state();
  }

  @Override
  public long sourceLine(long row) {
    return view.sourceLine(row);
  }

  @Override
  public long rowOf(long line) {
    return view.rowOf(line);
  }

  @Override
  public RowCursor rows() {
    return view.cursor();
  }

  @Override
  void caretMoved() {
    if (selection().isEmpty() || selection().anchor().line() == selection().caret().line()) {
      long line = view.sourceLine(selection().caret().line());
      if (line >= 0) {
        owner.showFromFiltered(line);
      }
    }
  }

  // Navigation history.

  /** Records the main view's line from before this view moved it, as a jump. */
  @Override
  public void rowChosen() {
    owner.commitDeparture();
  }

  @Override
  void jump(Runnable move) {
    move.run();
    rowChosen();
  }

  /**
   * Returns the mode.
   *
   * @return the mode
   */
  public Visibility visibility() {
    return view.visibility();
  }

  /**
   * Switches the mode, keeping the caret on its line or the nearest listed line after it.
   *
   * @param mode the mode
   */
  public void setVisibility(Visibility mode) {
    if (mode == view.visibility()) {
      return;
    }
    long line = view.sourceLine(selection().caret().line());
    view.setVisibility(mode);
    long row = line < 0 ? 0 : view.rowOf(line);
    viewport().setLineCount(view.size());
    placeCaret(Math.min(row, Math.max(0, view.size() - 1)));
    viewport().ensureVisible(selection().caret().line());
    refresh();
  }

  /**
   * Puts the caret on a line's row, or the next listed row, without moving the main view.
   *
   * @param line a 0-based line
   */
  void showNearest(long line) {
    if (isClosed() || view.size() == 0) {
      return;
    }
    long row = Math.min(view.rowOf(line), view.size() - 1);
    placeCaret(row);
    viewport().ensureVisible(row);
    changed();
  }

  /** Re-reads the rows after the results, the marks or the mode changed. */
  void refresh() {
    if (isClosed()) {
      return;
    }
    refreshRows(view.size());
    if (!buildContext()) {
      buildExtras();
    }
  }

  // Context lines.

  /** Sets the shown set's context counts on the view. */
  private void applyContext() {
    SearchOptions shown = search.shownOptions();
    view.setContext(
        shown.contextBefore(),
        shown.contextAfter(),
        new ContextRows.LineCount() {
          @Override
          public long get() {
            return document().index().lineCount();
          }
        });
  }

  /**
   * Re-reads the rows after the line count changed, when the last result's context may extend.
   *
   * @param lineCount the document's line count
   */
  void grew(long lineCount) {
    if (lineCount != seenLines) {
      seenLines = lineCount;
      if (view.context() != null) {
        refresh();
      }
    }
  }

  @Override
  public boolean contextRow(long line) {
    return view.isContext(line);
  }

  @Override
  public boolean startsGroup(long line) {
    return view.startsGroup(line);
  }

  /**
   * Starts extending the context rows when they are stale and no build runs.
   *
   * @return true while a build runs or is due, so the marks snapshot waits for it
   */
  private boolean buildContext() {
    if (extending != null) {
      return true;
    }
    if (!view.contextStale()) {
      return false;
    }
    final ContextRows from = view.context();
    final MemoryBudget budget = view.budget();
    final CancellationToken token = new CancellationToken();
    extending = token;
    boolean started =
        workers()
            .tryStart(
                Workers.Kind.SEARCH,
                new Runnable() {
                  @Override
                  public void run() {
                    extend(from, budget, token);
                  }
                });
    if (!started) {
      extending = null;
      return false;
    }
    return true;
  }

  /** Runs on a worker; posts back on every outcome so {@code extending} always clears. */
  private void extend(final ContextRows from, MemoryBudget budget, final CancellationToken token) {
    ContextRows rows = null;
    try {
      rows = from.extend(budget, token);
    } catch (CancellationException e) {
      rows = null;
    } finally {
      final ContextRows built = rows;
      ui().asyncExec(
              new Runnable() {
                @Override
                public void run() {
                  extended(from, built, token);
                }
              });
    }
  }

  private void extended(ContextRows from, ContextRows built, CancellationToken token) {
    if (extending == token) {
      extending = null;
    }
    if (built == null) {
      return;
    }
    boolean installed = view.installContext(built, from);
    if (isClosed()) {
      view.close();
      return;
    }
    if (installed && !built.complete() && !contextNoticeShown) {
      contextNoticeShown = true;
      notifications().showNotice(UiText.FILTERED_CONTEXT_LIMIT.text());
    }
    refresh();
  }

  /** Starts a marks snapshot when the installed one is stale and none is being built. */
  private void buildExtras() {
    if (building != null || owner.markingRunning() || !view.extrasStale()) {
      return;
    }
    final LineSet matches = view.rows();
    final MutableLineSet marks = view.marks();
    final CancellationToken token = new CancellationToken();
    building = token;
    boolean started =
        workers()
            .tryStart(
                Workers.Kind.SEARCH,
                new Runnable() {
                  @Override
                  public void run() {
                    build(matches, marks, token);
                  }
                });
    if (!started) {
      building = null;
    }
  }

  /** Runs on a worker; posts back on every outcome so {@code building} always clears. */
  private void build(LineSet matches, MutableLineSet marks, final CancellationToken token) {
    FilteredView.Extras extras = null;
    boolean admitted = document().enter();
    try {
      if (admitted) {
        extras = FilteredView.buildExtras(matches, marks, view.budget(), token);
      }
    } catch (CancellationException e) {
      extras = null;
    } finally {
      if (admitted) {
        document().exit();
      }
      final FilteredView.Extras built = extras;
      ui().asyncExec(
              new Runnable() {
                @Override
                public void run() {
                  built(built, token);
                }
              });
    }
  }

  private void built(FilteredView.Extras extras, CancellationToken token) {
    if (building == token) {
      building = null;
    }
    if (extras == null) {
      return;
    }
    if (isClosed() || !view.installExtras(extras)) {
      extras.lines().clear();
      if (!isClosed()) {
        buildExtras();
      }
      return;
    }
    if (!extras.complete() && !noticeShown) {
      noticeShown = true;
      notifications().showNotice(UiText.FILTERED_MARKS_LIMIT.text());
    }
    refresh();
  }

  /**
   * Reports whether the rows reflect the current marks: no snapshot is being built or due.
   *
   * @return true when settled
   */
  boolean settled() {
    return building == null && extending == null && !view.contextStale() && !view.extrasStale();
  }

  /** Detaches from the search and returns the view's reservation. */
  void detach() {
    search.removeListener(results);
    if (building != null) {
      building.cancel();
    }
    if (extending != null) {
      extending.cancel();
    }
    view.close();
    detachView();
  }
}
