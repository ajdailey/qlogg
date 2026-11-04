package io.github.ajdailey.qlogg.core.search;

/**
 * A pattern overflowed the thread's stack while matching a line, as a deeply recursive regex such
 * as {@code (a|b)*c} does on a long line. A search or quick find ends with it at that line.
 */
public final class PatternTooComplexException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final long line;

  /**
   * Creates the exception.
   *
   * @param line the 0-based line being matched
   */
  public PatternTooComplexException(long line) {
    super("The pattern overflowed the stack matching line " + (line + 1), null, false, false);
    this.line = line;
  }

  /**
   * Returns the line being matched.
   *
   * @return the 0-based line
   */
  public long line() {
    return line;
  }
}
