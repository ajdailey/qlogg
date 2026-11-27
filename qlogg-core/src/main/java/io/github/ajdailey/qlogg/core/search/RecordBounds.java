package io.github.ajdailey.qlogg.core.search;

/**
 * Where a {@link RecordScan} record starts whatever the content: at a forced line (a rotated set's
 * member start), and at a line {@link RecordScan#MAX_RECORD_LINES} lines or {@link
 * RecordScan#MAX_RECORD_BYTES} bytes past its record's first line. Not thread-safe.
 */
final class RecordBounds {
  private long recordLine;
  private long recordStart;
  private LineSet forced;
  private long nextForced = -1;

  /** Sets the forced starts of a pass, or null for none, and rewinds the cursor over them. */
  void force(LineSet starts) {
    forced = starts;
    nextForced = -1;
  }

  /** Records that a record starts at a line. */
  void start(long line, long offset) {
    recordLine = line;
    recordStart = offset;
  }

  /**
   * True when a line may join the record before it: within both bounds and not forced. Lines are
   * asked in ascending order within a pass.
   */
  boolean joins(long line, long offset) {
    return line - recordLine < RecordScan.MAX_RECORD_LINES
        && offset - recordStart < RecordScan.MAX_RECORD_BYTES
        && !forced(line);
  }

  private boolean forced(long line) {
    if (forced == null) {
      return false;
    }
    if (nextForced < line) {
      long next = forced.ceiling(line);
      nextForced = next < 0 ? Long.MAX_VALUE : next;
    }
    return nextForced == line;
  }
}
