package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.index.LineIndex;

/**
 * The lines a search tests: every line, or the members of {@link SearchQuery#restriction}; with
 * {@link SearchQuery#records}, the records whose first line is such a line. A tested unit is one
 * line, or one record from its first line to the line before the next record start. {@link
 * SearchEngine} cuts chunks, splits a chunk into runs, walks a chunk's units and adds range lines
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
    if (query.records() != null) {
      return new Records(query.restriction(), query.records(), index, gapBytes);
    }
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
   * Returns the first unit after a unit.
   *
   * @param last the unit's last line
   * @return the next unit's first line, or -1 when none follows
   */
  abstract long next(long last);

  /**
   * Returns the last searched line before a line.
   *
   * @param end the first line not searched
   * @return the line, or -1 when none is below it
   */
  abstract long last(long end);

  /**
   * Returns the end of a chunk that starts at a unit, cut back to the end of its last unit within
   * {@code stop}, or past {@code stop} to the end of the first unit when that unit is longer.
   *
   * @param start a unit's first line
   * @param stop the first line after the chunk, above {@code start}
   * @return one past the chunk's last line
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

  /**
   * Returns the record bounds of a chunk's lines for its worker.
   *
   * @param start the chunk's first line
   * @param stop one past its last line
   * @return null when each unit is one line
   */
  RecordMarks marks(long start, long stop) {
    return null;
  }

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
    long next(long last) {
      return last + 1;
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
    long next(long last) {
      return set.ceiling(last + 1);
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

  /**
   * Records whose first line is in a set, or every record. A run starts at a tested record and
   * takes each following tested record that starts at most {@code gapBytes} after the previous
   * one's end, with the lines between them.
   */
  private static final class Records extends SearchedLines {
    private final LineSet set;
    private final RecordLines records;
    private final int gapBytes;

    /** {@code set} null tests every record. */
    Records(LineSet set, RecordLines records, LineIndex index, int gapBytes) {
      super(index);
      this.set = set;
      this.records = records;
      this.gapBytes = gapBytes;
    }

    @Override
    long first(long line, long end) {
      if (line >= end) {
        return end;
      }
      if (set == null) {
        return Math.min(records.nextStart(line), end);
      }
      long member = set.ceiling(line);
      while (member >= 0 && member < end && !records.starts(member)) {
        member = set.ceiling(member + 1);
      }
      return member < 0 ? end : Math.min(member, end);
    }

    @Override
    long next(long last) {
      if (set == null) {
        return last + 1;
      }
      long next = first(last + 1, Long.MAX_VALUE);
      return next == Long.MAX_VALUE ? -1 : next;
    }

    @Override
    long last(long end) {
      return end - 1;
    }

    @Override
    long cut(long start, long stop) {
      long last = start;
      if (set == null) {
        long boundary = stop >= index.lineCount() ? stop : records.startOf(stop);
        if (boundary > start) {
          return boundary;
        }
      } else {
        long member = set.floor(stop - 1);
        while (member > start && !records.starts(member)) {
          member = set.floor(member - 1);
        }
        last = Math.max(start, member);
      }
      return recordEnd(last);
    }

    @Override
    ChunkRuns runs(long start, long stop) {
      ChunkRuns runs = new ChunkRuns();
      if (set == null) {
        runs.add(start, stop - 1, index.lineStart(start), index.lineEnd(stop - 1));
        return runs;
      }
      long runFirst = start;
      long first = index.lineStart(start);
      long end = recordEnd(start);
      long finish = index.lineEnd(end - 1);
      while (true) {
        long next = end < stop ? first(end, stop) : stop;
        if (next >= stop) {
          runs.add(runFirst, end - 1, first, finish);
          return runs;
        }
        long nextStart = index.lineStart(next);
        if (nextStart > finish + gapBytes) {
          runs.add(runFirst, end - 1, first, finish);
          runFirst = next;
          first = nextStart;
        }
        end = recordEnd(next);
        finish = index.lineEnd(end - 1);
      }
    }

    @Override
    int add(long[] out, int n, long from, long to) {
      int at = n;
      long line = first(from, to);
      while (line < to) {
        out[at++] = line;
        line = first(line + 1, to);
      }
      return at;
    }

    @Override
    RecordMarks marks(long start, long stop) {
      return records.marks(start, stop);
    }

    /** The first line after the record starting at a line, within the index. */
    private long recordEnd(long start) {
      return Math.min(records.nextStart(start + 1), Math.max(start + 1, index.lineCount()));
    }
  }
}
