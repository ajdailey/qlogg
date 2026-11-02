package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.search.LineSet;

/**
 * Walks the document lines a view lists, in line order. Thread-safe: a worker can walk the rows of
 * a view while the UI thread uses it.
 */
public interface RowCursor {
  /**
   * Returns the first listed line after a line.
   *
   * @param line a 0-based line, or -1 for the first listed line
   * @return the line, or -1 when none follows
   */
  long next(long line);

  /**
   * Returns the last listed line before a line.
   *
   * @param line a 0-based line
   * @return the line, or -1 when none precedes
   */
  long previous(long line);

  /**
   * Reports whether every line is listed, so row n is line n.
   *
   * @return true for {@link #all}
   */
  boolean contiguous();

  /**
   * Returns a cursor over every line below a count.
   *
   * @param lineCount lines in the document
   * @return the cursor
   */
  static RowCursor all(final long lineCount) {
    return new RowCursor() {
      @Override
      public long next(long line) {
        long next = Math.max(0, line + 1);
        return next < lineCount ? next : -1;
      }

      @Override
      public long previous(long line) {
        return Math.min(line, lineCount) - 1;
      }

      @Override
      public boolean contiguous() {
        return true;
      }
    };
  }

  /**
   * Returns a cursor over a set, or over the union of two sets.
   *
   * @param a a set
   * @param b another set, or null for {@code a} alone
   * @return the cursor
   */
  static RowCursor of(final LineSet a, final LineSet b) {
    return new RowCursor() {
      @Override
      public long next(long line) {
        long x = a.ceiling(line + 1);
        if (b == null) {
          return x;
        }
        long y = b.ceiling(line + 1);
        if (x < 0) {
          return y;
        }
        return y < 0 ? x : Math.min(x, y);
      }

      @Override
      public long previous(long line) {
        if (line <= 0) {
          return -1;
        }
        long x = a.floor(line - 1);
        return b == null ? x : Math.max(x, b.floor(line - 1));
      }

      @Override
      public boolean contiguous() {
        return false;
      }
    };
  }
}
