package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.RecordLines;

/**
 * The column view's row map for a multi-line format: one row per record, shown at its first line.
 * Rows map to lines through {@code rank} and {@code select} on the {@link RecordLines} set, or on
 * its complement for a continuation set (a binary search over {@code rank}). Lines from {@link
 * RecordLines#end} on, not yet classified, are one row each. Holds one snapshot of the records;
 * thread-safe when its set is.
 */
public final class RecordRows {
  private final RecordLines records;

  /**
   * Creates the map.
   *
   * @param records the records of one moment of a {@link
   *     io.github.ajdailey.qlogg.core.search.RecordScan}
   */
  public RecordRows(RecordLines records) {
    this.records = records;
  }

  /**
   * Returns the records this map reads.
   *
   * @return the snapshot
   */
  public RecordLines records() {
    return records;
  }

  /**
   * Counts the rows of a document.
   *
   * @param lineCount lines in the document
   * @return the records starting below {@code lineCount}
   */
  public long rowCount(long lineCount) {
    return startsBelow(lineCount);
  }

  /**
   * Returns the row showing a line: its record's row.
   *
   * @param line a 0-based line
   * @return the 0-based row
   */
  public long rowOf(long line) {
    return startsBelow(line + 1) - 1;
  }

  /**
   * Returns the first line of a row's record.
   *
   * @param row a 0-based row
   * @return the 0-based line
   */
  public long lineAt(long row) {
    LineSet set = records.lines();
    long end = records.end();
    long classified = startsBelow(end);
    if (row >= classified) {
      return end + (row - classified);
    }
    if (!records.continuations()) {
      long below = set.rank(1) > 0 ? 0 : 1;
      return row == 0 ? 0 : set.select(row - below);
    }
    long low = row;
    long high = end - 1;
    while (low < high) {
      long mid = (low + high) >>> 1;
      if (mid + 1 - set.rank(mid + 1) > row) {
        high = mid;
      } else {
        low = mid + 1;
      }
    }
    return low;
  }

  /**
   * Returns the line after the last line of the record holding a line.
   *
   * @param line a 0-based line
   * @param lineCount lines in the document
   * @return the next record's first line, at most {@code lineCount}
   */
  public long recordEnd(long line, long lineCount) {
    return Math.min(records.nextStart(line + 1), Math.max(line + 1, lineCount));
  }

  /**
   * Returns a walk over the records' first lines below a line count.
   *
   * @param lineCount lines in the document
   * @return the cursor, never {@link RowCursor#contiguous}
   */
  public RowCursor cursor(final long lineCount) {
    return new RowCursor() {
      @Override
      public long next(long line) {
        long next = records.nextStart(Math.max(0, line + 1));
        return next < lineCount ? next : -1;
      }

      @Override
      public long previous(long line) {
        long previous = Math.min(line, lineCount) - 1;
        return previous < 0 ? -1 : records.startOf(previous);
      }

      @Override
      public boolean contiguous() {
        return false;
      }
    };
  }

  /** Counts the record starts below a line. */
  private long startsBelow(long line) {
    if (line <= 0) {
      return 0;
    }
    long end = records.end();
    long classified = Math.min(line, end);
    long past = Math.max(0, line - end);
    LineSet set = records.lines();
    if (records.continuations()) {
      return classified - set.rank(classified) + past;
    }
    long starts = set.rank(classified);
    if (classified > 0 && !set.contains(0)) {
      starts++;
    }
    return starts + past;
  }
}
