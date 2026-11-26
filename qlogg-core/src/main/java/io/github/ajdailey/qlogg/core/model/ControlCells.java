package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.text.ControlChars;

/**
 * Builds a row's {@link LineBlock#controls} beside its cut text. Nothing is appended until the
 * row's first box, so a row without one costs no work and gives null. Not thread-safe.
 */
final class ControlCells {
  private final StringBuilder cells = new StringBuilder();
  private boolean any;

  /** Starts a row. */
  void reset() {
    cells.setLength(0);
    any = false;
  }

  /**
   * Appends the columns of a boxed char's box that lie in {@code [from, to)} to the row text as
   * spaces, and records them.
   *
   * @param out the row text
   * @param c the boxed char
   * @param column the box's first column
   * @param from first column kept
   * @param to first column dropped
   */
  void box(StringBuilder out, char c, long column, long from, long to) {
    long end = Math.min(column + ControlChars.WIDTH, to);
    for (long s = Math.max(column, from); s < end; s++) {
      pad(out.length());
      any = true;
      out.append(' ');
      cells.append(s == column ? c : (char) (c + LineBlock.SECOND_CELL));
    }
  }

  /**
   * Returns the row's cells.
   *
   * @param length the row text's length
   * @return the cells, or null when the row shows no box
   */
  String take(int length) {
    if (!any) {
      return null;
    }
    pad(length);
    return cells.toString();
  }

  private void pad(int length) {
    while (cells.length() < length) {
      cells.append(' ');
    }
  }
}
