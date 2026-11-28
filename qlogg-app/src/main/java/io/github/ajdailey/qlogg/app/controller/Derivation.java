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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
  private String column = "";

  /**
   * Creates a definition over rows a finished search found.
   *
   * @param tabs the parent links; growth pokes the tab's children through them
   * @param parent the parent tab
   * @param ran the search and where it ended, over a copy of its set, which the rows own
   * @param engine runs growth
   * @param ui the UI-thread dispatcher
   */
  Derivation(
      DerivedTabs tabs,
      DocumentController parent,
      RanSearch ran,
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
            ran.lines,
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
    RanSearch ran = RanSearch.unrun(text, options, query, new MutableLineSet(budget));
    Derivation again = new Derivation(tabs, newParent, ran, engine, ui);
    again.column = column;
    return again;
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

  /** Returns the search's toggles. */
  SearchOptions options() {
    return options;
  }

  /** Returns the root lines the search covers, or null for every line. */
  LineRange scope() {
    return query.scope();
  }

  /** Returns the search's column, as the session keeps it; empty for none. */
  String column() {
    return column;
  }

  /**
   * Sets the search's column, as a restored session gives it.
   *
   * @param name the column, or empty for none
   */
  void setColumn(String name) {
    column = name;
  }

  /** Reports whether growth has ended: a limit stopped it, or the scope ends before the file. */
  boolean stopped() {
    return limit != SearchLimit.NONE
        || (query.scope() != null && query.scope().end() != Long.MAX_VALUE);
  }

  @Override
  public String title() {
    return UiText.FILTER_TAB_TITLE.format(
        RotatedTabs.title(parent.document()), ResultTabs.shortText(text, options));
  }

  /** Lists the searches from the root down, then whether the tab grows. */
  @Override
  public String details(ZoneId zone) {
    List<String> lines = new ArrayList<String>();
    Derivation link = this;
    while (link != null) {
      lines.add(0, UiText.FILTER_TAB_SEARCH.format(ResultTabs.label(link.text, link.options)));
      link = tabs.derivation(link.parent);
    }
    if (stopped()) {
      lines.add(UiText.FILTER_TAB_NO_GROWTH.text());
    }
    return String.join("\n", lines);
  }

  @Override
  public String note() {
    return UiText.FILTER_TAB_ROWS.format(rows.lines().size());
  }

  @Override
  public long rowCount(long lineCount) {
    return rows.lines().size();
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
    long end = searchEnd(index);
    long last = rows.searchedEnd() - 1;
    long from = searchFrom();
    if (from >= end || !document.enter()) {
      return;
    }
    rows.growthStarted(from);
    source.readerStarted();
    SearchQuery growth =
        RowRestriction.restricted(
            new SearchQuery(
                query.expression(),
                query.caseSensitive(),
                query.wholeWord(),
                query.inverse(),
                new LineRange(from, end),
                query.hideAnsi(),
                query.rangeEnd(),
                from == last && rangeOpen,
                null,
                parent.columns().search()),
            source.members());
    Growth events = new Growth(owner, rows.lines(), source, end, bytes);
    try {
      running = engine.search(document, index, growth, rows.lines(), events);
    } catch (RejectedExecutionException e) {
      document.exit();
      rows.growthEnded(-1, 0);
      source.readerEnded();
    }
  }

  /**
   * A growth runs, or the parent settled lines this tab or one above has not searched. A wanted
   * growth alone does not count: a flush while a child reads the rows sets it with nothing to
   * search.
   */
  @Override
  public boolean deriving() {
    if (owner == null || owner.isClosed() || limit != SearchLimit.NONE) {
      return false;
    }
    if (running != null) {
      return true;
    }
    Derivation above = tabs.derivation(parent);
    if (above != null && above.deriving()) {
      return true;
    }
    LineIndex index = owner.document().index();
    return parent.rowMap().settledBytes(index) > rows.searchedBytes()
        && searchFrom() < searchEnd(index);
  }

  /** Returns the first root line a growth searches. */
  private long searchFrom() {
    long first = query.scope() == null ? 0 : query.scope().first();
    long last = rows.searchedEnd() - 1;
    return Math.max(first, query.range() ? last : parent.columns().recordStart(last));
  }

  /** Returns the root line a growth searches below: the parent's settled lines, within scope. */
  private long searchEnd(LineIndex index) {
    long end = Math.min(index.lineCount(), parent.rowMap().settledLines());
    return query.scope() == null ? end : Math.min(end, query.scope().end());
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
