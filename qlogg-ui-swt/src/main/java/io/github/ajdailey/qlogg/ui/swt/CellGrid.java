package io.github.ajdailey.qlogg.ui.swt;

/**
 * Pixel geometry of a column view's cells for one horizontal scroll position: composed column
 * {@code c} of the frozen cells ({@code c < frozen}) sits at {@code x + columnOffset(c)}, a
 * scrolling column at {@code x + columnOffset(c - firstColumn)}, so composed column {@code frozen +
 * firstColumn} shows right of the frozen cells. A cell of width w keeps its last column blank; the
 * rule between cells is drawn in the middle of that column. Immutable and free of SWT types.
 *
 * @param grid the column grid
 * @param x the left edge of the text area
 * @param firstColumn the viewport's first column, counted after the frozen cells
 * @param frozen composed columns the frozen cells take
 */
record CellGrid(TextGrid grid, int x, long firstColumn, int frozen) {
  /**
   * Returns the x of a composed column.
   *
   * @param column a composed column; a frozen one when below {@link #frozen}
   * @return pixels
   */
  int columnX(long column) {
    return column < frozen
        ? x + grid.columnOffset(column)
        : x + grid.columnOffset(column - firstColumn);
  }

  /**
   * Returns the x of the frozen cells' right edge, where the scrolling cells start.
   *
   * @return pixels
   */
  int frozenEdge() {
    return x + grid.columnOffset(frozen);
  }

  /**
   * Returns the x of the rule that ends a cell.
   *
   * @param end the composed column after the cell, at least 1
   * @return pixels, in the middle of the cell's last column
   */
  int ruleX(long end) {
    long gap = end - 1;
    int left;
    int right;
    if (end <= frozen) {
      left = x + grid.columnOffset(gap);
      right = x + grid.columnOffset(end);
    } else {
      left = x + grid.columnOffset(gap - firstColumn);
      right = x + grid.columnOffset(end - firstColumn);
    }
    return left + (right - left) / 2;
  }

  /**
   * Reports whether a rule shows: a frozen cell's always, a scrolling cell's when it lies right of
   * the frozen cells.
   *
   * @param end the composed column after the cell
   * @param right the right edge of the text area
   * @return true when it is drawn
   */
  boolean ruleShows(long end, int right) {
    int at = ruleX(end);
    return end <= frozen || at >= frozenEdge() && at < right;
  }

  /**
   * Returns the composed column under an x.
   *
   * @param pointX pixels in the canvas
   * @return the column boundary nearest the point: a frozen column left of {@link #frozenEdge}
   */
  long columnAt(int pointX) {
    long screen = grid.columnAt(pointX - x);
    return screen < frozen ? screen : screen + firstColumn;
  }
}
