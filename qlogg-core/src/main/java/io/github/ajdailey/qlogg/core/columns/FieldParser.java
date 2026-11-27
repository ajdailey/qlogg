package io.github.ajdailey.qlogg.core.columns;

import io.github.ajdailey.qlogg.core.time.TimestampParser;

/**
 * Splits one record into column values. A record is one line, or for a {@link
 * ColumnFormat#multiLine} format the lines of one record with their line feeds. Each built-in
 * format has its own subclass. Reads each char of the record a bounded number of times and grows
 * only its reused buffers, so a parse allocates nothing per char. Not thread-safe: one instance per
 * thread.
 */
public abstract class FieldParser {
  private final FieldValues values;
  private long nanos = TimestampParser.NONE;
  private int fractionDigits;

  FieldParser(int columns) {
    values = new FieldValues(columns);
  }

  /**
   * Parses a record. A record that does not parse shows its raw text across all columns.
   *
   * @param record the record's chars, a CR before each line feed included; read through {@code
   *     charAt}, so a cancelable sequence cancels the parse, except that the CSV and TSV parser
   *     reads an array-backed {@link java.nio.CharBuffer} from its array
   * @return true when it parsed; {@link #values} then has every column
   */
  public final boolean parse(CharSequence record) {
    values.reset();
    nanos = TimestampParser.NONE;
    fractionDigits = 0;
    boolean parsed = read(record);
    if (!parsed) {
      values.fail();
      nanos = TimestampParser.NONE;
    }
    return parsed;
  }

  /**
   * Returns the last record's values.
   *
   * @return the values, empty after a record that did not parse
   */
  public final FieldValues values() {
    return values;
  }

  /**
   * Returns the instant in the last record's Time column.
   *
   * @return epoch nanoseconds, or {@link TimestampParser#NONE} without a parsed stamp
   */
  public final long nanos() {
    return nanos;
  }

  /**
   * Returns the second-fraction digits of the last record's stamp.
   *
   * @return 0-9
   */
  public final int fractionDigits() {
    return fractionDigits;
  }

  /**
   * Fills {@link #values} after {@link FieldValues#reset}; false when the record does not parse.
   */
  abstract boolean read(CharSequence record);

  /** Records the Time column's instant. */
  final void stamp(long stampNanos, int digits) {
    nanos = stampNanos;
    fractionDigits = stampNanos == TimestampParser.NONE ? 0 : digits;
  }
}
