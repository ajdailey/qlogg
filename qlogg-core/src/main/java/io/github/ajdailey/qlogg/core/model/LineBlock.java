package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.text.ControlChars;

/**
 * Decoded text of consecutive rows, cut to a column window, for display. Each row holds the display
 * columns {@code [firstColumn, firstColumn + columns)} with tabs expanded to spaces, a {@link
 * ControlChars#boxed} char as a space per column (its box in {@link #controls}) and a trailing CR
 * dropped, so its text is bounded by the window rather than by the line. In the main view row n is
 * line n; in the filtered view {@link #sourceLine} gives each row's document line. Immutable, so a
 * worker can build one and publish it to the UI thread.
 *
 * <p>In column view ({@link #cellView}) a row is a record composed into cells ({@link CellReader}):
 * its text holds the composed columns {@code [0, frozenColumns())} and then {@code [frozenColumns()
 * + firstColumn, frozenColumns() + firstColumn + columns)}, and highlight, token and cell columns
 * are composed columns.
 */
public final class LineBlock {
  /** A block of no lines. */
  public static final LineBlock EMPTY =
      new LineBlock(0, 0, 0, new String[0], new long[0], null, null);

  /** Marker of a tab's first cell in {@link #whitespace}. */
  public static final char TAB_MARK = 'T';

  /** Marker of a trailing space in {@link #whitespace}. */
  public static final char SPACE_MARK = 'S';

  /** Marker of a lone CR, shown as a space, in {@link #whitespace}. */
  public static final char CR_MARK = 'R';

  /** Added to a boxed char in the second column of its box in {@link #controls}. */
  public static final char SECOND_CELL = 0x100;

  /** Row flag: the record did not parse and its text spans every cell. */
  static final byte UNPARSED = 1;

  /** Row flag: a header line. */
  static final byte HEADER = 2;

  /** Row flag: a cut record. */
  static final byte CUT = 4;

  private final long firstLine;
  private final long firstColumn;
  private final int columns;
  private final String[] lines;
  private final long[] widths;
  private final long[] sourceLines;
  private final HighlightSpans[] spans;
  private final TokenSpans[] tokens;
  private final String[] whitespace;
  private final byte[] terminators;
  private final String[] controls;
  private final int[][] cells;
  private final byte[] flags;
  private final int frozenColumns;

  /**
   * Creates a block; the arrays are kept, not copied.
   *
   * @param firstLine row of the first entry
   * @param firstColumn first display column held
   * @param columns width of the window
   * @param lines the cut text of each row
   * @param widths the full display width of each row, in columns
   * @param sourceLines the document line of each row, or null when row n is line n
   * @param spans the highlight of each row, or null for none
   */
  LineBlock(
      long firstLine,
      long firstColumn,
      int columns,
      String[] lines,
      long[] widths,
      long[] sourceLines,
      HighlightSpans[] spans) {
    this(firstLine, firstColumn, columns, lines, widths, sourceLines, spans, null, null, null);
  }

  /**
   * Creates a block with tokens and whitespace markers; the arrays are kept, not copied.
   *
   * @param firstLine row of the first entry
   * @param firstColumn first display column held
   * @param columns width of the window
   * @param lines the cut text of each row
   * @param widths the full display width of each row, in columns
   * @param sourceLines the document line of each row, or null when row n is line n
   * @param spans the highlight of each row, or null for none
   * @param tokens the syntax-color tokens of each row, or null for none
   * @param whitespace per row, one marker per char of its text, or null for none
   * @param terminators per row, a {@link LineTerminator} ordinal, or null with no markers
   */
  LineBlock(
      long firstLine,
      long firstColumn,
      int columns,
      String[] lines,
      long[] widths,
      long[] sourceLines,
      HighlightSpans[] spans,
      TokenSpans[] tokens,
      String[] whitespace,
      byte[] terminators) {
    this(
        firstLine,
        firstColumn,
        columns,
        lines,
        widths,
        sourceLines,
        spans,
        tokens,
        whitespace,
        terminators,
        null);
  }

  /**
   * Creates a block with tokens, whitespace markers and control boxes; the arrays are kept, not
   * copied.
   *
   * @param firstLine row of the first entry
   * @param firstColumn first display column held
   * @param columns width of the window
   * @param lines the cut text of each row
   * @param widths the full display width of each row, in columns
   * @param sourceLines the document line of each row, or null when row n is line n
   * @param spans the highlight of each row, or null for none
   * @param tokens the syntax-color tokens of each row, or null for none
   * @param whitespace per row, one marker per char of its text, or null for none
   * @param terminators per row, a {@link LineTerminator} ordinal, or null with no markers
   * @param controls per row, the {@link #controls} cells, or null for none
   */
  LineBlock(
      long firstLine,
      long firstColumn,
      int columns,
      String[] lines,
      long[] widths,
      long[] sourceLines,
      HighlightSpans[] spans,
      TokenSpans[] tokens,
      String[] whitespace,
      byte[] terminators,
      String[] controls) {
    this(
        firstLine,
        firstColumn,
        columns,
        lines,
        widths,
        sourceLines,
        spans,
        tokens,
        whitespace,
        terminators,
        controls,
        null,
        null,
        0);
  }

  /**
   * Creates a block with every per-row array; the arrays are kept, not copied.
   *
   * @param firstLine row of the first entry
   * @param firstColumn first display column held
   * @param columns width of the window
   * @param lines the cut text of each row
   * @param widths the full display width of each row, in columns
   * @param sourceLines the document line of each row, or null when row n is line n
   * @param spans the highlight of each row, or null for none
   * @param tokens the syntax-color tokens of each row, or null for none
   * @param whitespace per row, one marker per char of its text, or null for none
   * @param terminators per row, a {@link LineTerminator} ordinal, or null with no markers
   * @param controls per row, the {@link #controls} cells, or null for none
   * @param cells per row, the composed column of each cell, or null outside column view
   * @param flags per row, {@link #UNPARSED}, {@link #HEADER} and {@link #CUT}, or null
   * @param frozenColumns composed columns that do not scroll
   */
  LineBlock(
      long firstLine,
      long firstColumn,
      int columns,
      String[] lines,
      long[] widths,
      long[] sourceLines,
      HighlightSpans[] spans,
      TokenSpans[] tokens,
      String[] whitespace,
      byte[] terminators,
      String[] controls,
      int[][] cells,
      byte[] flags,
      int frozenColumns) {
    this.firstLine = firstLine;
    this.firstColumn = firstColumn;
    this.columns = columns;
    this.lines = lines;
    this.widths = widths;
    this.sourceLines = sourceLines;
    this.spans = spans;
    this.tokens = tokens;
    this.whitespace = whitespace;
    this.terminators = terminators;
    this.controls = controls;
    this.cells = cells;
    this.flags = flags;
    this.frozenColumns = frozenColumns;
  }

  /**
   * Reports whether the rows are column-view records.
   *
   * @return true when {@link #cellCount} describes the rows
   */
  public boolean cellView() {
    return cells != null;
  }

  /**
   * Returns the composed columns at the start of each row that do not scroll.
   *
   * @return 0 outside column view or without frozen cells
   */
  public int frozenColumns() {
    return frozenColumns;
  }

  /**
   * Counts a held row's cells.
   *
   * @param line a row that {@link #contains}
   * @return the cells; 1 for a row that did not parse, 0 outside column view
   */
  public int cellCount(long line) {
    int i = index(line);
    return cells == null ? 0 : cells[i].length;
  }

  /**
   * Returns a cell's first composed column.
   *
   * @param line a row that {@link #contains}
   * @param cell 0 to {@link #cellCount} - 1
   * @return the composed column
   */
  public int cellStart(long line, int cell) {
    return cells[index(line)][cell];
  }

  /**
   * Reports whether a held row's record did not parse; its text spans every cell.
   *
   * @param line a row that {@link #contains}
   * @return true for raw text
   */
  public boolean unparsed(long line) {
    return flag(line, UNPARSED);
  }

  /**
   * Reports whether a held row is a header line.
   *
   * @param line a row that {@link #contains}
   * @return true for a header row
   */
  public boolean header(long line) {
    return flag(line, HEADER);
  }

  /**
   * Reports whether a held row's record is cut ({@link CellRecord#cut}), so the row shows the
   * truncation marker.
   *
   * @param line a row that {@link #contains}
   * @return true for a cut record
   */
  public boolean cut(long line) {
    return flag(line, CUT);
  }

  private boolean flag(long line, byte flag) {
    int i = index(line);
    return flags != null && (flags[i] & flag) != 0;
  }

  /**
   * Returns a held row's control boxes: one char per char of {@link #text}, a space where the text
   * shows its own char, the boxed char in the first column of its box and the char plus {@link
   * #SECOND_CELL} in the second.
   *
   * @param line a row that {@link #contains}
   * @return the cells, or null when the row shows no box
   */
  public String controls(long line) {
    int i = index(line);
    return controls == null ? null : controls[i];
  }

  /**
   * Returns a held row's whitespace markers: one char per char of {@link #text}, {@link #TAB_MARK},
   * {@link #SPACE_MARK}, {@link #CR_MARK} or a space for none.
   *
   * @param line a row that {@link #contains}
   * @return the markers, or null when the block was read without them
   */
  public String whitespace(long line) {
    int i = index(line);
    return whitespace == null ? null : whitespace[i];
  }

  /**
   * Returns how a held row's line ends.
   *
   * @param line a row that {@link #contains}
   * @return the terminator, or null when the block was read without markers
   */
  public LineTerminator terminator(long line) {
    int i = index(line);
    return terminators == null ? null : LineTerminator.values()[terminators[i]];
  }

  /**
   * Returns a held row's highlight.
   *
   * @param line a row that {@link #contains}
   * @return the spans, {@link HighlightSpans#NONE} when the row has none
   */
  public HighlightSpans spans(long line) {
    int i = index(line);
    return spans == null || spans[i] == null ? HighlightSpans.NONE : spans[i];
  }

  /**
   * Returns a held row's syntax-color tokens.
   *
   * @param line a row that {@link #contains}
   * @return the tokens, {@link TokenSpans#NONE} when the row has none
   */
  public TokenSpans tokens(long line) {
    int i = index(line);
    return tokens == null || tokens[i] == null ? TokenSpans.NONE : tokens[i];
  }

  /**
   * Returns the heap the rows' highlights and tokens hold.
   *
   * @return bytes, the sum of {@link HighlightSpans#retainedBytes} and {@link
   *     TokenSpans#retainedBytes}
   */
  public long spanBytes() {
    long bytes = 0;
    if (spans != null) {
      for (HighlightSpans row : spans) {
        bytes += row == null ? 0 : row.retainedBytes();
      }
    }
    if (tokens != null) {
      for (TokenSpans row : tokens) {
        bytes += row == null ? 0 : row.retainedBytes();
      }
    }
    return bytes;
  }

  /**
   * Returns the block without highlights and tokens, as a read that cannot reserve their memory
   * keeps it.
   *
   * @return a block sharing this one's text
   */
  public LineBlock withoutSpans() {
    return spans == null && tokens == null
        ? this
        : new LineBlock(
            firstLine,
            firstColumn,
            columns,
            lines,
            widths,
            sourceLines,
            null,
            null,
            whitespace,
            terminators,
            controls,
            cells,
            flags,
            frozenColumns);
  }

  /**
   * Returns a held row's document line.
   *
   * @param line a row that {@link #contains}
   * @return the 0-based document line
   */
  public long sourceLine(long line) {
    int i = index(line);
    return sourceLines == null ? line : sourceLines[i];
  }

  /**
   * Returns the first line held.
   *
   * @return a 0-based document line
   */
  public long firstLine() {
    return firstLine;
  }

  /**
   * Counts the lines held.
   *
   * @return lines
   */
  public int count() {
    return lines.length;
  }

  /**
   * Returns the first display column held.
   *
   * @return a 0-based column
   */
  public long firstColumn() {
    return firstColumn;
  }

  /**
   * Returns the width of the column window.
   *
   * @return columns
   */
  public int columns() {
    return columns;
  }

  /**
   * Reports whether a document line is held.
   *
   * @param line a 0-based document line
   * @return true when {@link #text} returns its text
   */
  public boolean contains(long line) {
    return line >= firstLine && line - firstLine < lines.length;
  }

  /**
   * Returns a held line's text inside the column window.
   *
   * @param line a 0-based document line that {@link #contains}
   * @return the text; empty when the line ends before the window
   */
  public String text(long line) {
    return lines[index(line)];
  }

  /**
   * Returns a held line's full display width.
   *
   * @param line a 0-based document line that {@link #contains}
   * @return columns, tabs expanded
   */
  public long width(long line) {
    return widths[index(line)];
  }

  /**
   * Returns the widest held line's display width.
   *
   * @return columns
   */
  public long maxWidth() {
    long max = 0;
    for (long width : widths) {
      max = Math.max(max, width);
    }
    return max;
  }

  private int index(long line) {
    if (!contains(line)) {
      throw new IndexOutOfBoundsException("Line " + line + " is not in the block");
    }
    return (int) (line - firstLine);
  }
}
