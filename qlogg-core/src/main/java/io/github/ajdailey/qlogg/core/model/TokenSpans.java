package io.github.ajdailey.qlogg.core.model;

/**
 * The syntax-color tokens of one displayed row: sorted spans that do not overlap, each with a
 * {@link TokenKind}. Columns are display columns of the line, clipped to the column window the row
 * was read for. Text outside every span is no token. Immutable.
 *
 * <p>Cost ({@link #retainedBytes}): {@link #BYTES_PER_SPAN} bytes per span plus {@link #OVERHEAD}.
 */
public final class TokenSpans {
  /** A row with no token. */
  public static final TokenSpans NONE = new TokenSpans(new int[0], 0);

  /** Bytes per span: start, end and kind. */
  public static final int BYTES_PER_SPAN = 12;

  /** Bytes per instance beyond its spans: the object and its array header. */
  public static final int OVERHEAD = 40;

  private static final TokenKind[] KINDS = TokenKind.values();

  private final int[] data;
  private final int count;

  TokenSpans(int[] data, int count) {
    this.data = data;
    this.count = count;
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
    return data[3 * index(i)];
  }

  /**
   * Returns the column after a span.
   *
   * @param i 0 to {@code count() - 1}
   * @return the display column, greater than {@link #start}
   */
  public int end(int i) {
    return data[3 * index(i) + 1];
  }

  /**
   * Returns a span's kind.
   *
   * @param i 0 to {@code count() - 1}
   * @return the kind
   */
  public TokenKind kind(int i) {
    return KINDS[data[3 * index(i) + 2]];
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
