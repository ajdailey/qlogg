package io.github.ajdailey.qlogg.core.search;

/**
 * The record bounds of one chunk, copied from {@link RecordLines} by the thread that cuts or reads
 * the chunk, so a worker finds each record's end without reading the shared set. Queries go
 * forward: each {@link #end} call takes a start after the previous call's. Not thread-safe.
 */
final class RecordMarks {
  private final long[] lines;
  private final int count;
  private final boolean continuations;
  private final long classified;
  private final long stop;
  private int at;

  /**
   * Creates marks.
   *
   * @param lines the set's lines inside the chunk, ascending
   * @param count how many of {@code lines} are used
   * @param continuations true when the lines continue records, false when they start them
   * @param classified the records' first unclassified line
   * @param stop the first line after the chunk
   */
  RecordMarks(long[] lines, int count, boolean continuations, long classified, long stop) {
    this.lines = lines;
    this.count = count;
    this.continuations = continuations;
    this.classified = classified;
    this.stop = stop;
  }

  /**
   * Returns the end of the record starting at a line.
   *
   * @param start a record's first line inside the chunk
   * @return the first line after the record, at most the chunk's end
   */
  long end(long start) {
    while (at < count && lines[at] <= start) {
      at++;
    }
    long next;
    if (continuations) {
      next = start + 1;
      while (at < count && lines[at] == next) {
        at++;
        next++;
      }
    } else {
      next = at < count ? lines[at] : Long.MAX_VALUE;
      next = Math.min(next, Math.max(start + 1, classified));
    }
    return Math.min(next, stop);
  }
}
