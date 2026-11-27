package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.search.ExpressionException;
import io.github.ajdailey.qlogg.core.search.ExpressionParser;
import io.github.ajdailey.qlogg.core.search.LineRange;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.search.SearchExpression;
import io.github.ajdailey.qlogg.core.search.SearchLimit;
import io.github.ajdailey.qlogg.core.search.SearchQuery;

/**
 * A search that ran to its end, as a filter tab takes it, or a filter tab's search before it ran,
 * as a session restores it. A class, not a record, so the mutable set has no public accessor.
 */
final class RanSearch {
  final String text;
  final SearchOptions options;
  final SearchQuery query;
  final MutableLineSet lines;
  final SearchLimit limit;
  final long end;
  final long bytes;
  final boolean rangeOpen;

  /**
   * Creates the search.
   *
   * @param text the search text
   * @param options the toggles it ran with
   * @param query its query, without a restriction to the tab's rows
   * @param lines the matching lines
   * @param limit what stopped it early, or {@code NONE}
   * @param end the line below which {@code lines} holds every match
   * @param bytes the indexed bytes it searched, or -1 when none
   * @param rangeOpen for a range search, whether a range was open before the line {@code end - 1}
   */
  RanSearch(
      String text,
      SearchOptions options,
      SearchQuery query,
      MutableLineSet lines,
      SearchLimit limit,
      long end,
      long bytes,
      boolean rangeOpen) {
    this.text = text;
    this.options = options;
    this.query = query;
    this.lines = lines;
    this.limit = limit;
    this.end = end;
    this.bytes = bytes;
    this.rangeOpen = rangeOpen;
  }

  /**
   * Returns the same search over other lines, as a copy of its set.
   *
   * @param copy the lines
   * @return the search
   */
  RanSearch over(MutableLineSet copy) {
    return new RanSearch(text, options, query, copy, limit, end, bytes, rangeOpen);
  }

  /**
   * Returns a search that has not run, over empty lines a filter tab's growth fills.
   *
   * @param text the search text
   * @param options the toggles
   * @param query the query
   * @param lines empty lines the tab's rows own
   * @return the search
   */
  static RanSearch unrun(
      String text, SearchOptions options, SearchQuery query, MutableLineSet lines) {
    return new RanSearch(text, options, query, lines, SearchLimit.NONE, 0, -1, false);
  }

  /**
   * Parses a search as the search bar runs it.
   *
   * @param text the search text, not empty
   * @param options the toggles
   * @param scope the lines searched, or null for every line
   * @param hideAnsi the document's ANSI setting
   * @return the query
   * @throws ExpressionException when the text or the range end does not parse
   */
  static SearchQuery query(String text, SearchOptions options, LineRange scope, boolean hideAnsi)
      throws ExpressionException {
    SearchExpression expression = ExpressionParser.parse(text, options.mode(), options.combining());
    return new SearchQuery(
        expression,
        options.caseSensitive(),
        options.wholeWord(),
        options.inverse(),
        scope,
        hideAnsi,
        options.range() ? rangeEnd(options) : null,
        false);
  }

  /**
   * Parses a range search's end expression.
   *
   * @param options the toggles, with {@code range} on
   * @return the expression
   * @throws ExpressionException when the end is empty or does not parse
   */
  static SearchExpression rangeEnd(SearchOptions options) throws ExpressionException {
    if (options.rangeEnd().isEmpty()) {
      throw new ExpressionException(ExpressionException.Reason.EXPECTED_TERM, 1);
    }
    return ExpressionParser.parse(options.rangeEnd(), options.mode(), options.combining());
  }
}
