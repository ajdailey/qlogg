package io.github.ajdailey.qlogg.core.columns;

import io.github.ajdailey.qlogg.core.text.TextEncoding;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.time.TimestampFormat;
import io.github.ajdailey.qlogg.core.time.TimestampParser;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Picks a file's column format from its head, the encoding head {@code LogDocument.open} reads.
 * Rules, first match wins, over at most {@link #SAMPLE_LINES} non-empty lines or CSV records:
 *
 * <table>
 *   <caption>Detection rules</caption>
 *   <tr><th>Format</th><th>File name</th><th>Content</th></tr>
 *   <tr><td>{@code log4j-xml}</td><td>any</td><td>a line starting {@code <log4j:event}</td></tr>
 *   <tr><td>{@code clef}</td><td>any</td><td>{@link #MIN_LINES} lines, {@link #MIN_PERCENT}% of
 *       them objects with a top-level {@code @t}</td></tr>
 *   <tr><td>{@code clf}</td><td>any</td><td>{@link #MIN_LINES} lines, {@link #MIN_PERCENT}% of
 *       them parsing</td></tr>
 *   <tr><td>{@code csv}</td><td>{@code .csv}</td><td>at least 2 fields, the same count in {@link
 *       #DELIMITED_PERCENT}% of the records</td></tr>
 *   <tr><td>{@code tsv}</td><td>{@code .tsv}, {@code .tab}</td><td>as {@code csv}</td></tr>
 * </table>
 *
 * <p>{@code time} is never detected.
 */
public final class ColumnDetector {
  /** Non-empty lines or records sampled. */
  public static final int SAMPLE_LINES = 200;

  /** Fewest parsed lines that choose {@code clef} or {@code clf}. */
  public static final int MIN_LINES = 3;

  /** Smallest parsed share of the sample that chooses {@code clef} or {@code clf}. */
  public static final int MIN_PERCENT = 80;

  /** Smallest share of CSV or TSV records with the most common field count. */
  public static final int DELIMITED_PERCENT = 90;

  private static final String EVENT_START = "<log4j:event";
  private static final char[] CSV_DELIMITERS = {',', ';', '|'};

  private ColumnDetector() {}

  /**
   * Detects the column format of a file head.
   *
   * @param head the file's first bytes from offset 0, BOM included, between position and limit; not
   *     modified
   * @param encoding the detected encoding
   * @param fileName the file's name, for the CSV and TSV extensions
   * @param context zone and file date for the stamp parsers
   * @return the schema, or null when no rule matches
   */
  public static ColumnSchema detect(
      ByteBuffer head, TextEncoding encoding, String fileName, TimeContext context) {
    HeadText text = new HeadText(head, encoding, SAMPLE_LINES);
    if (log4j(text)) {
      return ColumnSchema.fixed(ColumnFormat.LOG4J_XML);
    }
    if (mostParse(text, new ClefParser(context), true)) {
      return ColumnSchema.fixed(ColumnFormat.CLEF);
    }
    if (mostParse(text, new ClfParser(context), false)) {
      return ColumnSchema.fixed(ColumnFormat.CLF);
    }
    String name = fileName.toLowerCase(Locale.ROOT);
    ColumnFormat delimited = null;
    if (name.endsWith(".csv")) {
      delimited = ColumnFormat.CSV;
    } else if (name.endsWith(".tsv") || name.endsWith(".tab")) {
      delimited = ColumnFormat.TSV;
    }
    if (delimited == null) {
      return null;
    }
    Delimiter best = delimiter(text, delimited);
    if (best.modal < 2 || best.consistent * 100L < (long) DELIMITED_PERCENT * best.records) {
      return null;
    }
    return delimitedSchema(text, delimited, best, null, context);
  }

  /**
   * Builds the schema of a format the user chose.
   *
   * @param format the format
   * @param head the file head, as for {@link #detect}
   * @param encoding the detected encoding
   * @param header for CSV and TSV, true or false to override the header guess, or null to guess
   * @param timeFormat the document's timestamp format, for {@link ColumnFormat#TIME}
   * @param context zone and file date for the header guess
   * @return the schema, or null for {@link ColumnFormat#TIME} without a timestamp format
   */
  public static ColumnSchema schema(
      ColumnFormat format,
      ByteBuffer head,
      TextEncoding encoding,
      Boolean header,
      TimestampFormat timeFormat,
      TimeContext context) {
    return switch (format) {
      case CLF, CLEF, LOG4J_XML -> ColumnSchema.fixed(format);
      case TIME -> timeFormat == null ? null : ColumnSchema.timestamp(timeFormat);
      case CSV, TSV -> {
        HeadText text = new HeadText(head, encoding, SAMPLE_LINES);
        yield delimitedSchema(text, format, delimiter(text, format), header, context);
      }
    };
  }

  private static boolean log4j(HeadText text) {
    CharSequence chars = text.chars();
    for (int line = 0; line < text.lines(); line++) {
      int i = text.start(line);
      int end = text.end(line);
      while (i < end && (chars.charAt(i) == ' ' || chars.charAt(i) == '\t')) {
        i++;
      }
      if (end - i >= EVENT_START.length() && regionEquals(chars, i, EVENT_START)) {
        return true;
      }
    }
    return false;
  }

  private static boolean mostParse(HeadText text, FieldParser parser, boolean needTime) {
    Span span = new Span();
    int parsed = 0;
    for (int line = 0; line < text.lines(); line++) {
      if (parser.parse(span.set(text.chars(), text.start(line), text.end(line)))) {
        FieldValues values = parser.values();
        if (!needTime || values.end(0) > values.start(0)) {
          parsed++;
        }
      }
    }
    return parsed >= MIN_LINES && parsed * 100L >= (long) MIN_PERCENT * text.lines();
  }

  /** Picks the delimiter whose most common field count covers the most records. */
  private static Delimiter delimiter(HeadText text, ColumnFormat format) {
    if (format == ColumnFormat.TSV) {
      return count(text, '\t');
    }
    Delimiter best = null;
    for (char c : CSV_DELIMITERS) {
      Delimiter candidate = count(text, c);
      boolean better =
          best == null
              || (candidate.modal >= 2
                  && (best.modal < 2 || candidate.consistent > best.consistent));
      if (better) {
        best = candidate;
      }
    }
    return best;
  }

  /** Counts each sampled record's fields with a delimiter, quotes opening only at a field start. */
  private static Delimiter count(HeadText text, char delimiter) {
    CharSequence chars = text.chars();
    int length = chars.length();
    int[] counts = new int[SAMPLE_LINES];
    int records = 0;
    int firstStart = -1;
    int firstEnd = -1;
    int i = 0;
    while (i < length && records < SAMPLE_LINES) {
      int start = i;
      int fields = 1;
      boolean quoted = false;
      boolean fieldStart = true;
      while (i < length) {
        char c = chars.charAt(i);
        if (quoted) {
          if (c == '"') {
            if (i + 1 < length && chars.charAt(i + 1) == '"') {
              i++;
            } else {
              quoted = false;
            }
          }
        } else if (c == '\n') {
          break;
        } else if (c == '"' && fieldStart) {
          quoted = true;
        } else if (c == delimiter) {
          fields++;
          fieldStart = true;
          i++;
          continue;
        }
        fieldStart = false;
        i++;
      }
      if (i >= length && text.cut()) {
        break;
      }
      int end = i > start && chars.charAt(i - 1) == '\r' ? i - 1 : i;
      i++;
      if (end > start) {
        if (firstStart < 0) {
          firstStart = start;
          firstEnd = end;
        }
        counts[records++] = fields;
      }
    }
    return new Delimiter(delimiter, counts, records, firstStart, firstEnd);
  }

  private static ColumnSchema delimitedSchema(
      HeadText text,
      ColumnFormat format,
      Delimiter delimiter,
      Boolean header,
      TimeContext context) {
    List<String> names = new ArrayList<String>();
    if (delimiter.firstStart >= 0) {
      DelimitedParser parser = new DelimitedParser(delimiter.delimiter, ColumnSchema.MAX_FIELDS);
      Span span = new Span();
      parser.parse(span.set(text.chars(), delimiter.firstStart, delimiter.firstEnd));
      int fields = Math.min(delimiter.fields(), ColumnSchema.MAX_FIELDS);
      for (int i = 0; i < fields; i++) {
        names.add(parser.values().value(i).toString());
      }
    }
    if (names.isEmpty()) {
      names.add("");
    }
    boolean isHeader = header != null ? header : looksLikeHeader(names, context);
    return ColumnSchema.delimited(format, delimiter.delimiter, names, isHeader);
  }

  /** Reports whether no field is empty, repeated (ignoring case), a number or a timestamp. */
  private static boolean looksLikeHeader(List<String> names, TimeContext context) {
    List<TimestampParser> parsers = new ArrayList<TimestampParser>();
    for (BuiltInFormat format : BuiltInFormat.values()) {
      parsers.add(format.parser(context));
    }
    Set<String> seen = new HashSet<String>();
    for (String name : names) {
      String trimmed = name.strip();
      if (trimmed.isEmpty() || !seen.add(trimmed.toLowerCase(Locale.ROOT)) || number(trimmed)) {
        return false;
      }
      for (TimestampParser parser : parsers) {
        if (parser.parse(trimmed) != TimestampParser.NONE) {
          return false;
        }
      }
    }
    return true;
  }

  /** An optional sign, digits and at most one {@code .} or {@code ,}. */
  private static boolean number(String text) {
    int i = text.charAt(0) == '-' || text.charAt(0) == '+' ? 1 : 0;
    int digits = 0;
    boolean point = false;
    for (; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c >= '0' && c <= '9') {
        digits++;
      } else if ((c == '.' || c == ',') && !point) {
        point = true;
      } else {
        return false;
      }
    }
    return digits > 0;
  }

  private static boolean regionEquals(CharSequence chars, int at, String s) {
    for (int i = 0; i < s.length(); i++) {
      if (chars.charAt(at + i) != s.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  /** Field counts of the sampled records under one delimiter. */
  private static final class Delimiter {
    final char delimiter;
    final int records;
    final int modal;
    final int consistent;
    final int firstStart;
    final int firstEnd;

    Delimiter(char delimiter, int[] counts, int records, int firstStart, int firstEnd) {
      this.delimiter = delimiter;
      this.records = records;
      this.firstStart = firstStart;
      this.firstEnd = firstEnd;
      int bestCount = 0;
      int bestValue = 0;
      for (int r = 0; r < records; r++) {
        int same = 0;
        for (int k = 0; k < records; k++) {
          if (counts[k] == counts[r]) {
            same++;
          }
        }
        if (same > bestCount || (same == bestCount && counts[r] > bestValue)) {
          bestCount = same;
          bestValue = counts[r];
        }
      }
      this.modal = bestValue;
      this.consistent = bestCount;
    }

    /** Returns the column count: the most common field count, at least 1. */
    int fields() {
      return Math.max(1, modal);
    }
  }
}
