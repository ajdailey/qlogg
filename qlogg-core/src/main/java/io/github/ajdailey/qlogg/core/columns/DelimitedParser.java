package io.github.ajdailey.qlogg.core.columns;

import java.nio.CharBuffer;

/**
 * CSV and TSV fields with RFC 4180 quoting. A quote opens a quoted field only at a field start;
 * elsewhere it is literal. Inside quotes {@code ""} is one quote and a delimiter, CR or LF is part
 * of the value; chars after the closing quote up to the delimiter are kept. Fields past the last
 * column join it with the delimiter; missing fields are empty. Every record parses. The parse reads
 * a char array a run at a time: an array-backed {@link CharBuffer}'s own, else a reused copy of the
 * record.
 */
final class DelimitedParser extends FieldParser {
  private final char delimiter;
  private final int columns;
  private char[] copy = new char[256];

  DelimitedParser(char delimiter, int columns) {
    super(columns);
    this.delimiter = delimiter;
    this.columns = columns;
  }

  @Override
  boolean read(CharSequence record) {
    if (record instanceof CharBuffer buffer && buffer.hasArray()) {
      int from = buffer.arrayOffset() + buffer.position();
      read(buffer.array(), from, from + buffer.remaining());
      return true;
    }
    int length = record.length();
    if (copy.length < length) {
      copy = new char[Math.max(length, 2 * copy.length)];
    }
    for (int i = 0; i < length; i++) {
      copy[i] = record.charAt(i);
    }
    read(copy, 0, length);
    return true;
  }

  private void read(char[] chars, int from, int to) {
    FieldValues values = values();
    int i = from < to && chars[from] == '﻿' ? from + 1 : from;
    int column = 0;
    int start = values.mark();
    while (true) {
      if (i < to && chars[i] == '"') {
        i = quoted(chars, i + 1, to, values);
      }
      int run = i;
      while (i < to && chars[i] != delimiter) {
        i++;
      }
      values.append(chars, run, i);
      if (i >= to) {
        values.close(column, start);
        return;
      }
      i++;
      if (column == columns - 1) {
        values.append(delimiter);
      } else {
        values.close(column, start);
        column++;
        start = values.mark();
      }
    }
  }

  /** Appends a quoted value starting after its quote and returns the index after its close. */
  private static int quoted(char[] chars, int i, int to, FieldValues values) {
    while (true) {
      int run = i;
      while (i < to && chars[i] != '"') {
        i++;
      }
      values.append(chars, run, i);
      if (i >= to) {
        return i;
      }
      if (i + 1 < to && chars[i + 1] == '"') {
        values.append('"');
        i += 2;
      } else {
        return i + 1;
      }
    }
  }
}
