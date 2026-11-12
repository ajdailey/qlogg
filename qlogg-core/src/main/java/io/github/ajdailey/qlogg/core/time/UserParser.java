package io.github.ajdailey.qlogg.core.time;

import io.github.ajdailey.qlogg.core.text.BoundedChars;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for a {@link UserFormat}. The regex runs with {@code find} over {@link BoundedChars}; a
 * run that reaches the bound or overflows the stack counts as no timestamp. The group's text then
 * resolves as an instant when it carries an offset or zone, as a local date and time in the
 * context's zone, or as a month and day with {@link TimeContext#yearOf}. A two-digit year takes
 * {@link TimeContext#nearestYear}. A time without a date is no timestamp.
 */
final class UserParser implements TimestampParser {
  private final Matcher matcher;
  private final DateTimeFormatter formatter;
  private final int digits;
  private final TimeContext context;
  private final LocalClock clock;
  private final BoundedChars bounded = new BoundedChars();
  private final boolean twoDigitYear;

  UserParser(
      Pattern locate,
      DateTimeFormatter formatter,
      int digits,
      boolean twoDigitYear,
      TimeContext context) {
    this.twoDigitYear = twoDigitYear;
    this.matcher = locate.matcher("");
    this.formatter = formatter;
    this.digits = digits;
    this.context = context;
    this.clock = new LocalClock(context.zone());
  }

  @Override
  public long parse(CharSequence line) {
    CharSequence prefix =
        line.length() > TimeReader.PREFIX_CHARS
            ? line.subSequence(0, TimeReader.PREFIX_CHARS)
            : line;
    String text;
    try {
      if (!bounded.find(matcher, prefix)) {
        return NONE;
      }
      text = matcher.group("ts");
    } finally {
      matcher.reset("");
    }
    if (text == null) {
      return NONE;
    }
    try {
      return resolve(formatter.parse(text));
    } catch (DateTimeException e) {
      return NONE;
    }
  }

  @Override
  public int fractionDigits() {
    return digits;
  }

  private long resolve(TemporalAccessor parsed) {
    if (twoDigitYear) {
      return resolveTwoDigitYear(parsed);
    }
    if (parsed.isSupported(ChronoField.INSTANT_SECONDS)) {
      return LocalClock.nanos(
          parsed.getLong(ChronoField.INSTANT_SECONDS), parsed.getLong(ChronoField.NANO_OF_SECOND));
    }
    LocalTime time = parsed.query(TemporalQueries.localTime());
    if (time == null) {
      return NONE;
    }
    LocalDate date = parsed.query(TemporalQueries.localDate());
    int year;
    int month;
    int day;
    if (date != null) {
      year = date.getYear();
      month = date.getMonthValue();
      day = date.getDayOfMonth();
    } else if (parsed.isSupported(ChronoField.MONTH_OF_YEAR)
        && parsed.isSupported(ChronoField.DAY_OF_MONTH)) {
      month = parsed.get(ChronoField.MONTH_OF_YEAR);
      day = parsed.get(ChronoField.DAY_OF_MONTH);
      year = context.yearOf(month);
    } else {
      return NONE;
    }
    return clock.local(
        year, month, day, time.getHour(), time.getMinute(), time.getSecond(), time.getNano());
  }

  /** Replaces the formatter's 2000-2099 year with {@link TimeContext#nearestYear}. */
  private long resolveTwoDigitYear(TemporalAccessor parsed) {
    LocalDate date = parsed.query(TemporalQueries.localDate());
    LocalTime time = parsed.query(TemporalQueries.localTime());
    if (date == null || time == null) {
      return NONE;
    }
    int month = date.getMonthValue();
    int day = date.getDayOfMonth();
    int year = context.nearestYear(date.getYear() % 100, month);
    if (!LocalClock.validDate(year, month, day)) {
      return NONE;
    }
    ZoneId zone = parsed.query(TemporalQueries.zone());
    if (zone == null) {
      return clock.local(
          year, month, day, time.getHour(), time.getMinute(), time.getSecond(), time.getNano());
    }
    Instant instant =
        LocalDateTime.of(LocalDate.of(year, month, day), time).atZone(zone).toInstant();
    return LocalClock.nanos(instant.getEpochSecond(), instant.getNano());
  }
}
