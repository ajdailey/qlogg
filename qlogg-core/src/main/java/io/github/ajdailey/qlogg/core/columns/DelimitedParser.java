package io.github.ajdailey.qlogg.core.columns;

/**
 * CSV and TSV fields with RFC 4180 quoting. A quote opens a quoted field only at a field start;
 * elsewhere it is literal. Inside quotes {@code ""} is one quote and a delimiter, CR or LF is part
 * of the value; chars after the closing quote up to the delimiter are kept. Fields past the last
 * column join it with the delimiter; missing fields are empty. Every record parses.
 */
final class DelimitedParser extends FieldParser {
  private final char delimiter;
  private final int columns;

  DelimitedParser(char delimiter, int columns) {
    super(columns);
    this.delimiter = delimiter;
    this.columns = columns;
  }

  @Override
  boolean read(CharSequence record) {
    FieldValues values = values();
    int length = record.length();
    int i = length > 0 && record.charAt(0) == '\ufeff' ? 1 : 0;
    int column = 0;
    int start = values.mark();
    while (true) {
      if (i < length && record.charAt(i) == '"') {
        i = quoted(record, i + 1, length, values);
      }
      while (i < length && record.charAt(i) != delimiter) {
        values.append(record.charAt(i));
        i++;
      }
      if (i >= length) {
        values.close(column, start);
        return true;
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
  private static int quoted(CharSequence record, int i, int length, FieldValues values) {
    while (i < length) {
      char c = record.charAt(i);
      if (c == '"') {
        if (i + 1 < length && record.charAt(i + 1) == '"') {
          values.append('"');
          i += 2;
          continue;
        }
        return i + 1;
      }
      values.append(c);
      i++;
    }
    return i;
  }
}
