package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;

/**
 * Finds the next or previous mark among a view's rows. Marks belong to the root document, so a
 * filter tab steps over marks that are not its rows: the search alternates {@code ceiling} (or
 * {@code floor}) on the marks and on the rows until both return the same line, so each pair of
 * lookups skips a run of marks that are not rows. A step costs about 50 ns per mark it skips.
 */
final class MarkSteps {
  /**
   * Marks above which a step in a filter tab runs on a worker ({@link MarkStepper}): a step that
   * skips this many marks takes under 4 ms on the UI thread (measured, median of 9).
   */
  static final long WORKER_MARKS = 75_000;

  /** Lookup pairs between token checks. */
  static final int CHECK_STEPS = 4096;

  private MarkSteps() {}

  /**
   * Returns the first mark at or after a line that is a row.
   *
   * @param marks the document's marks
   * @param rows the rows' lines, or null for every line
   * @param from a 0-based line
   * @return the line, or -1
   */
  static long next(LineSet marks, LineSet rows, long from) {
    return step(marks, rows, from, true, null);
  }

  /**
   * Returns the last mark at or before a line that is a row.
   *
   * @param marks the document's marks
   * @param rows the rows' lines, or null for every line
   * @param from a 0-based line
   * @return the line, or -1
   */
  static long previous(LineSet marks, LineSet rows, long from) {
    return step(marks, rows, from, false, null);
  }

  /**
   * Returns {@link #next} or {@link #previous} from one state of the marks and the rows: the step
   * runs again while either set's {@code version} changed during it. Runs on a worker.
   *
   * @param marks the document's marks
   * @param rows the rows' lines, or null for every line
   * @param from a 0-based line
   * @param forward true for {@link #next}
   * @param token checked every {@value #CHECK_STEPS} lookup pairs
   * @return the line, or -1
   * @throws java.util.concurrent.CancellationException once the token is canceled
   */
  @WorkerThread
  static long stable(
      MutableLineSet marks, LineSet rows, long from, boolean forward, CancellationToken token) {
    while (true) {
      long version = marks.version();
      long rowVersion = version(rows);
      long line = step(marks, rows, from, forward, token);
      if (marks.version() == version && version(rows) == rowVersion) {
        return line;
      }
    }
  }

  private static long version(LineSet rows) {
    return rows instanceof MutableLineSet set ? set.version() : 0;
  }

  private static long step(
      LineSet marks, LineSet rows, long from, boolean forward, CancellationToken token) {
    long line = forward ? marks.ceiling(from) : marks.floor(from);
    if (rows == null) {
      return line;
    }
    int steps = 0;
    while (line >= 0) {
      long row = forward ? rows.ceiling(line) : rows.floor(line);
      if (row == line || row < 0) {
        return row;
      }
      line = forward ? marks.ceiling(row) : marks.floor(row);
      if (token != null && ++steps == CHECK_STEPS) {
        steps = 0;
        token.check();
      }
    }
    return -1;
  }

  /**
   * Counts the marks that are rows and lists the first of them.
   *
   * @param marks the document's marks
   * @param rows the rows' lines
   * @param out receives the first {@code out.length} such marks, ascending
   * @param token stops the count early when canceled
   * @return the count
   */
  static long among(LineSet marks, LineSet rows, long[] out, CancellationToken token) {
    long count = 0;
    long line = next(marks, rows, 0);
    while (line >= 0 && !token.isCanceled()) {
      if (count < out.length) {
        out[(int) count] = line;
      }
      count++;
      line = next(marks, rows, line + 1);
    }
    return count;
  }
}
