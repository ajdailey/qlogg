package io.github.ajdailey.qlogg.core.search;

/** A combined search expression that does not parse. */
public final class ExpressionException extends Exception {
  private static final long serialVersionUID = 1L;

  /** What is wrong at the column. */
  public enum Reason {
    /** A double quote opens a term that never closes. */
    UNTERMINATED_QUOTE,
    /** A quoted term, {@code not} or {@code (} is expected. */
    EXPECTED_TERM,
    /** A {@code )} is expected. */
    EXPECTED_CLOSE,
    /** An operator or the end is expected. */
    EXPECTED_OPERATOR,
    /** A word that is not an operator. */
    UNKNOWN_WORD,
    /** Parentheses or {@code not} nest deeper than {@link ExpressionParser#MAX_DEPTH}. */
    TOO_DEEP
  }

  private final Reason reason;
  private final int column;

  /**
   * Creates the exception.
   *
   * @param reason what is wrong
   * @param column the 1-based column where it was found
   */
  public ExpressionException(Reason reason, int column) {
    super(reason + " at column " + column);
    this.reason = reason;
    this.column = column;
  }

  /**
   * Returns what is wrong.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }

  /**
   * Returns where it was found.
   *
   * @return the 1-based column
   */
  public int column() {
    return column;
  }
}
