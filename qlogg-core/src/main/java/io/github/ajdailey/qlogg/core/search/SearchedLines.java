package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.index.LineIndex;

/**
 * The lines a search tests: every line, or the members of {@link SearchQuery#restriction}. {@link
 * SearchEngine} cuts chunks, walks a chunk's lines and adds range lines through it.
 */
abstract sealed class SearchedLines {
  /**
   * Returns the searched lines of a query.
   *
   * @param query the query
   * @param index the snapshot searched
   * @param gapBytes the most bytes between two members in one chunk
   * @return every line when the query has no restriction, else its members
   */
  static SearchedLines of(SearchQuery query, LineIndex index, int gapBytes) {
    return query.restriction() == null
        ? new All()
        : new Members(query.restriction(), index, gapBytes);
  }

  /**
   * Returns the first searched line at or after a line.
   *
   * @param line at least 0
   * @param end the first line not searched
   * @return the line, or {@code end} when none is below it
   */
  abstract long first(long line, long end);

  /**
   * Returns the searched line after a line.
   *
   * @param line at least 0
   * @return the line, or -1 when none follows
   */
  abstract long next(long line);

  /**
   * Returns the last searched line before a line.
   *
   * @param end the first line not searched
   * @return the line, or -1 when none is below it
   */
  abstract long last(long end);

  /**
   * Returns the end of a chunk that starts at a searched line, cut back to its last searched line
   * and, for members, before the first member that starts more than the gap after the previous
   * member's end.
   *
   * @param start a searched line
   * @param stop the first line after the chunk, above {@code start}
   * @return one past the chunk's last searched line
   */
  abstract long cut(long start, long stop);

  /**
   * Puts the searched lines of {@code [from, to)} into {@code out} at {@code n}.
   *
   * @return the next free slot
   */
  abstract int add(long[] out, int n, long from, long to);

  /** Every line. */
  private static final class All extends SearchedLines {
    @Override
    long first(long line, long end) {
      return Math.min(line, end);
    }

    @Override
    long next(long line) {
      return line + 1;
    }

    @Override
    long last(long end) {
      return end - 1;
    }

    @Override
    long cut(long start, long stop) {
      return stop;
    }

    @Override
    int add(long[] out, int n, long from, long to) {
      int at = n;
      for (long line = from; line < to; line++) {
        out[at++] = line;
      }
      return at;
    }
  }

  /** The members of a set. */
  private static final class Members extends SearchedLines {
    private final LineSet set;
    private final LineIndex index;
    private final int gapBytes;

    Members(LineSet set, LineIndex index, int gapBytes) {
      this.set = set;
      this.index = index;
      this.gapBytes = gapBytes;
    }

    @Override
    long first(long line, long end) {
      long member = set.ceiling(line);
      return member < 0 ? end : Math.min(member, end);
    }

    @Override
    long next(long line) {
      return set.ceiling(line + 1);
    }

    @Override
    long last(long end) {
      return set.floor(end - 1);
    }

    @Override
    long cut(long start, long stop) {
      long last = Math.max(start, set.floor(stop - 1));
      long lastStart = index.lineStart(last);
      long member = start;
      while (member < last) {
        long reach = index.lineEnd(member) + gapBytes;
        if (lastStart <= reach) {
          return last + 1;
        }
        // Every member from here to the last one starting by reach is within the gap of the one
        // before it.
        long next = set.floor(index.lineAt(reach));
        if (next <= member) {
          return member + 1;
        }
        member = next;
      }
      return last + 1;
    }

    @Override
    int add(long[] out, int n, long from, long to) {
      int at = n;
      long line = set.ceiling(from);
      while (line >= 0 && line < to) {
        out[at++] = line;
        line = set.ceiling(line + 1);
      }
      return at;
    }
  }
}
