package io.github.ajdailey.qlogg.core.columns;

import io.github.ajdailey.qlogg.core.format.JsonStrings;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.time.TimestampParser;

/**
 * Serilog compact JSON (CLEF): the top-level members of one JSON object. Nested objects and arrays
 * are kept as raw text, nested at most {@link #MAX_DEPTH} deep; a deeper value, a malformed object
 * or text after it does not parse. Message is {@code @m}, else {@code @mt} with each property token
 * replaced by the property's value; Level defaults to {@code Information}; Properties lists the
 * members not named with one leading {@code @} as {@code name=value}, a {@code @@} name with one
 * {@code @}. Strings unescape leniently through {@link JsonStrings}.
 */
final class ClefParser extends FieldParser {
  /** Deepest nesting of a member value. */
  static final int MAX_DEPTH = 64;

  /** Members recorded per object; later ones are scanned and left out of Properties. */
  static final int MAX_MEMBERS = 1024;

  /** Template tokens replaced per message; later ones are kept as written. */
  static final int MAX_TOKENS = 1024;

  private static final String DEFAULT_LEVEL = "Information";
  private static final int TIME = 0;
  private static final int LEVEL = 1;
  private static final int MESSAGE = 2;
  private static final int EXCEPTION = 3;
  private static final int PROPERTIES = 4;
  private static final int TEMPLATE = 5;
  private static final int EVENT_ID = 6;

  private final TimestampParser iso;
  private final Span span = new Span();
  private final int[] keyStart = new int[MAX_MEMBERS];
  private final int[] keyEnd = new int[MAX_MEMBERS];
  private final int[] valueStart = new int[MAX_MEMBERS];
  private final int[] valueEnd = new int[MAX_MEMBERS];
  private final boolean[] string = new boolean[MAX_MEMBERS];
  private final int[] nameStart = new int[MAX_MEMBERS];
  private final int[] nameEnd = new int[MAX_MEMBERS];
  private final int[] propertyStart = new int[MAX_MEMBERS];
  private final int[] propertyEnd = new int[MAX_MEMBERS];
  private CharSequence text;
  private int length;
  private int pos;
  private int members;
  private int properties;

  ClefParser(TimeContext context) {
    super(ColumnFormat.CLEF.columns().size());
    iso = BuiltInFormat.ISO.parser(context);
  }

  @Override
  boolean read(CharSequence record) {
    text = record;
    length = record.length();
    pos = 0;
    members = 0;
    properties = 0;
    try {
      if (!object()) {
        return false;
      }
      fill();
      return true;
    } finally {
      text = null;
    }
  }

  /** Reads the object's members into the arrays. */
  private boolean object() {
    skipSpace();
    if (!is('{')) {
      return false;
    }
    skipSpace();
    if (!is('}')) {
      while (true) {
        skipSpace();
        if (!is('"')) {
          return false;
        }
        int keyFrom = pos;
        if (!skipString()) {
          return false;
        }
        int keyTo = pos - 1;
        skipSpace();
        if (!is(':')) {
          return false;
        }
        skipSpace();
        boolean quoted = pos < length && text.charAt(pos) == '"';
        int from = quoted ? pos + 1 : pos;
        if (!skipValue()) {
          return false;
        }
        int to = quoted ? pos - 1 : pos;
        if (members < MAX_MEMBERS) {
          keyStart[members] = keyFrom;
          keyEnd[members] = keyTo;
          valueStart[members] = from;
          valueEnd[members] = to;
          string[members] = quoted;
          members++;
        }
        skipSpace();
        if (is('}')) {
          break;
        }
        if (!is(',')) {
          return false;
        }
      }
    }
    skipSpace();
    return pos == length;
  }

  /** Writes the columns from the recorded members. */
  private void fill() {
    FieldValues values = values();
    int time = find("@t");
    if (time >= 0) {
      int start = value(time);
      values.close(TIME, start);
      long nanos = iso.parse(span.set(values.chars(), start, values.mark()));
      stamp(nanos, iso.fractionDigits());
    }
    int level = find("@l");
    int levelStart = values.mark();
    if (level >= 0) {
      value(level);
    } else {
      values.append(DEFAULT_LEVEL, 0, DEFAULT_LEVEL.length());
    }
    values.close(LEVEL, levelStart);
    column(EXCEPTION, find("@x"));
    column(EVENT_ID, find("@i"));
    propertiesColumn();
    int template = column(TEMPLATE, find("@mt"));
    int message = find("@m");
    if (message >= 0) {
      column(MESSAGE, message);
    } else if (template >= 0) {
      render(values.start(TEMPLATE), values.end(TEMPLATE));
    }
  }

  /** Writes a member as a column; returns the member or -1. */
  private int column(int column, int member) {
    if (member >= 0) {
      int start = value(member);
      values().close(column, start);
    }
    return member;
  }

  /** Writes the Properties column and records each property's name and value spans. */
  private void propertiesColumn() {
    FieldValues values = values();
    int start = values.mark();
    for (int m = 0; m < members; m++) {
      int from = keyStart[m];
      int to = keyEnd[m];
      boolean reserved = to > from && text.charAt(from) == '@';
      if (reserved && !(to - from > 1 && text.charAt(from + 1) == '@')) {
        continue;
      }
      if (properties > 0) {
        values.append(',');
        values.append(' ');
      }
      nameStart[properties] = values.mark();
      values.appendJson(text, reserved ? from + 1 : from, to);
      nameEnd[properties] = values.mark();
      values.append('=');
      propertyStart[properties] = value(m);
      propertyEnd[properties] = values.mark();
      properties++;
    }
    values.close(PROPERTIES, start);
  }

  /**
   * Writes the Message column from the template at {@code [from, to)} of the buffer: {@code {{} and
   * {@code }}} unescape; {@code {Name}}, {@code {@Name}}, {@code {$Name}} and {@code {Name:format}}
   * take the property's value; any other token stays as written.
   */
  private void render(int from, int to) {
    FieldValues values = values();
    CharSequence chars = values.chars();
    int start = values.mark();
    int tokens = 0;
    int i = from;
    while (i < to && values.room()) {
      char c = chars.charAt(i);
      if ((c == '{' || c == '}') && i + 1 < to && chars.charAt(i + 1) == c) {
        values.append(c);
        i += 2;
        continue;
      }
      if (c != '{') {
        values.append(c);
        i++;
        continue;
      }
      int close = indexOf(chars, '}', i + 1, to);
      if (close < 0) {
        values.copy(i, to);
        break;
      }
      int property = tokens < MAX_TOKENS ? property(chars, i + 1, close) : -1;
      tokens++;
      if (property >= 0) {
        values.copy(propertyStart[property], propertyEnd[property]);
      } else {
        values.copy(i, close + 1);
      }
      i = close + 1;
    }
    values.close(MESSAGE, start);
  }

  /** Returns the property a token body names, or -1. */
  private int property(CharSequence chars, int from, int to) {
    if (from < to && (chars.charAt(from) == '@' || chars.charAt(from) == '$')) {
      from++;
    }
    int end = from;
    while (end < to && chars.charAt(end) != ':' && chars.charAt(end) != ',') {
      end++;
    }
    int nameLength = end - from;
    for (int p = 0; p < properties; p++) {
      if (nameEnd[p] - nameStart[p] == nameLength && regionEquals(chars, nameStart[p], from, end)) {
        return p;
      }
    }
    return -1;
  }

  /** Appends a member's value to the buffer and returns its start. */
  private int value(int member) {
    FieldValues values = values();
    int start = values.mark();
    if (string[member]) {
      values.appendJson(text, valueStart[member], valueEnd[member]);
    } else {
      values.append(text, valueStart[member], valueEnd[member]);
    }
    return start;
  }

  /** Returns the first member with a key written exactly as {@code key}, or -1. */
  private int find(String key) {
    for (int m = 0; m < members; m++) {
      int from = keyStart[m];
      if (keyEnd[m] - from == key.length()) {
        int k = 0;
        while (k < key.length() && text.charAt(from + k) == key.charAt(k)) {
          k++;
        }
        if (k == key.length()) {
          return m;
        }
      }
    }
    return -1;
  }

  /** Skips a string's content and closing quote; false when it does not close. */
  private boolean skipString() {
    while (pos < length) {
      char c = text.charAt(pos);
      if (c == '"') {
        pos++;
        return true;
      }
      pos += c == '\\' ? 2 : 1;
    }
    return false;
  }

  /** Skips a string, an object or array up to {@link #MAX_DEPTH} deep, or a scalar. */
  private boolean skipValue() {
    if (pos >= length) {
      return false;
    }
    char first = text.charAt(pos);
    if (first == '"') {
      pos++;
      return skipString();
    }
    if (first != '{' && first != '[') {
      int start = pos;
      while (pos < length && !delimiter(text.charAt(pos))) {
        pos++;
      }
      return pos > start;
    }
    int depth = 0;
    while (pos < length) {
      char c = text.charAt(pos);
      pos++;
      if (c == '"') {
        if (!skipString()) {
          return false;
        }
      } else if (c == '{' || c == '[') {
        if (++depth > MAX_DEPTH) {
          return false;
        }
      } else if (c == '}' || c == ']') {
        if (--depth == 0) {
          return true;
        }
      }
    }
    return false;
  }

  private void skipSpace() {
    while (pos < length && space(text.charAt(pos))) {
      pos++;
    }
  }

  private boolean is(char expected) {
    if (pos < length && text.charAt(pos) == expected) {
      pos++;
      return true;
    }
    return false;
  }

  private static boolean delimiter(char c) {
    return c == ',' || c == '}' || c == ']' || space(c);
  }

  private static boolean space(char c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n';
  }

  private static int indexOf(CharSequence chars, char c, int from, int to) {
    for (int i = from; i < to; i++) {
      if (chars.charAt(i) == c) {
        return i;
      }
    }
    return -1;
  }

  private static boolean regionEquals(CharSequence chars, int a, int b, int bEnd) {
    for (int i = 0; i < bEnd - b; i++) {
      if (chars.charAt(a + i) != chars.charAt(b + i)) {
        return false;
      }
    }
    return true;
  }
}
