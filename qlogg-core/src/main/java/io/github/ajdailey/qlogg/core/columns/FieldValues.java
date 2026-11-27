package io.github.ajdailey.qlogg.core.columns;

import io.github.ajdailey.qlogg.core.format.JsonStrings;
import io.github.ajdailey.qlogg.core.format.XmlReferences;
import java.nio.CharBuffer;

/**
 * The column values of the record a {@link FieldParser} read last: one reused char array and a span
 * in it per column. Spans need not follow column order. The next parse overwrites the array, the
 * spans and every view {@link #value} returned. Not thread-safe.
 */
public final class FieldValues {
  /** Chars the buffer holds after each value is written; the rest of the value is dropped. */
  public static final int MAX_CHARS = 1 << 20;

  private char[] buffer = new char[256];
  private int length;
  private final StringBuilder unescaped = new StringBuilder(64);
  private final Chars chars = new Chars(-1);
  private final int[] starts;
  private final int[] ends;
  private final Chars[] views;
  private int count;
  private boolean truncated;

  FieldValues(int columns) {
    starts = new int[columns];
    ends = new int[columns];
    views = new Chars[columns];
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
    return chars;
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
    Chars view = views[column];
    if (view == null) {
      view = new Chars(column);
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
    length = 0;
    unescaped.setLength(0);
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

  /** Returns the buffer length, the start of the next value. */
  int mark() {
    return length;
  }

  /**
   * Reports whether the buffer is below {@link #MAX_CHARS}.
   *
   * @return true when room is left
   */
  boolean room() {
    return length < MAX_CHARS;
  }

  /**
   * Appends {@code text[from, to)} with JSON string escapes replaced, cut at {@link #MAX_CHARS}.
   */
  void appendJson(CharSequence text, int from, int to) {
    if (indexOf(text, '\\', from, to) < 0) {
      append(text, from, to);
    } else {
      JsonStrings.unescape(text, from, to, unescaped);
      moveUnescaped();
    }
  }

  /** Appends {@code text[from, to)} with XML references replaced, cut at {@link #MAX_CHARS}. */
  void appendXml(CharSequence text, int from, int to) {
    if (indexOf(text, '&', from, to) < 0) {
      append(text, from, to);
    } else {
      XmlReferences.unescape(text, from, to, unescaped);
      moveUnescaped();
    }
  }

  private static int indexOf(CharSequence text, char c, int from, int to) {
    for (int i = from; i < to; i++) {
      if (text.charAt(i) == c) {
        return i;
      }
    }
    return -1;
  }

  private void moveUnescaped() {
    int moved = Math.min(unescaped.length(), MAX_CHARS - length);
    if (moved < unescaped.length()) {
      truncated = true;
    }
    ensure(length + moved);
    unescaped.getChars(0, moved, buffer, length);
    length += moved;
    unescaped.setLength(0);
  }

  /**
   * Appends {@code text[from, to)}, cut at {@link #MAX_CHARS}. A String or an array-backed {@link
   * CharBuffer} is copied in bulk.
   */
  void append(CharSequence text, int from, int to) {
    int room = MAX_CHARS - length;
    if (to - from > room) {
      to = from + Math.max(0, room);
      truncated = true;
    }
    ensure(length + to - from);
    if (text instanceof String string) {
      string.getChars(from, to, buffer, length);
      length += to - from;
    } else if (text instanceof CharBuffer chars && chars.hasArray()) {
      int offset = chars.arrayOffset() + chars.position();
      System.arraycopy(chars.array(), offset + from, buffer, length, to - from);
      length += to - from;
    } else {
      for (int i = from; i < to; i++) {
        buffer[length++] = text.charAt(i);
      }
    }
  }

  /** Appends {@code chars[from, to)}, cut at {@link #MAX_CHARS}. */
  void append(char[] chars, int from, int to) {
    int n = Math.min(to - from, MAX_CHARS - length);
    if (n < to - from) {
      truncated = true;
    }
    ensure(length + n);
    System.arraycopy(chars, from, buffer, length, n);
    length += n;
  }

  /** Appends a char unless the buffer is full. */
  void append(char c) {
    if (length < MAX_CHARS) {
      if (length == buffer.length) {
        ensure(length + 1);
      }
      buffer[length++] = c;
    } else {
      truncated = true;
    }
  }

  /** Copies the buffer's own {@code [from, to)} to its end, cut at {@link #MAX_CHARS}. */
  void copy(int from, int to) {
    int n = to - from;
    if (n > MAX_CHARS - length) {
      n = MAX_CHARS - length;
      truncated = true;
    }
    ensure(length + n);
    System.arraycopy(buffer, from, buffer, length, n);
    length += n;
  }

  /** Sets a column's span. */
  void set(int column, int start, int end) {
    starts[column] = Math.min(start, length);
    ends[column] = Math.min(end, length);
  }

  /** Sets a column's span from {@code start} to the buffer's end. */
  void close(int column, int start) {
    set(column, start, length);
  }

  /** Grows the buffer to hold {@code needed} chars, doubling up to {@link #MAX_CHARS}. */
  private void ensure(int needed) {
    if (needed > buffer.length) {
      int size = buffer.length;
      while (size < needed) {
        size = Math.min(MAX_CHARS, size * 2);
      }
      char[] grown = new char[size];
      System.arraycopy(buffer, 0, grown, 0, length);
      buffer = grown;
    }
  }

  /** One column's chars, or with column -1 the whole buffer. */
  private final class Chars implements CharSequence {
    private final int column;

    Chars(int column) {
      this.column = column;
    }

    private int from() {
      return column < 0 ? 0 : starts[column];
    }

    @Override
    public int length() {
      return column < 0 ? length : ends[column] - starts[column];
    }

    @Override
    public char charAt(int index) {
      if (index < 0 || index >= length()) {
        throw new IndexOutOfBoundsException(index);
      }
      return buffer[from() + index];
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      if (start < 0 || end > length() || start > end) {
        throw new IndexOutOfBoundsException(start + ".." + end);
      }
      return new String(buffer, from() + start, end - start);
    }

    @Override
    public String toString() {
      return new String(buffer, from(), length());
    }
  }
}
