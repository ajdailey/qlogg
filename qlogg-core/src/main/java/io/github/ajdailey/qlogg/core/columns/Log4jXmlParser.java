package io.github.ajdailey.qlogg.core.columns;

import io.github.ajdailey.qlogg.core.format.XmlReferences;
import io.github.ajdailey.qlogg.core.time.TimestampParser;

/**
 * One {@code <log4j:event>} element, as log4j 1.x, logback and NLog write it: the {@code logger},
 * {@code timestamp} (epoch milliseconds), {@code level} and {@code thread} attributes; the text of
 * {@code log4j:message}, {@code log4j:throwable} and {@code log4j:NDC}, CDATA or entity-escaped;
 * {@code log4j:locationInfo} as {@code class.method(file:line)}; each {@code log4j:data} as {@code
 * name=value}. Other elements, comments and text between elements are skipped. A record parses when
 * it starts with the event's well-formed start tag; a record cut before {@code </log4j:event>}
 * keeps what it holds. Entities unescape through {@link XmlReferences}; nothing else of XML is
 * read.
 */
final class Log4jXmlParser extends FieldParser {
  /** {@code log4j:data} pairs recorded per event; later ones are skipped. */
  static final int MAX_DATA = 1024;

  private static final String EVENT_START = "<log4j:event";
  private static final String EVENT_END = "</log4j:event";
  private static final String[] EVENT_ATTRIBUTES = {"timestamp", "level", "logger", "thread"};
  private static final String[] LOCATION_ATTRIBUTES = {"class", "method", "file", "line"};
  private static final String CDATA = "<![CDATA[";
  private static final int TIME = 0;
  private static final int LEVEL = 1;
  private static final int LOGGER = 2;
  private static final int THREAD = 3;
  private static final int MESSAGE = 4;
  private static final int THROWABLE = 5;
  private static final int NDC = 6;
  private static final int LOCATION = 7;
  private static final int PROPERTIES = 8;

  /** Attribute spans in the record, start and end, by {@link #slot}. */
  private final int[] attributes = new int[2 * 8];

  private final int[] dataName = new int[2 * MAX_DATA];
  private final int[] dataValue = new int[2 * MAX_DATA];
  private CharSequence text;
  private int length;
  private int pos;
  private int data;
  private boolean location;

  Log4jXmlParser() {
    super(ColumnFormat.LOG4J_XML.columns().size());
  }

  @Override
  boolean read(CharSequence record) {
    text = record;
    length = record.length();
    pos = 0;
    data = 0;
    location = false;
    for (int i = 0; i < attributes.length; i++) {
      attributes[i] = -1;
    }
    try {
      skipSpace();
      if (!startsWith(EVENT_START) || !boundary(pos + EVENT_START.length())) {
        return false;
      }
      pos += EVENT_START.length();
      int end = attributes();
      if (end < 0) {
        return false;
      }
      if (end == 0) {
        content();
      }
      fill();
      return true;
    } finally {
      text = null;
    }
  }

  /** Reads the elements inside the event up to its end tag or the record's end. */
  private void content() {
    while (true) {
      while (pos < length && text.charAt(pos) != '<') {
        pos++;
      }
      if (pos >= length || startsWith(EVENT_END)) {
        return;
      }
      if (startsWith("<!--")) {
        pos = after("-->", pos + 4);
      } else if (startsWith(CDATA)) {
        pos = after("]]>", pos + CDATA.length());
      } else if (startsWith("log4j:message", pos + 1)) {
        text(MESSAGE, "log4j:message");
      } else if (startsWith("log4j:throwable", pos + 1)) {
        text(THROWABLE, "log4j:throwable");
      } else if (startsWith("log4j:NDC", pos + 1)) {
        text(NDC, "log4j:NDC");
      } else if (startsWith("log4j:locationInfo", pos + 1)) {
        pos += 1 + "log4j:locationInfo".length();
        location = true;
        skipTag();
      } else if (startsWith("log4j:data", pos + 1) && boundary(pos + 11)) {
        pos += 11;
        int from = pos;
        skipTag();
        data(from);
      } else {
        pos++;
        skipTag();
      }
    }
  }

  /**
   * Reads the start tag's attributes after the element name.
   *
   * @return 0 at {@code >}, 1 at {@code />}, -1 when malformed
   */
  private int attributes() {
    while (true) {
      skipSpace();
      if (pos >= length) {
        return -1;
      }
      char c = text.charAt(pos);
      if (c == '>') {
        pos++;
        return 0;
      }
      if (c == '/' && pos + 1 < length && text.charAt(pos + 1) == '>') {
        pos += 2;
        return 1;
      }
      int nameFrom = pos;
      while (pos < length && text.charAt(pos) != '=' && !space(text.charAt(pos))) {
        pos++;
      }
      int nameTo = pos;
      skipSpace();
      if (nameTo == nameFrom || pos >= length || text.charAt(pos) != '=') {
        return -1;
      }
      pos++;
      skipSpace();
      if (pos >= length || (text.charAt(pos) != '"' && text.charAt(pos) != '\'')) {
        return -1;
      }
      char quote = text.charAt(pos++);
      int valueFrom = pos;
      while (pos < length && text.charAt(pos) != quote) {
        pos++;
      }
      if (pos >= length) {
        return -1;
      }
      int slot = slot(nameFrom, nameTo);
      if (slot >= 0) {
        attributes[2 * slot] = valueFrom;
        attributes[2 * slot + 1] = pos;
      }
      pos++;
    }
  }

  /**
   * Returns the attribute slot of a name: 0-3 for the event's {@code timestamp}, {@code level},
   * {@code logger} and {@code thread}, 4-7 for location's {@code class}, {@code method}, {@code
   * file} and {@code line}, or -1.
   */
  private int slot(int from, int to) {
    String[] names = location ? LOCATION_ATTRIBUTES : EVENT_ATTRIBUTES;
    for (int i = 0; i < names.length; i++) {
      if (equals(from, to, names[i])) {
        return location ? 4 + i : i;
      }
    }
    return -1;
  }

  /** Reports whether {@code text[from, to)} is {@code name}. */
  private boolean equals(int from, int to, String name) {
    return to - from == name.length() && startsWith(name, from);
  }

  /** Reads an element's text into a column: CDATA as written, other text entity-unescaped. */
  private void text(int column, String name) {
    pos += 1 + name.length();
    if (!boundary(pos)) {
      skipTag();
      return;
    }
    if (skipTag()) {
      return;
    }
    FieldValues values = values();
    int start = values.mark();
    while (pos < length) {
      if (startsWith(CDATA)) {
        int from = pos + CDATA.length();
        int end = indexOf("]]>", from);
        values.append(text, from, end < 0 ? length : end);
        pos = end < 0 ? length : end + 3;
      } else if (text.charAt(pos) == '<') {
        break;
      } else {
        int from = pos;
        while (pos < length && text.charAt(pos) != '<') {
          pos++;
        }
        XmlReferences.unescape(text, from, pos, values.builder());
        values.limit();
      }
    }
    values.close(column, start);
  }

  /**
   * Records a {@code log4j:data} element's name and value, between {@code from} and the tag end.
   */
  private void data(int from) {
    if (data >= MAX_DATA) {
      return;
    }
    int saved = pos;
    pos = from;
    int nameFrom = -1;
    int nameTo = -1;
    int valueFrom = -1;
    int valueTo = -1;
    while (pos < saved) {
      skipSpace();
      int attrFrom = pos;
      while (pos < saved && text.charAt(pos) != '=' && !space(text.charAt(pos))) {
        pos++;
      }
      int attrTo = pos;
      skipSpace();
      if (pos >= saved || text.charAt(pos) != '=') {
        break;
      }
      pos++;
      skipSpace();
      if (pos >= saved || (text.charAt(pos) != '"' && text.charAt(pos) != '\'')) {
        break;
      }
      char quote = text.charAt(pos++);
      int valueStart = pos;
      while (pos < saved && text.charAt(pos) != quote) {
        pos++;
      }
      if (equals(attrFrom, attrTo, "name")) {
        nameFrom = valueStart;
        nameTo = pos;
      } else if (equals(attrFrom, attrTo, "value")) {
        valueFrom = valueStart;
        valueTo = pos;
      }
      pos++;
    }
    pos = saved;
    if (nameFrom >= 0) {
      dataName[2 * data] = nameFrom;
      dataName[2 * data + 1] = nameTo;
      dataValue[2 * data] = valueFrom;
      dataValue[2 * data + 1] = valueTo;
      data++;
    }
  }

  /** Writes the attribute columns, Location and Properties. */
  private void fill() {
    FieldValues values = values();
    attribute(TIME, 0);
    attribute(LEVEL, 1);
    attribute(LOGGER, 2);
    attribute(THREAD, 3);
    stamp(millis(attributes[0], attributes[1]), 3);
    if (attributes[8] >= 0 || attributes[10] >= 0) {
      int start = values.mark();
      unescape(attributes[8], attributes[9]);
      values.append('.');
      unescape(attributes[10], attributes[11]);
      values.append('(');
      unescape(attributes[12], attributes[13]);
      values.append(':');
      unescape(attributes[14], attributes[15]);
      values.append(')');
      values.close(LOCATION, start);
    }
    int start = values.mark();
    for (int d = 0; d < data; d++) {
      if (d > 0) {
        values.append(',');
        values.append(' ');
      }
      unescape(dataName[2 * d], dataName[2 * d + 1]);
      values.append('=');
      unescape(dataValue[2 * d], dataValue[2 * d + 1]);
    }
    values.close(PROPERTIES, start);
  }

  private void attribute(int column, int slot) {
    int start = values().mark();
    unescape(attributes[2 * slot], attributes[2 * slot + 1]);
    values().close(column, start);
  }

  /** Appends {@code text[from, to)} unescaped; nothing when {@code from} is -1. */
  private void unescape(int from, int to) {
    if (from >= 0) {
      XmlReferences.unescape(text, from, to, values().builder());
      values().limit();
    }
  }

  /** Reads 1-13 digits of epoch milliseconds. */
  private long millis(int from, int to) {
    if (from < 0 || to == from || to - from > 13) {
      return TimestampParser.NONE;
    }
    long value = 0;
    for (int i = from; i < to; i++) {
      char c = text.charAt(i);
      if (c < '0' || c > '9') {
        return TimestampParser.NONE;
      }
      value = value * 10 + c - '0';
    }
    return value <= Long.MAX_VALUE / 1_000_000L ? value * 1_000_000L : TimestampParser.NONE;
  }

  /**
   * Moves past the current tag's {@code >}, skipping quoted attribute values, and recording
   * attributes when {@link #location} is set.
   *
   * @return true when the tag ended with {@code />} or the record ended
   */
  private boolean skipTag() {
    if (location) {
      int end = attributes();
      location = false;
      if (end >= 0) {
        return end == 1;
      }
    }
    while (pos < length) {
      char c = text.charAt(pos);
      if (c == '"' || c == '\'') {
        pos++;
        while (pos < length && text.charAt(pos) != c) {
          pos++;
        }
        pos = Math.min(length, pos + 1);
      } else if (c == '>') {
        pos++;
        return pos >= 2 && text.charAt(pos - 2) == '/';
      } else {
        pos++;
      }
    }
    return true;
  }

  /**
   * Reports whether {@code at} ends an element name: whitespace, {@code >}, {@code /} or the end.
   */
  private boolean boundary(int at) {
    if (at >= length) {
      return true;
    }
    char c = text.charAt(at);
    return c == '>' || c == '/' || space(c);
  }

  private boolean startsWith(String s) {
    return startsWith(s, pos);
  }

  private boolean startsWith(String s, int at) {
    if (at + s.length() > length) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      if (text.charAt(at + i) != s.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  private int indexOf(String s, int from) {
    for (int i = from; i + s.length() <= length; i++) {
      if (startsWith(s, i)) {
        return i;
      }
    }
    return -1;
  }

  /** Returns the index after {@code s} at or after {@code from}, or the record's length. */
  private int after(String s, int from) {
    int at = indexOf(s, from);
    return at < 0 ? length : at + s.length();
  }

  private void skipSpace() {
    while (pos < length && space(text.charAt(pos))) {
      pos++;
    }
  }

  private static boolean space(char c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n';
  }
}
