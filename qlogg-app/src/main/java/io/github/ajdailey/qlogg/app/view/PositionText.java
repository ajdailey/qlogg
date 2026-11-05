package io.github.ajdailey.qlogg.app.view;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.model.Selection;

/**
 * The top row's position field: caret line and column, the selection's size and the time from its
 * first to its last line.
 */
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
    return of(selection, lineCount, selection, "");
  }

  /**
   * Formats the position of one selection's caret and the size of another selection, with the time
   * difference of its first and last line.
   *
   * @param selection the main view's selection, for the line and column
   * @param lineCount lines indexed so far
   * @param selected the selection the size describes: the main view's, or the filtered view's
   * @param difference the selection's time difference, or empty
   * @return for example {@code Ln 12/200 Col 5 Sel 4 lines +00:01:23.456}
   */
  public static String of(
      Selection selection, long lineCount, Selection selected, String difference) {
    long line = Math.min(selection.caret().line() + 1, lineCount);
    long caretColumn = selection.caret().column();
    String text =
        caretColumn == Long.MAX_VALUE
            ? UiText.STATUS_LINE.format(line, lineCount)
            : UiText.STATUS_POSITION.format(line, lineCount, caretColumn + 1);
    if (selected.isEmpty()) {
      return text;
    }
    long first = selected.start().line();
    long last = selected.end().line();
    if (first == last && selected.end().column() != Long.MAX_VALUE) {
      long columns = selected.end().column() - selected.start().column();
      return text + "  " + UiText.STATUS_SELECTED_CHARS.format(columns);
    }
    text += "  " + UiText.STATUS_SELECTED_LINES.format(last - first + 1);
    return difference.isEmpty() || first == last ? text : text + "  " + difference;
  }
}
