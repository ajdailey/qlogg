package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.search.LineSet;

/**
 * Finds the next or previous mark among a view's rows. Marks belong to the root document, so a
 * filter tab steps over marks that are not its rows: the search alternates {@code ceiling} (or
 * {@code floor}) on the marks and on the rows until both return the same line, so each pair of
 * lookups skips a run of marks that are not rows.
 */
final class MarkSteps {
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
    long line = marks.ceiling(from);
    if (rows == null) {
      return line;
    }
    while (line >= 0) {
      long row = rows.ceiling(line);
      if (row == line || row < 0) {
        return row;
      }
      line = marks.ceiling(row);
    }
    return -1;
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
    long line = marks.floor(from);
    if (rows == null) {
      return line;
    }
    while (line >= 0) {
      long row = rows.floor(line);
      if (row == line || row < 0) {
        return row;
      }
      line = marks.floor(row);
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
