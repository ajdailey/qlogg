package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.index.LineIndex;

/**
 * The lines a search tests: every line, or the members of {@link SearchQuery#restriction}. {@link
 * SearchEngine} cuts chunks, splits a chunk into runs, walks a chunk's lines and adds range lines
 * through it.
 */
abstract sealed class SearchedLines {
  final LineIndex index;

  SearchedLines(LineIndex index) {
    this.index = index;
  }

  /**
   * Returns the searched lines of a query.
   *
   * @param query the query
   * @param index the snapshot searched
   * @param gapBytes the most bytes between two members read in one run
   * @param lineFeedBytes the width of the encoding's LF
   * @return every line when the query has no restriction, else its members
   */
  static SearchedLines of(SearchQuery query, LineIndex index, int gapBytes, int lineFeedBytes) {
    return query.restriction() == null
        ? new All(index)
        : new Members(query.restriction(), index, gapBytes, lineFeedBytes);
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
   * Returns the end of a chunk that starts at a searched line, cut back to its last searched line.
   *
   * @param start a searched line
   * @param stop the first line after the chunk, above {@code start}
   * @return one past the chunk's last searched line
   */
  abstract long cut(long start, long stop);

  /**
   * Splits a chunk into the runs read for it.
   *
   * @param start the chunk's first line, a searched line
   * @param stop one past the chunk's last line, a searched line
   * @return the runs, at least one
   */
  abstract ChunkRuns runs(long start, long stop);

  /**
   * Puts the searched lines of {@code [from, to)} into {@code out} at {@code n}.
   *
   * @return the next free slot
   */
  abstract int add(long[] out, int n, long from, long to);

  /** Every line, read as one run. */
  private static final class All extends SearchedLines {
    All(LineIndex index) {
      super(index);
    }

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
    ChunkRuns runs(long start, long stop) {
      ChunkRuns runs = new ChunkRuns();
      runs.add(start, stop - 1, index.lineStart(start), index.lineEnd(stop - 1));
      return runs;
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

  /**
   * The members of a set. A run starts at a member and takes each following member that starts at
   * most {@code gapBytes} after the previous member's end, with the lines between them.
   */
  private static final class Members extends SearchedLines {
    private final LineSet set;
    private final int gapBytes;
    private final int lineFeedBytes;

    Members(LineSet set, LineIndex index, int gapBytes, int lineFeedBytes) {
      super(index);
      this.set = set;
      this.gapBytes = gapBytes;
      this.lineFeedBytes = lineFeedBytes;
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
      return Math.max(start, set.floor(stop - 1)) + 1;
    }

    @Override
    ChunkRuns runs(long start, long stop) {
      ChunkRuns runs = new ChunkRuns();
      long limit = stop - 1;
      long member = start;
      long first = index.lineStart(member);
      if (gapBytes >= lineFeedBytes
          && limit > member
          && set.rank(stop) - set.rank(member) == stop - member) {
        // Consecutive members are one LF apart, so a chunk of members only is one run.
        runs.add(member, limit, first, index.lineEnd(limit));
        return runs;
      }
      while (true) {
        long last = member;
        long finish = index.lineEnd(member);
        while (last < limit) {
          // Each member up to the last one starting within the gap of finish starts within the gap
          // of the member before it.
          long next = set.floor(Math.min(limit, index.lineAt(finish + gapBytes)));
          if (next <= last) {
            break;
          }
          last = next;
          finish = index.lineEnd(next);
        }
        runs.add(member, last, first, finish);
        if (last == limit) {
          return runs;
        }
        member = set.ceiling(last + 1);
        first = index.lineStart(member);
      }
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
