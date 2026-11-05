package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.search.LineSet;

/**
 * Per-pixel counts of matching and marked lines for the overview bar: pixel y covers lines {@code
 * [lines[y], lines[y + 1])} of the array given to {@link #compute}. Each count is a difference of
 * two {@link LineSet#rank} calls, so {@link #compute} costs a few rank lookups per pixel whatever
 * the size of the sets. Immutable.
 *
 * <p>Cost ({@link #retainedBytes}): 8 bytes per pixel row plus {@link #OVERHEAD}.
 *
 * @param lineCount lines the bins cover
 * @param matches matching lines per pixel row
 * @param marks marked lines per pixel row
 */
public record OverviewBins(long lineCount, int[] matches, int[] marks) {
  /** No lines. */
  public static final OverviewBins EMPTY = new OverviewBins(0, new int[0], new int[0]);

  /** Bytes per instance beyond its arrays. */
  public static final int OVERHEAD = 64;

  /** Rows between cancellation checks. */
  static final int CHECK_ROWS = 256;

  /**
   * Returns the bar's height in pixels.
   *
   * @return pixel rows
   */
  public int height() {
    return matches.length;
  }

  /**
   * Returns the heap this instance holds.
   *
   * @return bytes
   */
  public long retainedBytes() {
    return OVERHEAD + 8L * matches.length;
  }

  /**
   * Counts the lines of two sets per pixel row.
   *
   * @param matches the matching lines, or null
   * @param marks the marked lines, or null
   * @param lines each pixel row's first line in ascending order, then the line after the last row;
   *     read, not kept
   * @param token checked every {@link #CHECK_ROWS} rows
   * @return the bins, {@code lines.length - 1} rows high
   * @throws java.util.concurrent.CancellationException once the token is canceled
   */
  public static OverviewBins compute(
      LineSet matches, LineSet marks, long[] lines, CancellationToken token) {
    int rows = lines.length - 1;
    int[] matched = new int[rows];
    int[] marked = new int[rows];
    long from = lines[0];
    long matchesBelow = matches == null ? 0 : matches.rank(from);
    long marksBelow = marks == null ? 0 : marks.rank(from);
    for (int y = 0; y < rows; y++) {
      if (y % CHECK_ROWS == 0) {
        token.check();
      }
      long to = lines[y + 1];
      if (to > from) {
        long m = matches == null ? 0 : matches.rank(to);
        long k = marks == null ? 0 : marks.rank(to);
        matched[y] = (int) Math.min(Integer.MAX_VALUE, m - matchesBelow);
        marked[y] = (int) Math.min(Integer.MAX_VALUE, k - marksBelow);
        matchesBelow = m;
        marksBelow = k;
        from = to;
      }
    }
    return new OverviewBins(lines[rows], matched, marked);
  }

  /**
   * Returns the first line of a pixel row: {@code floor(y * lineCount / height)}, exact for every
   * {@code long} line count.
   *
   * @param y 0 to height; height gives lineCount
   * @param lineCount lines, at least 0
   * @param height pixel rows, at least 1
   * @return the line
   */
  public static long lineAt(int y, long lineCount, int height) {
    if (height <= 0) {
      return 0;
    }
    int row = Math.max(0, Math.min(y, height));
    return (lineCount / height) * row + (lineCount % height) * row / height;
  }

  /**
   * Returns the pixel row holding a line: the largest y with {@code lineAt(y) <= line}.
   *
   * @param line a 0-based line
   * @param lineCount lines, at least 1
   * @param height pixel rows, at least 1
   * @return 0 to height - 1
   */
  public static int rowOf(long line, long lineCount, int height) {
    if (height <= 0 || lineCount <= 0) {
      return 0;
    }
    long clamped = Math.max(0, Math.min(line, lineCount - 1));
    int lo = 0;
    int hi = height - 1;
    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1;
      if (lineAt(mid, lineCount, height) <= clamped) {
        lo = mid;
      } else {
        hi = mid - 1;
      }
    }
    return lo;
  }
}
