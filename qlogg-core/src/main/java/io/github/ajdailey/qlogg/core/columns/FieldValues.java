package io.github.ajdailey.qlogg.core.columns;

/**
 * The column values of the record a {@link FieldParser} read last: one reused char buffer and a
 * span in it per column. Spans need not follow column order. The next parse overwrites the buffer,
 * the spans and every view {@link #value} returned. Not thread-safe.
 */
public final class FieldValues {
  /** Chars the buffer holds after each value is written; the rest of the value is dropped. */
  public static final int MAX_CHARS = 1 << 20;

  private final StringBuilder buffer = new StringBuilder(256);
  private final int[] starts;
  private final int[] ends;
  private final Value[] views;
  private int count;
  private boolean truncated;

  FieldValues(int columns) {
    starts = new int[columns];
    ends = new int[columns];
    views = new Value[columns];
  }

  /**
   * Returns the column count of a parsed record.
   *
   * @return the schema's column count, or 0 after a record that did not parse
   */
  public int count() {
    return count;
  }

  /**
   * Returns the buffer the spans index.
   *
   * @return the chars, valid until the next parse
   */
  public CharSequence chars() {
    return buffer;
  }

  /**
   * Returns a column's first index in {@link #chars}.
   *
   * @param column 0 to {@link #count} - 1
   * @return the index
   */
  public int start(int column) {
    return starts[column];
  }

  /**
   * Returns the index after a column's last char in {@link #chars}.
   *
   * @param column 0 to {@link #count} - 1
   * @return the index
   */
  public int end(int column) {
    return ends[column];
  }

  /**
   * Returns a column's value as a view reused for that column.
   *
   * @param column 0 to {@link #count} - 1
   * @return the chars, valid until the next parse
   */
  public CharSequence value(int column) {
    Value view = views[column];
    if (view == null) {
      view = new Value(column);
      views[column] = view;
    }
    return view;
  }

  /**
   * Reports whether the last record's values reached {@link #MAX_CHARS} and were cut.
   *
   * @return true when a value is incomplete
   */
  public boolean truncated() {
    return truncated;
  }

  /** Starts a parsed record with every column empty. */
  void reset() {
    buffer.setLength(0);
    count = starts.length;
    truncated = false;
    for (int i = 0; i < count; i++) {
      starts[i] = 0;
      ends[i] = 0;
    }
  }

  /** Marks the record unparsed. */
  void fail() {
    reset();
    count = 0;
  }

  /** Returns the buffer for appending; call {@link #limit} after each append. */
  StringBuilder builder() {
    return buffer;
  }

  /** Returns the buffer length, the start of the next value. */
  int mark() {
    return buffer.length();
  }

  /**
   * Cuts the buffer to {@link #MAX_CHARS}.
   *
   * @return true when room is left
   */
  boolean limit() {
    if (buffer.length() >= MAX_CHARS) {
      if (buffer.length() > MAX_CHARS) {
        buffer.setLength(MAX_CHARS);
        truncated = true;
      }
      return false;
    }
    return true;
  }

  /** Appends {@code text[from, to)}, cut at {@link #MAX_CHARS}. */
  void append(CharSequence text, int from, int to) {
    int room = MAX_CHARS - buffer.length();
    if (to - from > room) {
      to = from + Math.max(0, room);
      truncated = true;
    }
    buffer.append(text, from, to);
  }

  /** Appends a char unless the buffer is full. */
  void append(char c) {
    if (buffer.length() < MAX_CHARS) {
      buffer.append(c);
    } else {
      truncated = true;
    }
  }

  /** Copies the buffer's own {@code [from, to)} to its end, cut at {@link #MAX_CHARS}. */
  void copy(int from, int to) {
    for (int i = from; i < to; i++) {
      if (buffer.length() >= MAX_CHARS) {
        truncated = true;
        return;
      }
      buffer.append(buffer.charAt(i));
    }
  }

  /** Sets a column's span. */
  void set(int column, int start, int end) {
    starts[column] = Math.min(start, buffer.length());
    ends[column] = Math.min(end, buffer.length());
  }

  /** Sets a column's span from {@code start} to the buffer's end. */
  void close(int column, int start) {
    set(column, start, buffer.length());
  }

  /** One column's chars in the buffer. */
  private final class Value implements CharSequence {
    private final int column;

    Value(int column) {
      this.column = column;
    }

    @Override
    public int length() {
      return ends[column] - starts[column];
    }

    @Override
    public char charAt(int index) {
      if (index < 0 || index >= length()) {
        throw new IndexOutOfBoundsException(index);
      }
      return buffer.charAt(starts[column] + index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return buffer.subSequence(starts[column] + start, starts[column] + end);
    }

    @Override
    public String toString() {
      return buffer.substring(starts[column], ends[column]);
    }
  }
}
