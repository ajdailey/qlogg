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
 * @param rangeEnd null for a line search; otherwise a range search: {@code expression} opens a
 *     range and the first later line matching this closes it, and the results are every line from
 *     the opening line through the closing line ({@code inverse}: every other line)
 * @param rangeOpen for a range search, whether a range is open before the scope's first line
 * @param restriction the lines tested, or null for every line: the search reads the text as if it
 *     held only these lines, so a range opens and closes on members and {@code inverse} returns
 *     non-matching members. A change to the set below the searched end while the search runs leaves
 *     the result undefined
 * @param columns the columns column terms test and, with records, the records a line search tests;
 *     null in text view, where a column term fails with {@link ColumnException}
 */
public record SearchQuery(
    SearchExpression expression,
    boolean caseSensitive,
    boolean wholeWord,
    boolean inverse,
    LineRange scope,
    boolean hideAnsi,
    SearchExpression rangeEnd,
    boolean rangeOpen,
    LineSet restriction,
    ColumnSearch columns) {
  /** Checks the components. */
  public SearchQuery {
    if (expression == null) {
      throw new IllegalArgumentException("expression is required");
    }
  }

  /**
   * Creates a query in text view.
   *
   * @param expression the pattern or combined patterns
   * @param caseSensitive see the record
   * @param wholeWord see the record
   * @param inverse see the record
   * @param scope see the record
   * @param hideAnsi see the record
   * @param rangeEnd see the record
   * @param rangeOpen see the record
   * @param restriction see the record
   */
  public SearchQuery(
      SearchExpression expression,
      boolean caseSensitive,
      boolean wholeWord,
      boolean inverse,
      LineRange scope,
      boolean hideAnsi,
      SearchExpression rangeEnd,
      boolean rangeOpen,
      LineSet restriction) {
    this(
        expression,
        caseSensitive,
        wholeWord,
        inverse,
        scope,
        hideAnsi,
        rangeEnd,
        rangeOpen,
        restriction,
        null);
  }

  /**
   * Creates a query over every line.
   *
   * @param expression the pattern or combined patterns
   * @param caseSensitive see the record
   * @param wholeWord see the record
   * @param inverse see the record
   * @param scope see the record
   * @param hideAnsi see the record
   * @param rangeEnd see the record
   * @param rangeOpen see the record
   */
  public SearchQuery(
      SearchExpression expression,
      boolean caseSensitive,
      boolean wholeWord,
      boolean inverse,
      LineRange scope,
      boolean hideAnsi,
      SearchExpression rangeEnd,
      boolean rangeOpen) {
    this(expression, caseSensitive, wholeWord, inverse, scope, hideAnsi, rangeEnd, rangeOpen, null);
  }

  /**
   * Creates a line search.
   *
   * @param expression the pattern or combined patterns
   * @param caseSensitive see the record
   * @param wholeWord see the record
   * @param inverse see the record
   * @param scope see the record
   * @param hideAnsi see the record
   */
  public SearchQuery(
      SearchExpression expression,
      boolean caseSensitive,
      boolean wholeWord,
      boolean inverse,
      LineRange scope,
      boolean hideAnsi) {
    this(expression, caseSensitive, wholeWord, inverse, scope, hideAnsi, null, false);
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
   * Reports whether this is a range search.
   *
   * @return true when {@code rangeEnd} is set
   */
  public boolean range() {
    return rangeEnd != null;
  }

  /**
   * Returns this query restricted to a set of lines.
   *
   * @param lines the lines tested, or null for every line
   * @return the copy
   */
  public SearchQuery restrictedTo(LineSet lines) {
    return new SearchQuery(
        expression,
        caseSensitive,
        wholeWord,
        inverse,
        scope,
        hideAnsi,
        rangeEnd,
        rangeOpen,
        lines,
        columns);
  }

  /**
   * Returns this query in column view.
   *
   * @param search the columns and records, or null for text view
   * @return the copy
   */
  public SearchQuery withColumns(ColumnSearch search) {
    return new SearchQuery(
        expression,
        caseSensitive,
        wholeWord,
        inverse,
        scope,
        hideAnsi,
        rangeEnd,
        rangeOpen,
        restriction,
        search);
  }

  /**
   * Returns the records a line search tests.
   *
   * @return the records, or null when the search tests lines: in text view, for a single-line
   *     format and for a range search
   */
  public RecordLines records() {
    return columns == null || range() ? null : columns.records();
  }

  /**
   * Returns this query's matching for one expression: the same case, whole-word, ANSI and column
   * rules, not inverted, as a line search that tests lines.
   *
   * @param term the expression
   * @return the line search
   */
  public SearchQuery lineSearch(SearchExpression term) {
    return new SearchQuery(
        term,
        caseSensitive,
        wholeWord,
        false,
        scope,
        hideAnsi,
        null,
        false,
        null,
        columns == null ? null : columns.lineByLine());
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
