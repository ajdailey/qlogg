package io.github.ajdailey.qlogg.core.search;

/**
 * The first lines of the records holding at least one line of a set, computed on each call from the
 * set and the {@link RecordLines}, so a record search restricted to it tests every record a member
 * line belongs to. {@link #ceiling}, {@link #floor} and {@link #contains} cost two set lookups and
 * two record-bound lookups; {@link #size}, {@link #rank} and {@link #select} walk the records from
 * line 0. Thread-safe when the set and the records are.
 */
public final class RecordStarts implements LineSet {
  private final LineSet lines;
  private final RecordLines records;

  /**
   * Creates the view.
   *
   * @param lines the member lines
   * @param records the record bounds
   */
  public RecordStarts(LineSet lines, RecordLines records) {
    this.lines = lines;
    this.records = records;
  }

  @Override
  public long ceiling(long line) {
    long from = Math.max(0, line);
    long member = lines.ceiling(from);
    while (member >= 0) {
      long start = records.startOf(member);
      if (start >= from) {
        return start;
      }
      // The member's record starts before the line: the next record holding a member.
      member = lines.ceiling(records.nextStart(member + 1));
    }
    return -1;
  }

  @Override
  public long floor(long line) {
    if (line < 0) {
      return -1;
    }
    long start = records.startOf(line);
    long member = lines.ceiling(start);
    if (member >= 0 && member < records.nextStart(start + 1)) {
      return start;
    }
    long before = start == 0 ? -1 : lines.floor(start - 1);
    return before < 0 ? -1 : records.startOf(before);
  }

  @Override
  public boolean contains(long line) {
    return line >= 0 && records.starts(line) && ceiling(line) == line;
  }

  @Override
  public long size() {
    return rank(Long.MAX_VALUE);
  }

  @Override
  public long rank(long line) {
    long count = 0;
    long start = ceiling(0);
    while (start >= 0 && start < line) {
      count++;
      start = ceiling(start + 1);
    }
    return count;
  }

  @Override
  public long select(long index) {
    long start = index < 0 ? -1 : ceiling(0);
    for (long i = 0; i < index && start >= 0; i++) {
      start = ceiling(start + 1);
    }
    if (start < 0) {
      throw new IndexOutOfBoundsException("index " + index);
    }
    return start;
  }
}
