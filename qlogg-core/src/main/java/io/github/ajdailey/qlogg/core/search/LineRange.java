package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.util.Checks;

/**
 * A range of 0-based lines.
 *
 * @param first the first line
 * @param end the line after the last; equal to {@code first} for an empty range
 */
public record LineRange(long first, long end) {
  /** Checks the components. */
  public LineRange {
    Checks.notNegative(first, "first");
    Checks.atLeast(end, first, "end");
  }

  /**
   * Counts the lines.
   *
   * @return {@code end - first}
   */
  public long size() {
    return end - first;
  }
}
