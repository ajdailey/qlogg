package io.github.ajdailey.qlogg.core.columns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.time.TimeContext;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Shared values and helpers for the {@code core.columns} tests. */
final class ColumnFixture {
  static final TimeContext UTC = new TimeContext(ZoneOffset.UTC, 2026, 3);

  private ColumnFixture() {}

  /** Returns every column value of a parsed record as strings. */
  static List<String> values(FieldParser parser) {
    FieldValues values = parser.values();
    List<String> list = new ArrayList<String>();
    for (int i = 0; i < values.count(); i++) {
      list.add(values.value(i).toString());
    }
    return list;
  }

  /** Parses a record that must parse and returns its values. */
  static List<String> parsed(FieldParser parser, CharSequence record) {
    assertTrue(parser.parse(record), record.toString());
    return values(parser);
  }

  /** Asserts that a record does not parse and leaves no values. */
  static void unparsed(FieldParser parser, CharSequence record) {
    assertEquals(false, parser.parse(record), record.toString());
    assertEquals(0, parser.values().count(), record.toString());
  }

  /** Returns a random string of {@code length} chars drawn from {@code alphabet}. */
  static String random(Random random, String alphabet, int length) {
    StringBuilder text = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      text.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return text.toString();
  }

  /** Chars that count {@link #charAt} reads. */
  static final class CountingChars implements CharSequence {
    private final CharSequence text;
    private long reads;

    CountingChars(CharSequence text) {
      this.text = text;
    }

    long reads() {
      return reads;
    }

    @Override
    public int length() {
      return text.length();
    }

    @Override
    public char charAt(int index) {
      reads++;
      return text.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      reads += end - start;
      return text.subSequence(start, end);
    }

    @Override
    public String toString() {
      return text.toString();
    }
  }
}
