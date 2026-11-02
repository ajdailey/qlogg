package io.github.ajdailey.qlogg.core.search;

/** A sorted set of 0-based line numbers with rank and select. */
public interface LineSet {
  /**
   * Counts the lines.
   *
   * @return the size
   */
  long size();

  /**
   * Reports membership.
   *
   * @param line any value
   * @return true when the line is in the set
   */
  boolean contains(long line);

  /**
   * Returns a line by position.
   *
   * @param index 0 to {@code size() - 1}
   * @return the index-th smallest line
   * @throws IndexOutOfBoundsException for any other index
   */
  long select(long index);

  /**
   * Counts lines below a line.
   *
   * @param line any value
   * @return the number of lines {@code < line}
   */
  long rank(long line);

  /**
   * Returns the smallest line at or after a line.
   *
   * @param line any value
   * @return the line, or -1
   */
  long ceiling(long line);

  /**
   * Returns the largest line at or before a line.
   *
   * @param line any value
   * @return the line, or -1
   */
  long floor(long line);
}
