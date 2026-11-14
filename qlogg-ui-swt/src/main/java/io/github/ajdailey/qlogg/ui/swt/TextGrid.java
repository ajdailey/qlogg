package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.core.model.TextPosition;

/**
 * Column geometry of a log view: the monospace grid from a line height and a fractional advance,
 * the gutter, the wrap layout and hit testing. Column {@code c} sits at {@code round(c * advance)}
 * from the text's left edge. Immutable and free of SWT types; {@link LogCanvas} makes one per font
 * measurement.
 */
final class TextGrid {
  /** Padding on each side of the line numbers. */
  static final int GUTTER_PAD = 6;

  /** Fewest digits the line-number column has room for. */
  static final int MIN_DIGITS = 4;

  /** Bound on a column's pixel offset, so offsets and their differences stay in range. */
  private static final int MAX_OFFSET = Integer.MAX_VALUE / 4;

  /** Rows of each line from a view's top, as wrapped. */
  interface WrappedRows {
    /**
     * Returns the rows a line takes.
     *
     * @param line the line
     * @return at least 1
     */
    int rows(long line);
  }

  /**
   * A screen row as a line and the index of its segment.
   *
   * @param line the line; the line count when the row lies below the last line
   * @param segment the 0-based segment within the line
   */
  record LineRow(long line, int segment) {}

  private final int lineHeight;
  private final double advance;

  /**
   * Creates a grid.
   *
   * @param lineHeight the row height in pixels; at least 1
   * @param advance the column advance in pixels; at least 1
   */
  TextGrid(int lineHeight, double advance) {
    this.lineHeight = Math.max(1, lineHeight);
    this.advance = Math.max(1.0, advance);
  }

  int lineHeight() {
    return lineHeight;
  }

  double advance() {
    return advance;
  }

  /**
   * Returns the pixel offset of a column from the text's left edge, {@code round(column *
   * advance)}.
   *
   * @param column counted from the text's first column; may be negative
   * @return pixels, clamped to a quarter of the {@code int} range
   */
  int columnOffset(long column) {
    long x = Math.round(column * advance);
    return (int) Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, x));
  }

  /**
   * Returns the column boundary nearest a pixel offset from the text's left edge.
   *
   * @param textX pixels; 0 or less maps to column 0
   * @return the column
   */
  long columnAt(int textX) {
    return textX <= 0 ? 0 : (long) Math.floor(textX / advance + 0.5);
  }

  /** Returns one cell's width for markers drawn inside a cell. */
  int cellWidth() {
    return Math.max(1, (int) Math.round(advance));
  }

  /** Returns the width of the bullet column. */
  int bulletWidth() {
    return Math.max(8, lineHeight * 3 / 4);
  }

  /**
   * Returns the gutter width: the bullet column and, when shown, line numbers with room for the
   * digits of the line count and at least {@link #MIN_DIGITS}.
   *
   * @param lineNumbers whether line numbers show
   * @param lineCount the document's line count
   * @return pixels
   */
  int gutterWidth(boolean lineNumbers, long lineCount) {
    if (!lineNumbers) {
      return bulletWidth();
    }
    int digits = Math.max(MIN_DIGITS, Long.toString(lineCount).length());
    return bulletWidth() + (int) Math.ceil(digits * advance) + 2 * GUTTER_PAD;
  }

  /**
   * Returns the whole columns in a text area's width.
   *
   * @param width pixels right of the gutter
   * @return at least 1
   */
  int columns(int width) {
    return Math.max(1, (int) (width / advance));
  }

  /**
   * Returns the whole rows in a height.
   *
   * @param height pixels
   * @return at least 1
   */
  int rows(int height) {
    return Math.max(1, height / lineHeight);
  }

  /**
   * Maps a point to a line and column with text wrap off: row {@code y / lineHeight} from the top
   * line, clamped to the lines, and the column boundary nearest {@code textX}.
   *
   * @param topLine the first visible line
   * @param lineCount the lines; at least 1
   * @param firstColumn the first visible column
   * @param textX the x right of the gutter
   * @param y the y
   * @return the position
   */
  TextPosition hit(long topLine, long lineCount, long firstColumn, int textX, int y) {
    long line = topLine + Math.max(0, y) / lineHeight;
    line = Math.max(0, Math.min(line, lineCount - 1));
    return new TextPosition(line, firstColumn + columnAt(textX));
  }

  /**
   * Returns the rows a line takes when wrapped.
   *
   * @param width the line's width in columns, cut to the read window; negative for a line not read
   *     yet, which takes one row
   * @param wrapColumns columns per row; at least 1
   * @return at least 1
   */
  static int wrappedRows(long width, int wrapColumns) {
    if (width < 0) {
      return 1;
    }
    return (int) Math.max(1, (width + wrapColumns - 1) / wrapColumns);
  }

  /**
   * Counts the lines from the top that fit a height whole when wrapped.
   *
   * @param topLine the first visible line
   * @param lineCount the lines
   * @param rows each line's wrapped rows
   * @param height the view's height in pixels
   * @param screenRows the rows the view has room for, an upper bound on the result
   * @return at least 1
   */
  int wrapFit(long topLine, long lineCount, WrappedRows rows, int height, int screenRows) {
    int available = rows(height);
    int used = 0;
    int lines = 0;
    for (long line = topLine; line < lineCount; line++) {
      used += rows.rows(line);
      if (used > available) {
        break;
      }
      lines++;
    }
    return Math.max(1, Math.min(lines, screenRows));
  }

  /**
   * Walks the wrapped rows from the top line to the row under {@code y}.
   *
   * @param topLine the first visible line
   * @param lineCount the lines
   * @param rows each line's wrapped rows
   * @param y the y; 0 or less is the first row
   * @return the row; below the last line's rows, the line count and the segment past it
   */
  LineRow lineRow(long topLine, long lineCount, WrappedRows rows, int y) {
    int target = Math.max(0, y) / lineHeight;
    int row = 0;
    long line = topLine;
    while (line < lineCount && row + rows.rows(line) <= target) {
      row += rows.rows(line);
      line++;
    }
    return new LineRow(line, target - row);
  }

  /** As {@link #lineRow}, clamped to the last line and to the line's last segment. */
  private LineRow clampedRow(long topLine, long lineCount, WrappedRows rows, int y) {
    LineRow at = lineRow(topLine, lineCount, rows, y);
    long line = Math.max(0, Math.min(at.line(), lineCount - 1));
    int last = rows.rows(line) - 1;
    int segment = line == at.line() ? Math.min(at.segment(), last) : last;
    return new LineRow(line, Math.max(0, segment));
  }

  /** The column boundary nearest {@code textX} within a row's segment. */
  private TextPosition segmentHit(LineRow at, int wrapColumns, int textX) {
    long column = Math.min(columnAt(textX), wrapColumns);
    return new TextPosition(at.line(), (long) at.segment() * wrapColumns + column);
  }

  /**
   * Maps a point to a line and column with text wrap on: walks the wrapped rows from the top line
   * to the row under {@code y}, then takes the column boundary nearest {@code textX} within that
   * row's segment, clamped to the segment's end column.
   *
   * @param topLine the first visible line
   * @param lineCount the lines; at least 1
   * @param rows each line's wrapped rows
   * @param wrapColumns columns per row; at least 1
   * @param textX the x right of the gutter
   * @param y the y
   * @return the position
   */
  TextPosition wrappedHit(
      long topLine, long lineCount, WrappedRows rows, int wrapColumns, int textX, int y) {
    return segmentHit(clampedRow(topLine, lineCount, rows, y), wrapColumns, textX);
  }

  /**
   * Maps a point to a line and column with one line wrapped in place: the rows walk as in {@link
   * #wrappedHit}; on the wrapped line the column is taken within the row's segment, on any other
   * line it is {@code firstColumn} plus the column boundary nearest {@code textX}, as {@link #hit}
   * maps it.
   *
   * @param topLine the first visible line
   * @param lineCount the lines; at least 1
   * @param rows each line's rows: 1 except the wrapped line's
   * @param wrappedLine the line wrapped in place
   * @param wrapColumns columns per wrapped row; at least 1
   * @param firstColumn the first visible column of the other lines
   * @param textX the x right of the gutter
   * @param y the y
   * @return the position
   */
  TextPosition lineWrapHit(
      long topLine,
      long lineCount,
      WrappedRows rows,
      long wrappedLine,
      int wrapColumns,
      long firstColumn,
      int textX,
      int y) {
    LineRow at = clampedRow(topLine, lineCount, rows, y);
    if (at.line() != wrappedLine) {
      return new TextPosition(at.line(), firstColumn + columnAt(textX));
    }
    return segmentHit(at, wrapColumns, textX);
  }

  /**
   * Maps a double click to a column with text wrap on. As {@link #wrappedHit}, except that a point
   * at or past the right edge of a segment that is not the line's last maps to that segment's last
   * column instead of the boundary it shares with the next segment, so the word at the segment's
   * end is selected rather than the word that starts the next one.
   *
   * @param topLine the first visible line
   * @param lineCount the lines; at least 1
   * @param rows each line's wrapped rows
   * @param wrapColumns columns per row; at least 1
   * @param textX the x right of the gutter
   * @param y the y
   * @return the position
   */
  TextPosition wrappedWordHit(
      long topLine, long lineCount, WrappedRows rows, int wrapColumns, int textX, int y) {
    return segmentEnd(
        wrappedHit(topLine, lineCount, rows, wrapColumns, textX, y), rows, wrapColumns, textX);
  }

  /**
   * Maps a double click to a column with one line wrapped in place: {@link #lineWrapHit}, with the
   * wrapped line's segment ends mapped as {@link #wrappedWordHit} maps them.
   *
   * @param topLine the first visible line
   * @param lineCount the lines; at least 1
   * @param rows each line's rows: 1 except the wrapped line's
   * @param wrappedLine the line wrapped in place
   * @param wrapColumns columns per wrapped row; at least 1
   * @param firstColumn the first visible column of the other lines
   * @param textX the x right of the gutter
   * @param y the y
   * @return the position
   */
  TextPosition lineWrapWordHit(
      long topLine,
      long lineCount,
      WrappedRows rows,
      long wrappedLine,
      int wrapColumns,
      long firstColumn,
      int textX,
      int y) {
    TextPosition hit =
        lineWrapHit(topLine, lineCount, rows, wrappedLine, wrapColumns, firstColumn, textX, y);
    return hit.line() == wrappedLine ? segmentEnd(hit, rows, wrapColumns, textX) : hit;
  }

  /** Moves a hit at or past a segment's right edge, not on the line's last, back one column. */
  private TextPosition segmentEnd(TextPosition hit, WrappedRows rows, int wrapColumns, int textX) {
    if (columnAt(textX) < wrapColumns) {
      return hit;
    }
    long segment = hit.column() / wrapColumns - 1;
    if (segment >= rows.rows(hit.line()) - 1) {
      return hit;
    }
    return new TextPosition(hit.line(), hit.column() - 1);
  }

  /**
   * Returns the index of the char at a column of a decoded row, one column per code point (tabs are
   * already spaces).
   *
   * @param text the row
   * @param column a column counted from the row's first char
   * @return the index, at most {@code text.length()}
   */
  static int charIndex(String text, long column) {
    long seen = 0;
    int i = 0;
    while (i < text.length() && seen < column) {
      if (!Character.isLowSurrogate(text.charAt(i))) {
        seen++;
      }
      i++;
    }
    while (i < text.length() && Character.isLowSurrogate(text.charAt(i))) {
      i++;
    }
    return i;
  }

  /**
   * Reports whether every char is printable ASCII, so the row's glyphs sit on the grid.
   *
   * @param text the row
   * @return true for chars {@code 0x20}-{@code 0x7e} only
   */
  static boolean printableAscii(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < 0x20 || c > 0x7e) {
        return false;
      }
    }
    return true;
  }
}
