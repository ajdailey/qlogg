package io.github.ajdailey.qlogg.core.model;

/**
 * The colors of one displayed row: an optional whole-line color pair, then sorted spans that do not
 * overlap, each with its own pair. Columns are display columns of the line, clipped to the column
 * window the row was read for. A color is packed {@code 0xAARRGGBB}; alpha 0 means no color, so the
 * layer below shows. Immutable.
 *
 * <p>Cost ({@link #retainedBytes}): {@link #BYTES_PER_SPAN} bytes per span plus {@link #OVERHEAD}.
 */
public final class HighlightSpans {
  /** A row with no highlight. */
  public static final HighlightSpans NONE = new HighlightSpans(0, 0, new int[0], 0);

  /** Bytes per span: start, end and two colors. */
  public static final int BYTES_PER_SPAN = 16;

  /** Bytes per instance beyond its spans. */
  public static final int OVERHEAD = 32;

  private final int lineForeground;
  private final int lineBackground;
  private final int[] data;
  private final int count;

  HighlightSpans(int lineForeground, int lineBackground, int[] data, int count) {
    this.lineForeground = lineForeground;
    this.lineBackground = lineBackground;
    this.data = data;
    this.count = count;
  }

  /**
   * Reports whether the row has no color at all.
   *
   * @return true for no line color and no spans
   */
  public boolean isEmpty() {
    return count == 0 && lineForeground == 0 && lineBackground == 0;
  }

  /**
   * Returns the whole line's text color.
   *
   * @return {@code 0xAARRGGBB}, 0 for none
   */
  public int lineForeground() {
    return lineForeground;
  }

  /**
   * Returns the whole line's background.
   *
   * @return {@code 0xAARRGGBB}, 0 for none
   */
  public int lineBackground() {
    return lineBackground;
  }

  /**
   * Counts the spans.
   *
   * @return spans, at most {@link Highlighter#MAX_SPANS}
   */
  public int count() {
    return count;
  }

  /**
   * Returns a span's first column.
   *
   * @param i 0 to {@code count() - 1}
   * @return the display column
   */
  public int start(int i) {
    return data[4 * index(i)];
  }

  /**
   * Returns the column after a span.
   *
   * @param i 0 to {@code count() - 1}
   * @return the display column, greater than {@link #start}
   */
  public int end(int i) {
    return data[4 * index(i) + 1];
  }

  /**
   * Returns a span's text color.
   *
   * @param i 0 to {@code count() - 1}
   * @return {@code 0xAARRGGBB}, 0 to keep the line's
   */
  public int foreground(int i) {
    return data[4 * index(i) + 2];
  }

  /**
   * Returns a span's background.
   *
   * @param i 0 to {@code count() - 1}
   * @return {@code 0xAARRGGBB}, 0 to keep the line's
   */
  public int background(int i) {
    return data[4 * index(i) + 3];
  }

  /**
   * Returns the heap this instance holds, by the formula in the class description.
   *
   * @return bytes
   */
  public long retainedBytes() {
    return this == NONE ? 0 : OVERHEAD + (long) BYTES_PER_SPAN * count;
  }

  private int index(int i) {
    if (i < 0 || i >= count) {
      throw new IndexOutOfBoundsException(i);
    }
    return i;
  }
}
