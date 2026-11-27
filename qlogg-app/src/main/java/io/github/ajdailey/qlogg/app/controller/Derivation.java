package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.search.LineRange;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchLimit;
import io.github.ajdailey.qlogg.core.search.SearchListener;
import io.github.ajdailey.qlogg.core.search.SearchQuery;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A filter tab's definition and growth: its parent tab and one search, whose matching root lines
 * are the tab's {@link DerivedRows}. Confined to the UI thread.
 *
 * <p>Growth searches the root lines from the last searched line ({@code searchedEnd - 1}, which may
 * have grown, or that line's record in the parent's record view) to the parent's {@link
 * RowMap#settledLines}, restricted to the parent's members, with the parent's columns ({@link
 * ColumnController#search}), into the rows. It starts when the parent's {@link RowMap#settledBytes}
 * passed the rows' and neither a growth nor a reader holds the rows; while it runs it holds the
 * parent as a reader. When it ends, the tab's children grow. It runs on the engine's {@code
 * qlogg-search-} threads inside a document entry, shows no progress and holds no activity. A search
 * stopped by a limit, or a scope ending before the end of the file, ends growth: the first case
 * shows a notice.
 */
final class Derivation implements DocumentRole {
  private static final Logger LOG = LoggerFactory.getLogger(Derivation.class);

  private final DerivedTabs tabs;
  private final SearchEngine engine;
  private final UiDispatcher ui;
  private final String text;
  private final SearchOptions options;
  private final SearchQuery query;
  private final DerivedRows rows;
  private final AtomicBoolean detached = new AtomicBoolean();
  private DocumentController parent;
  private DocumentController owner;
  private SearchLimit limit;
  private boolean rangeOpen;
  private OperationHandle running;
  private boolean wanted;

  /**
   * Creates a definition over rows a finished search found.
   *
   * @param tabs the parent links; growth pokes the tab's children through them
   * @param parent the parent tab
   * @param ran the search and where it ended
   * @param lines a copy of its set, which the rows own
   * @param engine runs growth
   * @param ui the UI-thread dispatcher
   */
  Derivation(
      DerivedTabs tabs,
      DocumentController parent,
      SearchController.Ran ran,
      MutableLineSet lines,
      SearchEngine engine,
      UiDispatcher ui) {
    this.tabs = tabs;
    this.parent = parent;
    this.text = ran.text;
    this.options = ran.options;
    this.query = ran.query;
    this.limit = ran.limit;
    this.rangeOpen = ran.rangeOpen;
    this.engine = engine;
    this.ui = ui;
    this.rows =
        new DerivedRows(
            lines,
            ran.end,
            ran.bytes,
            new Runnable() {
              @Override
              public void run() {
                readersEnded();
              }
            });
  }

  /**
   * Returns the same definition over a new parent with empty rows, which growth fills.
   *
   * @param newParent the parent's replacement
   * @param budget charged for the new rows
   * @return the definition
   */
  Derivation rederived(DocumentController newParent, MemoryBudget budget) {
    SearchController.Ran ran =
        new SearchController.Ran(text, options, query, null, SearchLimit.NONE, 0, -1, false);
    return new Derivation(tabs, newParent, ran, new MutableLineSet(budget), engine, ui);
  }

  /** Binds the tab these rows belong to; its controller calls this once, while it is built. */
  void attach(DocumentController tab) {
    owner = tab;
  }

  /** Returns the tab, or null before its controller is built. */
  DocumentController owner() {
    return owner;
  }

  /** Returns the rows. */
  DerivedRows rows() {
    return rows;
  }

  @Override
  public DocumentController parent() {
    return parent;
  }

  /**
   * Moves the tab under another parent, as when its parent closes. A growth reading the closing
   * parent's rows is canceled and runs again over the new parent's.
   *
   * @param next the closed parent's own parent
   */
  void reparent(DocumentController next) {
    parent = next;
    if (running != null) {
      wanted = true;
      running.cancel();
    }
  }

  /** Returns the search text. */
  String text() {
    return text;
  }

  /** Returns what stopped growth. */
  SearchLimit limit() {
    return limit;
  }

  @Override
  public void start(DocumentController tab) {
    grow();
  }

  @Override
  public void changed(DocumentController tab, FileChange change) {
    // The root tab reopens the file and shows the notice; this tab is derived again.
  }

  @Override
  public void flushed() {
    grow();
  }

  @Override
  public boolean owns() {
    return false;
  }

  @Override
  public void detached() {
    detached.set(true);
    if (running != null) {
      running.cancel();
    }
  }

  @Override
  public void close(DocumentController tab) {
    // The root tab closes the document.
  }

  /**
   * Runs a growth when the parent settled more lines, or marks it wanted while the rows are held.
   */
  void grow() {
    if (owner == null || owner.isClosed() || limit != SearchLimit.NONE) {
      return;
    }
    if (rows.busy()) {
      wanted = true;
      return;
    }
    wanted = false;
    LogDocument document = owner.document();
    LineIndex index = document.index();
    RowMap source = parent.rowMap();
    long bytes = source.settledBytes(index);
    if (bytes <= rows.searchedBytes()) {
      return;
    }
    long first = query.scope() == null ? 0 : query.scope().first();
    long end = Math.min(index.lineCount(), source.settledLines());
    if (query.scope() != null) {
      end = Math.min(end, query.scope().end());
    }
    long last = rows.searchedEnd() - 1;
    long from = Math.max(first, query.range() ? last : parent.columns().recordStart(last));
    if (from >= end || !document.enter()) {
      return;
    }
    rows.growthStarted(from);
    source.readerStarted();
    SearchQuery growth =
        new SearchQuery(
            query.expression(),
            query.caseSensitive(),
            query.wholeWord(),
            query.inverse(),
            new LineRange(from, end),
            query.hideAnsi(),
            query.rangeEnd(),
            from == last && rangeOpen,
            source.members(),
            parent.columns().search());
    Growth events = new Growth(owner, rows.lines(), source, end, bytes);
    try {
      running = engine.search(document, index, growth, rows.lines(), events);
    } catch (RejectedExecutionException e) {
      document.exit();
      rows.growthEnded(-1, 0);
      source.readerEnded();
    }
  }

  private void readersEnded() {
    if (wanted) {
      grow();
    }
  }

  /** Applies a growth's end on the UI thread. */
  private void ended(Growth growth, OperationOutcome outcome, SearchLimit stop, Exception failure) {
    running = null;
    growth.source.readerEnded();
    boolean completed = outcome == OperationOutcome.COMPLETED && stop == SearchLimit.NONE;
    rows.growthEnded(completed ? growth.end : -1, growth.bytes);
    DocumentController tab = growth.tab;
    if (tab.isClosed()) {
      return;
    }
    if (completed) {
      rangeOpen = growth.open;
    } else if (outcome == OperationOutcome.COMPLETED) {
      limit = stop;
      UiText notice =
          stop == SearchLimit.MEMORY
              ? UiText.NOTICE_FILTER_TAB_MEMORY_LIMIT
              : UiText.NOTICE_FILTER_TAB_RESULT_LIMIT;
      tab.notifications().showNotice(notice.format(rows.lines().size()));
    } else if (outcome == OperationOutcome.FAILED) {
      LOG.warn(
          "Filter tab growth in {} failed: {}",
          LogText.oneLine(tab.document().path()),
          failure == null ? null : LogText.oneLine(failure.getMessage()));
    }
    tab.rowsChanged();
    tabs.grew(tab);
    if (wanted) {
      grow();
    }
  }

  /** One growth's events on the search thread; it holds a document entry until it finishes. */
  private final class Growth implements SearchListener {
    private final DocumentController tab;
    private final LogDocument document;
    private final MutableLineSet lines;
    private final RowMap source;
    private final long end;
    private final long bytes;
    private volatile boolean open;

    Growth(DocumentController tab, MutableLineSet lines, RowMap source, long end, long bytes) {
      this.tab = tab;
      this.document = tab.document();
      this.lines = lines;
      this.source = source;
      this.end = end;
      this.bytes = bytes;
    }

    @Override
    public void progressed(long searchedLines, long totalLines, long matchCount) {
      // Growth shows no progress.
    }

    @Override
    public void rangeOpenBeforeLast(boolean before) {
      open = before;
    }

    @Override
    public void finished(
        final OperationOutcome outcome,
        final SearchLimit stop,
        long skippedLines,
        final Exception failure) {
      try {
        if (detached.get()) {
          lines.clear();
        }
        ui.asyncExec(
            new Runnable() {
              @Override
              public void run() {
                ended(Growth.this, outcome, stop, failure);
              }
            });
      } finally {
        document.exit();
      }
    }
  }
}
