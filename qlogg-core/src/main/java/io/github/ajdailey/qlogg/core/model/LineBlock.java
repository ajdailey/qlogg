package io.github.ajdailey.qlogg.core.model;

/**
 * Decoded text of consecutive rows, cut to a column window, for display. Each row holds the display
 * columns {@code [firstColumn, firstColumn + columns)} with tabs expanded to spaces and a trailing
 * CR dropped, so its text is bounded by the window rather than by the line. In the main view row n
 * is line n; in the filtered view {@link #sourceLine} gives each row's document line. Immutable, so
 * a worker can build one and publish it to the UI thread.
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

  private final long firstLine;
  private final long firstColumn;
  private final int columns;
  private final String[] lines;
  private final long[] widths;
  private final long[] sourceLines;
  private final HighlightSpans[] spans;
  private final String[] whitespace;
  private final byte[] terminators;

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
    this(firstLine, firstColumn, columns, lines, widths, sourceLines, spans, null, null);
  }

  /**
   * Creates a block with whitespace markers; the arrays are kept, not copied.
   *
   * @param firstLine row of the first entry
   * @param firstColumn first display column held
   * @param columns width of the window
   * @param lines the cut text of each row
   * @param widths the full display width of each row, in columns
   * @param sourceLines the document line of each row, or null when row n is line n
   * @param spans the highlight of each row, or null for none
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
      String[] whitespace,
      byte[] terminators) {
    this.firstLine = firstLine;
    this.firstColumn = firstColumn;
    this.columns = columns;
    this.lines = lines;
    this.widths = widths;
    this.sourceLines = sourceLines;
    this.spans = spans;
    this.whitespace = whitespace;
    this.terminators = terminators;
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
   * Returns the heap the rows' highlights hold.
   *
   * @return bytes, the sum of {@link HighlightSpans#retainedBytes}
   */
  public long spanBytes() {
    if (spans == null) {
      return 0;
    }
    long bytes = 0;
    for (HighlightSpans row : spans) {
      bytes += row == null ? 0 : row.retainedBytes();
    }
    return bytes;
  }

  /**
   * Returns the block without highlights, as a read that cannot reserve their memory keeps it.
   *
   * @return a block sharing this one's text
   */
  public LineBlock withoutSpans() {
    return spans == null
        ? this
        : new LineBlock(
            firstLine,
            firstColumn,
            columns,
            lines,
            widths,
            sourceLines,
            null,
            whitespace,
            terminators);
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
