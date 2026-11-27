package io.github.ajdailey.qlogg.core.search;

import java.util.regex.PatternSyntaxException;

/**
 * A search term names a column the search cannot test: the view has no such column, the search has
 * no {@link ColumnSearch}, or the search tests a {@link
 * io.github.ajdailey.qlogg.core.columns.ColumnFormat#multiLine} format line by line, as a range
 * search does. Thrown where a term compiles, as a {@link PatternSyntaxException} at index -1, so
 * every caller that reports a pattern that does not compile reports this too.
 */
public final class ColumnException extends PatternSyntaxException {
  private static final long serialVersionUID = 1L;

  /** Why the column cannot be tested. */
  public enum Reason {
    /** The view's columns have no such name or number. */
    NO_SUCH_COLUMN,
    /** The view shows no columns. */
    NO_COLUMNS,
    /**
     * The format's records span lines and the search tests lines: a range search, or a search
     * without records.
     */
    LINES_ONLY
  }

  private final Reason reason;
  private final String column;

  /**
   * Creates the exception.
   *
   * @param reason why the column cannot be tested
   * @param column the name or number as written in the term
   */
  public ColumnException(Reason reason, String column) {
    super(describe(reason, column), column, -1);
    this.reason = reason;
    this.column = column;
  }

  /**
   * Returns why the column cannot be tested.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }

  /**
   * Returns the column as written in the term.
   *
   * @return the name or number
   */
  public String column() {
    return column;
  }

  private static String describe(Reason reason, String column) {
    return switch (reason) {
      case NO_SUCH_COLUMN, NO_COLUMNS -> "No column " + column + " in this view";
      case LINES_ONLY -> "Column " + column + " cannot be searched line by line in this format";
    };
  }
}
