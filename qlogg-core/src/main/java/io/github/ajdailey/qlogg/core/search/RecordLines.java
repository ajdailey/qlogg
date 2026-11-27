package io.github.ajdailey.qlogg.core.search;

import java.util.Arrays;

/**
 * Where the records of a multi-line column format start, over a {@link RecordScan}'s line set: the
 * set holds the record starts ({@link #starts(LineSet, long)}) or the continuation lines ({@link
 * #continuations(LineSet, long)}). Lines from {@link #end} on are not classified and each is a
 * record of its own; line 0 always starts one. Entries of the set at or past {@code end} are not
 * read, so a scan may keep adding to the set while this view is in use. Thread-safe when the set
 * is.
 */
public final class RecordLines {
  private final LineSet lines;
  private final boolean continuations;
  private final long end;

  private RecordLines(LineSet lines, boolean continuations, long end) {
    this.lines = lines;
    this.continuations = continuations;
    this.end = end;
  }

  /**
   * Returns records from a set of their first lines.
   *
   * @param starts the first line of every record below {@code end}
   * @param end the first line not classified
   * @return the records
   */
  public static RecordLines starts(LineSet starts, long end) {
    return new RecordLines(starts, false, end);
  }

  /**
   * Returns records from a set of the lines that continue a record.
   *
   * @param continuations every line below {@code end} that is not a record's first line
   * @param end the first line not classified
   * @return the records
   */
  public static RecordLines continuations(LineSet continuations, long end) {
    return new RecordLines(continuations, true, end);
  }

  /**
   * Returns the set.
   *
   * @return record starts, or continuation lines when {@link #continuations()}
   */
  public LineSet lines() {
    return lines;
  }

  /**
   * Reports what the set holds.
   *
   * @return true for continuation lines, false for record starts
   */
  public boolean continuations() {
    return continuations;
  }

  /**
   * Returns the first line not classified.
   *
   * @return a line count
   */
  public long end() {
    return end;
  }

  /**
   * Reports whether a line starts a record.
   *
   * @param line at least 0
   * @return true for line 0, a line at or past {@link #end}, and a record's first line
   */
  public boolean starts(long line) {
    if (line == 0 || line >= end) {
      return true;
    }
    return lines.contains(line) != continuations;
  }

  /**
   * Returns the first record start at or after a line.
   *
   * @param line at least 0
   * @return the line
   */
  public long nextStart(long line) {
    if (line == 0 || line >= end) {
      return line;
    }
    if (!continuations) {
      long next = lines.ceiling(line);
      return next < 0 || next >= end ? end : next;
    }
    long at = line;
    while (at < end && lines.contains(at)) {
      at++;
    }
    return at;
  }

  /**
   * Returns the first line of the record holding a line.
   *
   * @param line at least 0
   * @return the line
   */
  public long startOf(long line) {
    if (line >= end) {
      return line;
    }
    if (!continuations) {
      return Math.max(0, lines.floor(line));
    }
    long at = line;
    while (at > 0 && lines.contains(at)) {
      at--;
    }
    return at;
  }

  /**
   * Copies the set's lines in {@code [from, to)} for one worker's walk of a chunk.
   *
   * @param from a record's first line
   * @param to the first line after the chunk
   * @return the marks
   */
  RecordMarks marks(long from, long to) {
    long stop = Math.min(to, end);
    long[] found = new long[16];
    int count = 0;
    long line = from + 1;
    while (line < stop) {
      long next = lines.ceiling(line);
      if (next < 0 || next >= stop) {
        break;
      }
      if (count == found.length) {
        found = Arrays.copyOf(found, count * 2);
      }
      found[count++] = next;
      line = next + 1;
    }
    return new RecordMarks(found, count, continuations, end, to);
  }
}
