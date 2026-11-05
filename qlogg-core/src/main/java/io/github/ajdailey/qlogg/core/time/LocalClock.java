package io.github.ajdailey.qlogg.core.time;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;

/**
 * Converts date and time fields to epoch nanoseconds without allocating per call. A local time
 * takes {@link ZoneRules#getOffset(LocalDateTime)}: the offset before the transition in a gap and
 * in an overlap. The offset is cached per local hour that contains no transition. Not thread-safe.
 */
final class LocalClock {
  private static final long DAYS_0000_TO_1970 = 719_528L;
  private static final long NANOS = 1_000_000_000L;

  private final ZoneRules rules;
  private long cachedHour = Long.MIN_VALUE;
  private int cachedOffset;

  LocalClock(ZoneId zone) {
    this.rules = zone.getRules();
  }

  /**
   * Returns the instant of a local time in the zone.
   *
   * @return epoch nanoseconds, or {@link TimestampParser#NONE} for an invalid field or an instant
   *     outside the {@code long} range
   */
  long local(int year, int month, int day, int hour, int minute, int second, int nano) {
    long seconds = localSeconds(year, month, day, hour, minute, second);
    if (seconds == Long.MIN_VALUE) {
      return TimestampParser.NONE;
    }
    return nanos(seconds - offsetAt(seconds), nano);
  }

  /**
   * Returns the instant of a time at a fixed offset from UTC.
   *
   * @param offsetSeconds east of UTC
   * @return epoch nanoseconds, or {@link TimestampParser#NONE}
   */
  static long offset(
      int year, int month, int day, int hour, int minute, int second, int nano, int offsetSeconds) {
    long seconds = localSeconds(year, month, day, hour, minute, second);
    if (seconds == Long.MIN_VALUE) {
      return TimestampParser.NONE;
    }
    return nanos(seconds - offsetSeconds, nano);
  }

  /**
   * Returns epoch seconds plus nanoseconds as epoch nanoseconds.
   *
   * @return nanoseconds, or {@link TimestampParser#NONE} outside the {@code long} range
   */
  static long nanos(long epochSeconds, long nano) {
    long low = epochSeconds * NANOS;
    if (Math.multiplyHigh(epochSeconds, NANOS) != (low >> 63)
        || low > Long.MAX_VALUE - nano
        || low + nano == TimestampParser.NONE) {
      return TimestampParser.NONE;
    }
    return low + nano;
  }

  /** Reports whether a day exists in the proleptic Gregorian calendar. */
  static boolean validDate(int year, int month, int day) {
    return month >= 1 && month <= 12 && day >= 1 && day <= monthLength(year, month);
  }

  private static long localSeconds(int year, int month, int day, int hour, int minute, int second) {
    if (!validDate(year, month, day)
        || hour < 0
        || hour > 23
        || minute < 0
        || minute > 59
        || second < 0
        || second > 59) {
      return Long.MIN_VALUE;
    }
    return epochDay(year, month, day) * 86_400L + hour * 3600L + minute * 60L + second;
  }

  private int offsetAt(long localSeconds) {
    long hour = Math.floorDiv(localSeconds, 3600L);
    if (hour == cachedHour) {
      return cachedOffset;
    }
    int start = offsetOf(hour * 3600L);
    int last = offsetOf(hour * 3600L + 3599L);
    if (start == last) {
      cachedHour = hour;
      cachedOffset = start;
      return start;
    }
    return offsetOf(localSeconds);
  }

  private int offsetOf(long localSeconds) {
    LocalDateTime local = LocalDateTime.ofEpochSecond(localSeconds, 0, ZoneOffset.UTC);
    return rules.getOffset(local).getTotalSeconds();
  }

  private static int monthLength(int year, int month) {
    return switch (month) {
      case 2 -> leap(year) ? 29 : 28;
      case 4, 6, 9, 11 -> 30;
      default -> 31;
    };
  }

  private static boolean leap(long year) {
    return (year & 3) == 0 && (year % 100 != 0 || year % 400 == 0);
  }

  /** The day count from 1970-01-01, as {@code LocalDate.toEpochDay}, for years 0 and later. */
  private static long epochDay(long year, long month, long day) {
    long total = 365 * year;
    total += (year + 3) / 4 - (year + 99) / 100 + (year + 399) / 400;
    total += (367 * month - 362) / 12;
    total += day - 1;
    if (month > 2) {
      total--;
      if (!leap(year)) {
        total--;
      }
    }
    return total - DAYS_0000_TO_1970;
  }
}
