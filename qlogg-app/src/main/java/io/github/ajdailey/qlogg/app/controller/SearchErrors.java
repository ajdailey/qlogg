package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.search.ColumnException;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.util.regex.PatternSyntaxException;

/** The text a search, range search, quick find or folder search reports for a pattern error. */
final class SearchErrors {
  private SearchErrors() {}

  /**
   * Describes a pattern that does not compile, or a column term the search cannot test.
   *
   * @param error the error
   * @return the message
   */
  static String text(PatternSyntaxException error) {
    if (error instanceof ColumnException column) {
      UiText text =
          switch (column.reason()) {
            case NO_SUCH_COLUMN, NO_COLUMNS -> UiText.SEARCH_NO_COLUMN;
            case LINES_ONLY -> UiText.SEARCH_COLUMN_LINES_ONLY;
          };
      return text.format(LogText.oneLine(column.column()));
    }
    return UiText.SEARCH_REGEX_ERROR.format(
        Math.max(0, error.getIndex()) + 1, LogText.oneLine(error.getDescription()));
  }
}
