package io.github.ajdailey.qlogg.app.view;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.time.TimestampParser;
import io.github.ajdailey.qlogg.core.time.UserFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TimeTextTest {
  private static final long SECOND = 1_000_000_000L;
  private static final long DAY = 86_400L * SECOND;

  /** 2026-03-01 05:00:00 UTC. */
  private static final long BASE = 1_772_341_200L * SECOND;

  private static TimeStatus range(long first, long last, int digits) {
    return new TimeStatus(TimestampParser.NONE, 0, first, last, digits);
  }

  @Test
  void anInstantShowsAtLeastThreeFractionDigitsAndMoreWhenTheLineHasThem() {
    assertEquals("2026-03-01 05:00:00.000", TimeText.instant(BASE, 0, ZoneOffset.UTC));
    assertEquals(
        "2026-03-01 06:00:00.002", TimeText.instant(BASE + 2_000_000, 3, ZoneOffset.ofHours(1)));
    assertEquals("2026-03-01 05:00:00.000012", TimeText.instant(BASE + 12_000, 6, ZoneOffset.UTC));
    assertEquals("1969-12-31 23:59:59.500", TimeText.instant(-SECOND / 2, 1, ZoneOffset.UTC));
  }

  @Test
  void theCaretTimeAddsTheOffset() {
    TimeStatus status = new TimeStatus(BASE, 3, TimestampParser.NONE, TimestampParser.NONE, 0);
    assertEquals("2026-03-01 04:59:30.000", TimeText.caret(status, -30 * SECOND, ZoneOffset.UTC));
    assertEquals("", TimeText.caret(TimeStatus.NONE, 0, ZoneOffset.UTC));
    assertEquals("", TimeText.caret(status, Long.MAX_VALUE, ZoneOffset.UTC));
  }

  @Test
  void differencesCoverSubSecondDaysNegativeAndFractionDigits() {
    assertEquals("+00:00:00.250", TimeText.difference(range(BASE, BASE + SECOND / 4, 0)));
    assertEquals(
        "+00:01:23.456", TimeText.difference(range(BASE, BASE + 83 * SECOND + 456_000_000L, 3)));
    assertEquals(
        "+2d 03:00:00.000",
        TimeText.difference(range(BASE, BASE + 2 * DAY + 3 * 3600 * SECOND, 3)));
    assertEquals("-00:00:05.000", TimeText.difference(range(BASE, BASE - 5 * SECOND, 3)));
    assertEquals("+00:00:00.000000123", TimeText.difference(range(BASE, BASE + 123, 9)));
    assertEquals("", TimeText.difference(range(BASE, TimestampParser.NONE, 3)));
  }

  @Test
  void offsetsFormatAndParseBack() {
    assertEquals("+00:00:00", TimeText.offset(0));
    assertEquals("-1.02:00:00.500", TimeText.offset(-(DAY + 2 * 3600 * SECOND + SECOND / 2)));
    assertEquals("+00:00:01.000001", TimeText.offset(SECOND + 1000));
    long[] values = {0, 1, -1, 90 * SECOND, -(3 * DAY + 7), Long.MAX_VALUE, -Long.MAX_VALUE};
    for (long value : values) {
      assertEquals(value, TimeText.parseOffset(TimeText.offset(value)), TimeText.offset(value));
    }
    assertEquals(-90 * SECOND, TimeText.parseOffset(" -00:01:30 "));
    assertEquals(5 * SECOND + 5_000_000, TimeText.parseOffset("0:00:05,005"));
    assertEquals(TimeText.INVALID, TimeText.parseOffset("00:60:00"));
    assertEquals(TimeText.INVALID, TimeText.parseOffset("1.24:00:00"));
    assertEquals(TimeText.INVALID, TimeText.parseOffset("999999.00:00:00"));
    assertEquals(TimeText.INVALID, TimeText.parseOffset("five minutes"));
  }

  @Test
  void targetsTakeADateOrTheCaretLinesDate() {
    assertEquals(
        BASE + 1_500_000, TimeText.parseTarget("2026-03-01 05:00:00.0015", ZoneOffset.UTC, null));
    assertEquals(BASE, TimeText.parseTarget("2026-03-01T06:00:00", ZoneOffset.ofHours(1), null));
    assertEquals(
        BASE + 61 * SECOND,
        TimeText.parseTarget("05:01:01", ZoneOffset.UTC, LocalDate.of(2026, 3, 1)));
    assertEquals(TimeText.INVALID, TimeText.parseTarget("05:01:01", ZoneOffset.UTC, null));
    assertEquals(
        TimeText.INVALID, TimeText.parseTarget("2026-02-30 00:00:00", ZoneOffset.UTC, null));
    assertEquals(
        TimeText.INVALID, TimeText.parseTarget("2300-01-01 00:00:00", ZoneOffset.UTC, null));
    assertEquals(LocalDate.of(2026, 3, 1), TimeText.date(BASE, ZoneOffset.UTC));
  }

  @Test
  void namesAndTooltip() {
    assertEquals(UiText.TIME_FORMAT_SYSLOG.text(), TimeText.formatName(BuiltInFormat.SYSLOG));
    assertEquals("app", TimeText.formatName(new UserFormat("app", "(?<ts>\\d+)", "HH")));
    for (BuiltInFormat format : BuiltInFormat.values()) {
      assertEquals(false, TimeText.formatName(format).isEmpty());
    }
    assertEquals(
        UiText.TIME_FIELD_TIP.format(UiText.TIME_FORMAT_ISO.text(), "Europe/Berlin", "+00:00:30"),
        TimeText.tooltip(BuiltInFormat.ISO, ZoneId.of("Europe/Berlin"), 30 * SECOND));
  }
}
