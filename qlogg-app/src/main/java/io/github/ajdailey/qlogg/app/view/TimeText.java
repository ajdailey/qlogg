package io.github.ajdailey.qlogg.app.view;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.time.TimestampFormat;
import io.github.ajdailey.qlogg.core.time.TimestampParser;
import io.github.ajdailey.qlogg.core.time.UserFormat;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats and parses the times the UI shows: the top row's time field, the selection difference,
 * the time offset and the Go to Time target. Times are epoch nanoseconds; text is ASCII.
 */
public final class TimeText {
  /** What a parse returns for text it refuses; no valid time or offset has this value. */
  public static final long INVALID = TimestampParser.NONE;

  /** Fraction digits shown at least. */
  public static final int MIN_DIGITS = 3;

  private static final long SECOND = 1_000_000_000L;
  private static final long DAY = 86_400L * SECOND;

  // Most whole days an offset in nanoseconds holds.
  private static final long MAX_DAYS = Long.MAX_VALUE / DAY;

  private static final Pattern OFFSET =
      Pattern.compile("([+-])?(?:(\\d{1,6})\\.)?(\\d{1,2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,9}))?");

  private static final Pattern TARGET =
      Pattern.compile(
          "(?:(\\d{4})-(\\d{2})-(\\d{2})[ T])?(\\d{1,2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,9}))?");

  private TimeText() {}

  /**
   * Formats an instant as {@code yyyy-MM-dd HH:mm:ss} and a fraction.
   *
   * @param nanos epoch nanoseconds
   * @param digits fraction digits, raised to {@link #MIN_DIGITS}, at most 9
   * @param zone the display zone
   * @return for example {@code 2026-03-01 05:00:00.002}
   */
  public static String instant(long nanos, int digits, ZoneId zone) {
    LocalDateTime time =
        LocalDateTime.ofInstant(
            Instant.ofEpochSecond(Math.floorDiv(nanos, SECOND), Math.floorMod(nanos, SECOND)),
            zone);
    StringBuilder text = new StringBuilder(32);
    text.append(
        String.format(
            Locale.ROOT,
            "%04d-%02d-%02d %02d:%02d:%02d",
            time.getYear(),
            time.getMonthValue(),
            time.getDayOfMonth(),
            time.getHour(),
            time.getMinute(),
            time.getSecond()));
    appendFraction(text, time.getNano(), Math.max(MIN_DIGITS, Math.min(9, digits)));
    return text.toString();
  }

  /**
   * Formats the caret time of a status with the offset applied.
   *
   * @param status a view's times
   * @param offset the document's offset in nanoseconds
   * @param zone the display zone
   * @return the time, or empty when the caret line has none or the sum leaves the {@code long}
   *     range
   */
  public static String caret(TimeStatus status, long offset, ZoneId zone) {
    if (status.caret() == TimestampParser.NONE) {
      return "";
    }
    try {
      return instant(Math.addExact(status.caret(), offset), status.caretDigits(), zone);
    } catch (ArithmeticException | DateTimeException e) {
      return "";
    }
  }

  /**
   * Formats the time from the first to the last selected line of a status. The offset cancels out.
   *
   * @param status a view's times
   * @return for example {@code +00:01:23.456} or {@code +2d 03:00:00.000}; negative when the last
   *     line is earlier; empty when either line has no time
   */
  public static String difference(TimeStatus status) {
    if (status.first() == TimestampParser.NONE || status.last() == TimestampParser.NONE) {
      return "";
    }
    long nanos;
    try {
      nanos = Math.subtractExact(status.last(), status.first());
    } catch (ArithmeticException e) {
      return "";
    }
    return duration(nanos, Math.max(MIN_DIGITS, Math.min(9, status.rangeDigits())), true);
  }

  /**
   * Formats a time offset as the Time Offset dialog reads it: {@code [+-][d.]HH:mm:ss[.fff]}.
   *
   * @param nanos the offset
   * @return for example {@code +00:00:00} or {@code -1.02:00:00.500}
   */
  public static String offset(long nanos) {
    long fraction = Math.abs(nanos % SECOND);
    int digits = fraction == 0 ? 0 : fraction % 1_000_000 == 0 ? 3 : fraction % 1_000 == 0 ? 6 : 9;
    return duration(nanos, digits, false);
  }

  /**
   * Parses a time offset: an optional sign, optional days and a dot, {@code HH:mm:ss}, an optional
   * fraction of 1 to 9 digits after {@code .} or {@code ,}. Blanks around it are ignored.
   *
   * @param text the text
   * @return nanoseconds, or {@link #INVALID}
   */
  public static long parseOffset(String text) {
    Matcher m = OFFSET.matcher(text.strip());
    if (!m.matches()) {
      return INVALID;
    }
    long days = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
    int hours = Integer.parseInt(m.group(3));
    int minutes = Integer.parseInt(m.group(4));
    int seconds = Integer.parseInt(m.group(5));
    if (days > MAX_DAYS || hours > 23 || minutes > 59 || seconds > 59) {
      return INVALID;
    }
    try {
      long nanos =
          Math.addExact(
              Math.multiplyExact(days, DAY),
              ((hours * 60L + minutes) * 60L + seconds) * SECOND + fraction(m.group(6)));
      return "-".equals(m.group(1)) ? -nanos : nanos;
    } catch (ArithmeticException e) {
      return INVALID;
    }
  }

  /**
   * Parses a Go to Time target: {@code yyyy-MM-dd HH:mm:ss}, or {@code HH:mm:ss} on a given date,
   * each with an optional fraction of 1 to 9 digits after {@code .} or {@code ,}. A {@code T} may
   * stand for the space.
   *
   * @param text the text, blanks around it ignored
   * @param zone the zone the text is in
   * @param date the date of a time without one, or null to refuse such a time
   * @return epoch nanoseconds, or {@link #INVALID}
   */
  public static long parseTarget(String text, ZoneId zone, LocalDate date) {
    Matcher m = TARGET.matcher(text.strip());
    if (!m.matches() || (m.group(1) == null && date == null)) {
      return INVALID;
    }
    try {
      LocalDate day =
          m.group(1) == null
              ? date
              : LocalDate.of(
                  Integer.parseInt(m.group(1)),
                  Integer.parseInt(m.group(2)),
                  Integer.parseInt(m.group(3)));
      LocalDateTime time =
          day.atTime(
              Integer.parseInt(m.group(4)),
              Integer.parseInt(m.group(5)),
              Integer.parseInt(m.group(6)),
              (int) fraction(m.group(7)));
      Instant instant = ZonedDateTime.of(time, zone).toInstant();
      return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), SECOND), instant.getNano());
    } catch (DateTimeException | ArithmeticException e) {
      return INVALID;
    }
  }

  /**
   * Returns the date of an instant.
   *
   * @param nanos epoch nanoseconds
   * @param zone the display zone
   * @return the date
   */
  public static LocalDate date(long nanos, ZoneId zone) {
    return LocalDate.ofInstant(
        Instant.ofEpochSecond(Math.floorDiv(nanos, SECOND), Math.floorMod(nanos, SECOND)), zone);
  }

  /**
   * Returns a format's name as menus and tooltips show it.
   *
   * @param format a format
   * @return a built-in's label from {@link UiText}, or the user format's name
   */
  public static String formatName(TimestampFormat format) {
    return switch (format) {
      case UserFormat user -> user.name();
      case BuiltInFormat builtIn -> builtInName(builtIn).text();
    };
  }

  private static UiText builtInName(BuiltInFormat format) {
    return switch (format) {
      case ISO -> UiText.TIME_FORMAT_ISO;
      case YMD_SLASH -> UiText.TIME_FORMAT_YMD_SLASH;
      case YMD_DOT -> UiText.TIME_FORMAT_YMD_DOT;
      case DMY_DOT -> UiText.TIME_FORMAT_DMY_DOT;
      case DMY_SLASH -> UiText.TIME_FORMAT_DMY_SLASH;
      case MDY_SLASH -> UiText.TIME_FORMAT_MDY_SLASH;
      case DMONY -> UiText.TIME_FORMAT_DMONY;
      case SYSLOG -> UiText.TIME_FORMAT_SYSLOG;
      case CLF -> UiText.TIME_FORMAT_CLF;
      case JUL -> UiText.TIME_FORMAT_JUL;
      case EPOCH -> UiText.TIME_FORMAT_EPOCH;
    };
  }

  /**
   * Returns the time field's tooltip.
   *
   * @param format the document's format
   * @param zone the document zone
   * @param offset the document's offset
   * @return the format, zone and offset lines
   */
  public static String tooltip(TimestampFormat format, ZoneId zone, long offset) {
    return UiText.TIME_FIELD_TIP.format(formatName(format), zone.getId(), offset(offset));
  }

  /** Formats a signed duration; {@code days} with a {@code d} and a space when compact. */
  private static String duration(long nanos, int digits, boolean compact) {
    StringBuilder text = new StringBuilder(32);
    text.append(nanos < 0 ? '-' : '+');
    long rest = nanos == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(nanos);
    long days = rest / DAY;
    rest %= DAY;
    if (days > 0) {
      text.append(days).append(compact ? "d " : ".");
    }
    long seconds = rest / SECOND;
    text.append(
        String.format(
            Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60));
    appendFraction(text, (int) (rest % SECOND), digits);
    return text.toString();
  }

  private static void appendFraction(StringBuilder text, int nanos, int digits) {
    if (digits == 0) {
      return;
    }
    String all = String.format(Locale.ROOT, "%09d", nanos);
    text.append('.').append(all, 0, digits);
  }

  private static long fraction(String digits) {
    if (digits == null) {
      return 0;
    }
    long value = Long.parseLong(digits);
    for (int i = digits.length(); i < 9; i++) {
      value *= 10;
    }
    return value;
  }
}
