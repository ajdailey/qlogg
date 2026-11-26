package io.github.ajdailey.qlogg.core.time;

/** Reads the timestamp at the start of a line. Not thread-safe: one instance per thread. */
public interface TimestampParser {
  /** The result for a line without a timestamp. */
  long NONE = Long.MIN_VALUE;

  /**
   * Parses a line's leading chars.
   *
   * @param line the line's first chars, at most {@link TimeReader#PREFIX_CHARS}
   * @return epoch nanoseconds, or {@link #NONE}
   */
  long parse(CharSequence line);

  /**
   * Returns the fraction digits the last successful {@link #parse} read.
   *
   * @return 0-9
   */
  int fractionDigits();

  /**
   * Returns the index after the last char of the stamp the last successful {@link #parse} read:
   * past a closing {@code ]} for {@link BuiltInFormat#CLF}, past the offset when the stamp has one.
   *
   * @return a char index in the parsed line
   */
  int end();
}
