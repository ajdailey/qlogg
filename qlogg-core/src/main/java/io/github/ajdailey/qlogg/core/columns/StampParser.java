package io.github.ajdailey.qlogg.core.columns;

import io.github.ajdailey.qlogg.core.time.TimestampParser;

/**
 * The {@link ColumnFormat#TIME} columns: Time is the line up to {@link TimestampParser#end}, Text
 * the rest after a {@code ]} closing a leading {@code [} and the spaces and tabs that follow. A
 * line without a stamp does not parse.
 */
final class StampParser extends FieldParser {
  private static final int TIME = 0;
  private static final int TEXT = 1;

  private final TimestampParser parser;

  StampParser(TimestampParser parser) {
    super(ColumnFormat.TIME.columns().size());
    this.parser = parser;
  }

  @Override
  boolean read(CharSequence record) {
    long nanos = parser.parse(record);
    if (nanos == TimestampParser.NONE) {
      return false;
    }
    stamp(nanos, parser.fractionDigits());
    int length = record.length();
    int end = Math.min(parser.end(), length);
    FieldValues values = values();
    values.append(record, 0, end);
    values.close(TIME, 0);
    int text = end;
    if (length > 0 && record.charAt(0) == '[' && text < length && record.charAt(text) == ']') {
      text++;
    }
    while (text < length && (record.charAt(text) == ' ' || record.charAt(text) == '\t')) {
      text++;
    }
    int start = values.mark();
    values.append(record, text, length);
    values.close(TEXT, start);
    return true;
  }
}
