package io.github.ajdailey.qlogg.core.search;

import java.util.Arrays;

/** A {@link LineSet} over a sorted array of distinct lines, for a few lines. Immutable. */
public final class SortedLines implements LineSet {
  private final long[] lines;

  /**
   * Creates the set.
   *
   * @param lines distinct non-negative lines in ascending order; copied
   * @throws IllegalArgumentException when the lines are not ascending or one is negative
   */
  public SortedLines(long[] lines) {
    for (int i = 0; i < lines.length; i++) {
      if (lines[i] < 0 || (i > 0 && lines[i] <= lines[i - 1])) {
        throw new IllegalArgumentException("lines must be ascending and non-negative");
      }
    }
    this.lines = lines.clone();
  }

  @Override
  public long size() {
    return lines.length;
  }

  @Override
  public boolean contains(long line) {
    return Arrays.binarySearch(lines, line) >= 0;
  }

  @Override
  public long select(long index) {
    if (index < 0 || index >= lines.length) {
      throw new IndexOutOfBoundsException("index " + index + " of " + lines.length);
    }
    return lines[(int) index];
  }

  @Override
  public long rank(long line) {
    int at = Arrays.binarySearch(lines, line);
    return at >= 0 ? at : -at - 1;
  }

  @Override
  public long ceiling(long line) {
    int at = (int) rank(line);
    return at < lines.length ? lines[at] : -1;
  }

  @Override
  public long floor(long line) {
    int at = Arrays.binarySearch(lines, line);
    int index = at >= 0 ? at : -at - 2;
    return index >= 0 ? lines[index] : -1;
  }
}
