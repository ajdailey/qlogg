package io.github.ajdailey.qlogg.app.view;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.model.Selection;

/** The status bar's position field: caret line and column, and the selection's size. */
public final class PositionText {
  private PositionText() {}

  /**
   * Formats the position. Lines and columns count from 1; a caret at the end of a select-all shows
   * no column. A selection inside one line counts its columns, one over several lines counts the
   * lines.
   *
   * @param selection the main view's selection
   * @param lineCount lines indexed so far
   * @return for example {@code Ln 12/200 Col 5 Sel 3 lines}
   */
  public static String of(Selection selection, long lineCount) {
    long line = Math.min(selection.caret().line() + 1, lineCount);
    long caretColumn = selection.caret().column();
    String text =
        caretColumn == Long.MAX_VALUE
            ? UiText.STATUS_LINE.format(line, lineCount)
            : UiText.STATUS_POSITION.format(line, lineCount, caretColumn + 1);
    if (selection.isEmpty()) {
      return text;
    }
    long first = selection.start().line();
    long last = selection.end().line();
    if (first == last && selection.end().column() != Long.MAX_VALUE) {
      long columns = selection.end().column() - selection.start().column();
      return text + "  " + UiText.STATUS_SELECTED_CHARS.format(columns);
    }
    return text + "  " + UiText.STATUS_SELECTED_LINES.format(last - first + 1);
  }
}
