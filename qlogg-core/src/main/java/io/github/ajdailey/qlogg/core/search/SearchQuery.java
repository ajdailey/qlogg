package io.github.ajdailey.qlogg.core.search;

/**
 * What a search looks for. Immutable.
 *
 * @param expression the pattern or combined patterns
 * @param caseSensitive false folds case: ASCII letters on bytes for an ASCII literal, else {@code
 *     CASE_INSENSITIVE | UNICODE_CASE}
 * @param wholeWord a term matches only where the characters before and after it are not word
 *     characters ({@code [A-Za-z0-9_]})
 * @param inverse matches the lines the expression does not match
 * @param scope the lines searched, or null for every indexed line
 * @param hideAnsi lines are matched with ANSI SGR color sequences removed, as {@link
 *     io.github.ajdailey.qlogg.core.text.AnsiCodes} strips them; no byte search runs
 */
public record SearchQuery(
    SearchExpression expression,
    boolean caseSensitive,
    boolean wholeWord,
    boolean inverse,
    LineRange scope,
    boolean hideAnsi) {
  /** Checks the components. */
  public SearchQuery {
    if (expression == null) {
      throw new IllegalArgumentException("expression is required");
    }
  }

  /**
   * Creates a query over the lines as they are in the file.
   *
   * @param expression the pattern or combined patterns
   * @param caseSensitive see the record
   * @param wholeWord see the record
   * @param inverse see the record
   * @param scope see the record
   */
  public SearchQuery(
      SearchExpression expression,
      boolean caseSensitive,
      boolean wholeWord,
      boolean inverse,
      LineRange scope) {
    this(expression, caseSensitive, wholeWord, inverse, scope, false);
  }

  /**
   * Creates a case-sensitive query for one pattern over every indexed line.
   *
   * @param pattern the pattern
   * @param mode how the pattern is read
   * @return the query
   */
  public static SearchQuery of(String pattern, MatchMode mode) {
    return new SearchQuery(new SearchExpression.Term(pattern, mode), true, false, false, null);
  }
}
