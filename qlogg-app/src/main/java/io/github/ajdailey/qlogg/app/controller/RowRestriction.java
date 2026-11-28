package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.core.search.LineRange;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.RecordStarts;
import io.github.ajdailey.qlogg.core.search.SearchQuery;

/**
 * Restricts a search to a tab's rows. A record search ({@link SearchQuery#records}) over a filter
 * tab tests the records holding its rows ({@link RecordStarts}), so a tab whose rows are
 * continuation lines, as a text-view search finds them, finds records too; each hit is the record's
 * first line. The engine cuts record chunks after the restriction picked the records.
 */
final class RowRestriction {
  private RowRestriction() {}

  /**
   * Restricts a query to a tab's members and cuts its scope at their settled line.
   *
   * @param query the query, with the tab's columns attached
   * @param rows the tab's rows
   * @return the query searched
   */
  static SearchQuery settled(SearchQuery query, RowMap rows) {
    LineRange scope = query.scope();
    long end = Math.min(scope == null ? Long.MAX_VALUE : scope.end(), rows.settledLines());
    long first = scope == null ? 0 : scope.first();
    LineRange cut =
        first == 0 && end == Long.MAX_VALUE ? null : new LineRange(first, Math.max(first, end));
    SearchQuery settled =
        new SearchQuery(
            query.expression(),
            query.caseSensitive(),
            query.wholeWord(),
            query.inverse(),
            cut,
            query.hideAnsi(),
            query.rangeEnd(),
            query.rangeOpen(),
            null,
            query.columns());
    return restricted(settled, rows.members());
  }

  /**
   * Restricts a query to lines, or to the records holding them in a record search.
   *
   * @param query the query, with its columns attached
   * @param members the lines, or null for every line
   * @return the query searched
   */
  static SearchQuery restricted(SearchQuery query, LineSet members) {
    if (members == null) {
      return query;
    }
    return query.restrictedTo(
        query.records() == null ? members : new RecordStarts(members, query.records()));
  }
}
