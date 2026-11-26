package io.github.ajdailey.qlogg.core.columns;

import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.time.TimeReader;
import io.github.ajdailey.qlogg.core.time.TimestampParser;

/**
 * Apache access log lines: {@code host ident user [time] "request" status size}, then optionally
 * {@code "referer" "user agent"} (combined format). Request, Referer and User agent are kept as
 * written, a backslash escape included; a value of {@code -} is empty. A line with any other shape,
 * trailing text included, does not parse.
 */
final class ClfParser extends FieldParser {
  private static final int HOST = 0;
  private static final int TIME = 3;
  private static final int REQUEST = 4;
  private static final int STATUS = 5;
  private static final int SIZE = 6;
  private static final int REFERER = 7;
  private static final int AGENT = 8;

  private final TimestampParser time;
  private final Span span = new Span();
  private CharSequence text;
  private int length;
  private int pos;

  ClfParser(TimeContext context) {
    super(ColumnFormat.CLF.columns().size());
    time = BuiltInFormat.CLF.parser(context);
  }

  @Override
  boolean read(CharSequence record) {
    text = record;
    length = record.length();
    pos = 0;
    try {
      return fields();
    } finally {
      text = null;
    }
  }

  private boolean fields() {
    for (int column = HOST; column < TIME; column++) {
      int start = pos;
      while (pos < length && text.charAt(pos) != ' ') {
        pos++;
      }
      if (pos == start || !space()) {
        return false;
      }
      put(column, start, pos - 1);
    }
    if (pos >= length || text.charAt(pos) != '[') {
      return false;
    }
    int open = pos;
    while (pos < length && text.charAt(pos) != ']') {
      pos++;
    }
    if (pos >= length) {
      return false;
    }
    long nanos = time.parse(span.set(text, open, Math.min(length, open + TimeReader.PREFIX_CHARS)));
    if (nanos == TimestampParser.NONE || open + time.end() != pos + 1) {
      return false;
    }
    stamp(nanos, time.fractionDigits());
    put(TIME, open + 1, pos);
    pos++;
    if (!space() || !quoted(REQUEST) || !space()) {
      return false;
    }
    if (!token(STATUS, true) || !space() || !token(SIZE, false)) {
      return false;
    }
    if (pos < length
        && text.charAt(pos) == ' '
        && pos + 1 < length
        && text.charAt(pos + 1) == '"') {
      pos++;
      if (!quoted(REFERER) || !space() || !quoted(AGENT)) {
        return false;
      }
    }
    while (pos < length && text.charAt(pos) == ' ') {
      pos++;
    }
    return pos == length;
  }

  /** Reads a quoted value; a backslash escapes the next char. */
  private boolean quoted(int column) {
    if (pos >= length || text.charAt(pos) != '"') {
      return false;
    }
    int start = ++pos;
    while (pos < length) {
      char c = text.charAt(pos);
      if (c == '"') {
        put(column, start, pos);
        pos++;
        return true;
      }
      pos += c == '\\' ? 2 : 1;
    }
    return false;
  }

  /** Reads three status digits, or a size of digits or {@code -}. */
  private boolean token(int column, boolean status) {
    int start = pos;
    while (pos < length && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') {
      pos++;
    }
    if (status ? pos - start != 3 : pos == start) {
      if (status || pos >= length || text.charAt(pos) != '-') {
        return false;
      }
      pos++;
    }
    put(column, start, pos);
    return true;
  }

  private boolean space() {
    if (pos < length && text.charAt(pos) == ' ') {
      pos++;
      return true;
    }
    return false;
  }

  /** Copies {@code text[from, to)} as a column; {@code -} alone is empty. */
  private void put(int column, int from, int to) {
    FieldValues values = values();
    int start = values.mark();
    if (!(to - from == 1 && text.charAt(from) == '-')) {
      values.append(text, from, to);
    }
    values.close(column, start);
  }
}
