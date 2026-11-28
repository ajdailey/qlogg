package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.search.FilterPatterns;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.columns.TimeCell;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.ColumnLayout;
import io.github.ajdailey.qlogg.core.model.ColumnRead;
import io.github.ajdailey.qlogg.core.model.RecordRows;
import io.github.ajdailey.qlogg.core.search.ColumnSearch;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.RecordLines;
import io.github.ajdailey.qlogg.core.search.SortedLines;
import io.github.ajdailey.qlogg.core.time.TimestampFormat;

/**
 * One tab's column state: the format it shows or toggles to ({@link #schema}), whether column view
 * shows ({@link #shown}), the records of a multi-line format and the columns its searches test.
 * Confined to the UI thread.
 *
 * <p>Records: a file tab with a {@link ColumnFormat#multiLine} format runs a {@link RecordScanner}
 * after each indexing pass, in text view too, so Toggle Columns and the filter tabs over it find
 * the records read. A filter tab reads its parent's records. In a file tab's column view on such a
 * format a row is a record ({@link #rows}); each flush takes the scan's newest snapshot and keeps
 * the top line and the selection on their lines.
 *
 * <p>Search: in column view a search tests the format's columns ({@link #search}); on a multi-line
 * format the record is the search unit. Text view searches lines and has no columns.
 *
 * <p>Rows: in column view the views read each row as the record holding its line, composed into the
 * cells of {@link #layout} ({@link #read}), so a filter tab's continuation line shows its record. A
 * new format takes its stored layout ({@link ColumnSizing}); a view's read fits the cells waiting
 * for a width ({@link #fitted}).
 */
public final class ColumnController {
  private static final LineSet NO_HEADERS = new SortedLines(new long[0]);
  private static final RecordLines NO_RECORDS = RecordLines.starts(NO_HEADERS, 0);

  private final DocumentController owner;
  private final ColumnSizing sizing;
  private ColumnSchema schema;
  private boolean shown;
  private RecordScanner scanner;
  private RecordRows rows;
  private ColumnLayout layout;
  private RecordLines readRecords;
  private ColumnRead lastRead;

  ColumnController(DocumentController owner) {
    this.owner = owner;
    this.sizing = new ColumnSizing(this, owner);
  }

  /**
   * Returns the layout edits of the column header.
   *
   * @return the tab's sizing
   */
  public ColumnSizing sizing() {
    return sizing;
  }

  /**
   * Returns the format the tab shows in column view, or switches to with Toggle Columns.
   *
   * @return the schema, or null when the tab has no column format
   */
  public ColumnSchema schema() {
    return schema;
  }

  /**
   * Reports whether column view shows.
   *
   * @return true in column view
   */
  public boolean shown() {
    return shown;
  }

  /**
   * Sets the format and the view; the rows change with the top line kept.
   *
   * @param next the format, or null for none
   * @param show true for column view; ignored without a format
   */
  void configure(ColumnSchema next, boolean show) {
    if (scanner != null && !sameRecords(scanner.schema(), next)) {
      scanner.detach();
      scanner = null;
    }
    if (next != schema) {
      layout = next == null ? null : sizing.build(next, null);
      if (next != null) {
        sizing.shown(next);
      }
    }
    schema = next;
    if (scanner == null && next != null && next.format().multiLine() && owner.ownsDocument()) {
      scanner = new RecordScanner(owner, next);
    }
    boolean view = show && next != null;
    if (view || shown) {
      apply(view);
    }
  }

  /**
   * True when two schemas split records alike: the same format, delimiter and header, which decides
   * a rotated set's repeated headers.
   */
  private static boolean sameRecords(ColumnSchema a, ColumnSchema b) {
    return b != null
        && a.format() == b.format()
        && a.delimiter() == b.delimiter()
        && a.header() == b.header();
  }

  /**
   * Starts the record scan of a format the user chose under the busy indicator. An indexing
   * document starts it when indexing ends; a filter tab reads its parent's.
   */
  void scanChosen() {
    if (scanner != null && !owner.state().indexing()) {
      scanner.request(true);
    }
  }

  /**
   * Shows column view or text view; the rows change with the top line kept.
   *
   * @param show true for column view; ignored without a format
   */
  void setShown(boolean show) {
    boolean view = show && schema != null;
    if (view != shown) {
      apply(view);
    }
  }

  private void apply(final boolean view) {
    final boolean hideHeader = schema != null && schema.header();
    owner.changeRows(
        new Runnable() {
          @Override
          public void run() {
            shown = view;
            rows =
                recordView() && owner.ownsDocument() ? new RecordRows(records(), hideHeader) : null;
          }
        });
    owner.host().documentStateChanged();
  }

  /** True in column view on a multi-line format, where a record is the unit. */
  private boolean recordView() {
    return shown && schema != null && schema.format().multiLine();
  }

  /**
   * Returns the cells of column view.
   *
   * @return the layout, or null without a format
   */
  public ColumnLayout layout() {
    return layout;
  }

  /**
   * Sets the cells of column view; the rows are read again.
   *
   * @param next a layout of the tab's schema
   * @throws IllegalArgumentException for another schema's layout
   */
  public void setLayout(final ColumnLayout next) {
    if (schema == null || next.schema() != schema) {
      throw new IllegalArgumentException("a layout of the tab's format");
    }
    owner.changeRows(
        new Runnable() {
          @Override
          public void run() {
            layout = next;
          }
        });
  }

  /**
   * Returns the pattern Save as Filter stores for a search: in column view with a search bar
   * column, one term with that column's prefix ({@link FilterPatterns#saved}).
   *
   * @param text the search text
   * @param options the search's toggles
   * @return the pattern
   */
  String filterPattern(String text, SearchOptions options) {
    int index = shown && schema != null ? schema.columnIndex(options.column()) : -1;
    return index < 0
        ? text
        : FilterPatterns.saved(
            text, options.combining(), schema.columns().get(index).name(), index + 1);
  }

  /**
   * Takes the widths a view's read fitted, while the layout is still the one it fitted.
   *
   * @param from the layout the read started from
   * @param to the fitted layout
   */
  void fitted(ColumnLayout from, ColumnLayout to) {
    if (layout == from && to.schema() == schema) {
      setLayout(to);
    }
  }

  /**
   * Returns what the views read in column view: the cells, the time display, the records and the
   * repeated headers. The same instance returns while none of them changed.
   *
   * @return the read, or null in text view
   */
  public ColumnRead read() {
    if (!shown || schema == null) {
      return null;
    }
    RecordLines records = null;
    if (schema.format().multiLine()) {
      RecordLines newest = rows != null ? rows.records() : records();
      if (readRecords == null
          || newest.end() != readRecords.end()
          || newest.lines() != readRecords.lines()) {
        readRecords = newest;
      }
      records = readRecords;
    }
    TimeController time = owner.time();
    ColumnRead next =
        new ColumnRead(
            layout,
            time.context(),
            new TimeCell(time.displayZone(), time.offset()),
            records,
            headers());
    if (!next.equals(lastRead)) {
      lastRead = next;
    }
    return lastRead;
  }

  /** The timestamp format changed: a {@link ColumnFormat#TIME} format takes the new one. */
  void timeFormatChanged() {
    if (schema == null || schema.format() != ColumnFormat.TIME) {
      return;
    }
    TimestampFormat format = owner.time().format();
    configure(format == null ? null : ColumnSchema.timestamp(format), shown);
  }

  /**
   * Returns the row map of a file tab in column view on a multi-line format.
   *
   * @return the rows of one record snapshot, or null when a row is a line
   */
  RecordRows rows() {
    return rows;
  }

  /**
   * Returns the newest records of the tab's format.
   *
   * @return the scan's snapshot, the parent's for a filter tab, or no records (each line its own)
   *     before a scan or without a multi-line format
   */
  RecordLines records() {
    if (scanner != null) {
      return scanner.snapshot();
    }
    DocumentController parent = owner.parentTab();
    if (parent != null && schema != null && parent.columns().scanning(schema)) {
      return parent.columns().records();
    }
    return NO_RECORDS;
  }

  /** True when this tab's records are those of a schema's format. */
  private boolean scanning(ColumnSchema other) {
    if (scanner != null) {
      return sameRecords(scanner.schema(), other);
    }
    DocumentController parent = owner.parentTab();
    return parent != null && parent.columns().scanning(other);
  }

  /**
   * Returns the first line of the record holding a line, in column view on a multi-line format.
   *
   * @param line a 0-based line
   * @return the record's first line, or {@code line} when a row is a line
   */
  long recordStart(long line) {
    return recordView() ? records().startOf(Math.max(0, line)) : line;
  }

  /**
   * Reports whether a search started now finds the records of every complete line of an index: true
   * outside column view on a multi-line format, and once the scan reached the index's last complete
   * line or stopped at a refusal.
   *
   * @param index the document's current index
   * @return false while the scan is behind the index
   */
  boolean recordsSettled(LineIndex index) {
    return !recordView() || scanSettled(index);
  }

  /** True when the scan this tab reads covers an index or stopped at a refusal, or none applies. */
  private boolean scanSettled(LineIndex index) {
    if (scanner != null) {
      return scanner.settled(index);
    }
    DocumentController parent = owner.parentTab();
    return parent == null
        || schema == null
        || !parent.columns().scanning(schema)
        || parent.columns().scanSettled(index);
  }

  /**
   * Returns the columns a search started now tests.
   *
   * @return the columns with the newest records, or null in text view
   */
  ColumnSearch search() {
    if (!shown || schema == null) {
      return null;
    }
    RecordLines records = schema.format().multiLine() ? records() : null;
    return new ColumnSearch(schema, owner.time().context(), records, headers());
  }

  private LineSet headers() {
    if (scanner != null) {
      return scanner.headers();
    }
    DocumentController parent = owner.parentTab();
    return parent != null ? parent.columns().headers() : NO_HEADERS;
  }

  /**
   * Applies a flush: after indexing, extends the record scan; in record rows, takes the scan's
   * newest snapshot.
   *
   * @param indexing true while the document indexes
   */
  void flushed(boolean indexing) {
    if (scanner != null && !indexing) {
      scanner.request();
    }
    if (rows == null) {
      return;
    }
    final RecordLines next = records();
    final boolean hideHeader = rows.hidesHeader();
    if (next.end() == rows.records().end() && next.lines() == rows.records().lines()) {
      return;
    }
    owner.changeRows(
        new Runnable() {
          @Override
          public void run() {
            rows = new RecordRows(next, hideHeader);
          }
        });
  }

  /** Stops the scan and empties its records. */
  void detach() {
    if (scanner != null) {
      scanner.detach();
      scanner = null;
    }
    rows = null;
  }
}
