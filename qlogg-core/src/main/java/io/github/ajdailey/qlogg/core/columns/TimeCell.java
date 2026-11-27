package io.github.ajdailey.qlogg.core.columns;

import io.github.ajdailey.qlogg.core.time.TimestampParser;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Formats the Time column of column view: the parsed instant plus the tab's time offset, in the
 * display zone, as {@code yyyy-MM-dd HH:mm:ss} and at least three fraction digits, more when the
 * stamp has them. The status bar's time field formats the same way.
 *
 * @param zone the display zone
 * @param offset the tab's time offset in nanoseconds
 */
public record TimeCell(ZoneId zone, long offset) {
  /** Fraction digits shown at least. */
  public static final int MIN_DIGITS = 3;

  private static final long SECOND = 1_000_000_000L;

  /**
   * Appends an instant.
   *
   * @param nanos epoch nanoseconds as parsed, before the offset
   * @param digits the stamp's fraction digits
   * @param out receives the text
   * @return false, with nothing appended, for {@link TimestampParser#NONE} or an instant the offset
   *     moves out of range
   */
  public boolean append(long nanos, int digits, StringBuilder out) {
    if (nanos == TimestampParser.NONE) {
      return false;
    }
    LocalDateTime time;
    try {
      long shown = Math.addExact(nanos, offset);
      time =
          LocalDateTime.ofInstant(
              Instant.ofEpochSecond(Math.floorDiv(shown, SECOND), Math.floorMod(shown, SECOND)),
              zone);
    } catch (ArithmeticException | DateTimeException e) {
      return false;
    }
    digits(out, time.getYear(), 4).append('-');
    digits(out, time.getMonthValue(), 2).append('-');
    digits(out, time.getDayOfMonth(), 2).append(' ');
    digits(out, time.getHour(), 2).append(':');
    digits(out, time.getMinute(), 2).append(':');
    digits(out, time.getSecond(), 2).append('.');
    int fraction = time.getNano();
    int place = 100_000_000;
    for (int i = Math.max(MIN_DIGITS, Math.min(9, digits)); i > 0; i--) {
      out.append((char) ('0' + fraction / place % 10));
      place /= 10;
    }
    return true;
  }

  private static StringBuilder digits(StringBuilder out, int value, int width) {
    int at = out.length();
    out.append(value);
    while (out.length() - at < width) {
      out.insert(at, '0');
    }
    return out;
  }
}
