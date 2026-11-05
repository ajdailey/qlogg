package io.github.ajdailey.qlogg.core.time;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * What a parser needs besides the line. The year and month are the file's modification time, or the
 * current date for a document without one; they settle year-less and two-digit years.
 *
 * @param zone interprets local times
 * @param fileYear year of the reference date
 * @param fileMonth month of the reference date, 1-12
 */
public record TimeContext(ZoneId zone, int fileYear, int fileMonth) {
  /** Checks the month. */
  public TimeContext {
    if (fileMonth < 1 || fileMonth > 12) {
      throw new IllegalArgumentException("fileMonth must be 1-12");
    }
  }

  /**
   * Takes the year and month of a modification time in the zone.
   *
   * @param zone the document zone
   * @param modifiedMillis the file's modification time, epoch milliseconds
   * @return the context
   */
  public static TimeContext of(ZoneId zone, long modifiedMillis) {
    ZonedDateTime modified = Instant.ofEpochMilli(modifiedMillis).atZone(zone);
    return new TimeContext(zone, modified.getYear(), modified.getMonthValue());
  }

  /**
   * Takes the current date as the reference, for a document without a modification time.
   *
   * @param zone the document zone
   * @return the context
   */
  public static TimeContext current(ZoneId zone) {
    return of(zone, System.currentTimeMillis());
  }

  /**
   * Returns the year of a year-less date: the file's year, or the year before when the month is
   * after the file's month.
   *
   * @param month 1-12
   * @return the year
   */
  public int yearOf(int month) {
    return month > fileMonth ? fileYear - 1 : fileYear;
  }

  /**
   * Returns the year of a two-digit year: the century that puts the month nearest the reference
   * month, the earlier one on a tie.
   *
   * @param twoDigits 0-99
   * @param month 1-12
   * @return the year
   */
  public int nearestYear(int twoDigits, int month) {
    int reference = fileYear * 12 + fileMonth;
    int best = 0;
    int bestDistance = Integer.MAX_VALUE;
    int century = Math.floorDiv(fileYear, 100) * 100;
    for (int candidate = century - 100; candidate <= century + 100; candidate += 100) {
      int year = candidate + twoDigits;
      int distance = Math.abs(year * 12 + month - reference);
      if (distance < bestDistance) {
        best = year;
        bestDistance = distance;
      }
    }
    return best;
  }
}
