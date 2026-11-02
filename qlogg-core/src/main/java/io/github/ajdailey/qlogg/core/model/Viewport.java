package io.github.ajdailey.qlogg.core.model;

/**
 * The visible window over a document's lines and columns, and its mapping onto an {@code int}
 * scroll bar. Positions are {@code long}; a scroll bar is not. Not thread-safe: the UI thread owns
 * an instance.
 *
 * <p>Let {@code maxTop = max(0, lineCount - rows)}. While {@code maxTop <= SCROLLBAR_RANGE} the
 * scroll bar is one unit per line with a thumb of {@code rows}. Above that it is scaled: selection
 * {@code round(top / maxTop * SCROLLBAR_RANGE)}, and the inverse for a thumb drag, in {@code
 * double}, which is exact at both ends for any {@code maxTop < 2^53}. Wheel, keys and go to line
 * set {@link #topLine} directly; only a thumb drag goes through {@link #setScrollbarSelection}.
 */
public final class Viewport {
  /** Scroll bar units in scaled mode. */
  public static final int SCROLLBAR_RANGE = 1 << 30;

  private long lineCount;
  private int rows = 1;
  private int columns = 1;
  private long topLine;
  private long firstColumn;
  private long maxColumns;

  /**
   * Returns the document's line count as last set.
   *
   * @return lines
   */
  public long lineCount() {
    return lineCount;
  }

  /**
   * Returns the first visible line.
   *
   * @return a 0-based line, at most {@link #maxTop}
   */
  public long topLine() {
    return topLine;
  }

  /**
   * Returns the rows that fit the view.
   *
   * @return at least 1
   */
  public int rows() {
    return rows;
  }

  /**
   * Returns the columns that fit the view.
   *
   * @return at least 1
   */
  public int columns() {
    return columns;
  }

  /**
   * Returns the first visible display column.
   *
   * @return a 0-based column
   */
  public long firstColumn() {
    return firstColumn;
  }

  /**
   * Returns the horizontal extent.
   *
   * @return columns, as last set
   */
  public long maxColumns() {
    return maxColumns;
  }

  /**
   * Returns the last top line that still fills the view.
   *
   * @return {@code max(0, lineCount - rows)}
   */
  public long maxTop() {
    return Math.max(0, lineCount - rows);
  }

  /**
   * Counts visible rows that show a line.
   *
   * @return rows, fewer than {@link #rows} at the end of a short document
   */
  public int visibleLines() {
    return (int) Math.max(0, Math.min(rows, lineCount - topLine));
  }

  /**
   * Sets the line count, as the index grows; the top line stays unless it passes the new end.
   *
   * @param lineCount lines in the document
   */
  public void setLineCount(long lineCount) {
    this.lineCount = Math.max(0, lineCount);
    topLine = clampTop(topLine);
  }

  /**
   * Sets the horizontal extent.
   *
   * @param maxColumns the widest line's display width
   */
  public void setMaxColumns(long maxColumns) {
    this.maxColumns = Math.max(0, maxColumns);
    firstColumn = clampColumn(firstColumn);
  }

  /**
   * Sets the size of the view.
   *
   * @param rows rows that fit; at least 1 is kept
   * @param columns columns that fit; at least 1 is kept
   */
  public void resize(int rows, int columns) {
    this.rows = Math.max(1, rows);
    this.columns = Math.max(1, columns);
    topLine = clampTop(topLine);
    firstColumn = clampColumn(firstColumn);
  }

  /**
   * Puts a line at the top, or as near as the end allows.
   *
   * @param line a 0-based line
   */
  public void scrollToLine(long line) {
    topLine = clampTop(line);
  }

  /**
   * Moves the top line.
   *
   * @param lines positive scrolls down
   */
  public void scrollBy(long lines) {
    long target = topLine + lines;
    if (lines > 0 && target < topLine) {
      target = Long.MAX_VALUE;
    }
    topLine = clampTop(target);
  }

  /**
   * Sets the first visible column.
   *
   * @param column a 0-based column
   */
  public void setFirstColumn(long column) {
    firstColumn = clampColumn(column);
  }

  /**
   * Moves the first visible column.
   *
   * @param delta positive scrolls right
   */
  public void scrollColumnsBy(long delta) {
    firstColumn = clampColumn(firstColumn + delta);
  }

  /**
   * Scrolls the least distance that shows a line.
   *
   * @param line a 0-based line
   */
  public void ensureVisible(long line) {
    if (line < topLine) {
      topLine = clampTop(line);
    } else if (line >= topLine + rows) {
      topLine = clampTop(line - rows + 1);
    }
  }

  /**
   * Scrolls the least distance that shows a column.
   *
   * @param column a 0-based column
   */
  public void ensureColumnVisible(long column) {
    if (column < firstColumn) {
      firstColumn = clampColumn(column);
    } else if (column >= firstColumn + columns) {
      firstColumn = clampColumn(column - columns + 1);
    }
  }

  /**
   * Reports whether the last line is visible.
   *
   * @return true when the top line is {@link #maxTop}
   */
  public boolean atBottom() {
    return topLine >= maxTop();
  }

  /**
   * Reports whether the scroll bar is scaled.
   *
   * @return true when {@link #maxTop} exceeds {@link #SCROLLBAR_RANGE}
   */
  public boolean scaled() {
    return maxTop() > SCROLLBAR_RANGE;
  }

  /**
   * Returns the scroll bar maximum.
   *
   * @return {@code maxTop + rows} unscaled, {@code SCROLLBAR_RANGE + thumb} scaled
   */
  public int scrollbarMaximum() {
    if (scaled()) {
      return SCROLLBAR_RANGE + scrollbarThumb();
    }
    return (int) Math.min(Integer.MAX_VALUE, maxTop() + rows);
  }

  /**
   * Returns the scroll bar thumb.
   *
   * @return {@code rows} unscaled, {@code max(1, rows * SCROLLBAR_RANGE / maxTop)} scaled
   */
  public int scrollbarThumb() {
    if (scaled()) {
      return (int) Math.max(1, (long) rows * SCROLLBAR_RANGE / maxTop());
    }
    return rows;
  }

  /**
   * Returns the scroll bar selection for the top line.
   *
   * @return 0 to {@code scrollbarMaximum() - scrollbarThumb()}
   */
  public int scrollbarSelection() {
    long maxTop = maxTop();
    if (maxTop > SCROLLBAR_RANGE) {
      return (int) Math.round((double) topLine / maxTop * SCROLLBAR_RANGE);
    }
    return (int) topLine;
  }

  /**
   * Sets the top line from a scroll bar selection, for a thumb drag.
   *
   * @param selection the scroll bar's selection; clamped to its range
   */
  public void setScrollbarSelection(int selection) {
    long maxTop = maxTop();
    if (maxTop > SCROLLBAR_RANGE) {
      int clamped = Math.max(0, Math.min(SCROLLBAR_RANGE, selection));
      topLine = clampTop(Math.round((double) clamped / SCROLLBAR_RANGE * maxTop));
    } else {
      topLine = clampTop(selection);
    }
  }

  private long clampTop(long line) {
    return Math.max(0, Math.min(line, maxTop()));
  }

  private long clampColumn(long column) {
    return Math.max(0, Math.min(column, Math.max(0, maxColumns - columns)));
  }
}
