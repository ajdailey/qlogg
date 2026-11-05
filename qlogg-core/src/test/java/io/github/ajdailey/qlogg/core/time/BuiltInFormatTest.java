package io.github.ajdailey.qlogg.core.time;

import static io.github.ajdailey.qlogg.core.time.TimeFixture.UTC;
import static io.github.ajdailey.qlogg.core.time.TimeFixture.nanos;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Instant;
import java.time.Year;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class BuiltInFormatTest {
  private static final long MAR_1 = nanos("2026-03-01T05:00:00Z");
  private static final long MAR_1_002 = nanos("2026-03-01T05:00:00.002Z");

  @Test
  void eachLayoutParses() {
    check(BuiltInFormat.ISO, "2026-03-01 05:00:00,002 INFO mock", MAR_1_002, 3);
    check(BuiltInFormat.ISO, "2026-03-01T05:00:00.002Z mock", MAR_1_002, 3);
    check(BuiltInFormat.YMD_SLASH, "2026/03/01 05:00:00.002 mock", MAR_1_002, 3);
    check(BuiltInFormat.YMD_DOT, "2026.03.01 05:00:00 mock", MAR_1, 0);
    check(BuiltInFormat.DMY_DOT, "01.03.2026 05:00:00.002 mock", MAR_1_002, 3);
    check(BuiltInFormat.DMY_DOT, "01.03.26 05:00:00 mock", MAR_1, 0);
    check(BuiltInFormat.DMY_SLASH, "01/03/2026 05:00:00 mock", MAR_1, 0);
    check(BuiltInFormat.MDY_SLASH, "03/01/2026 05:00:00 mock", MAR_1, 0);
    check(BuiltInFormat.DMONY, "01 Mar 2026 05:00:00,002 mock", MAR_1_002, 3);
    check(BuiltInFormat.DMONY, "1 MAR 2026 05:00:00 mock", MAR_1, 0);
    check(BuiltInFormat.SYSLOG, "Mar  1 05:00:00 host-a mock[12]: entry", MAR_1, 0);
    check(BuiltInFormat.SYSLOG, "Mar 01 05:00:00 host-a mock", MAR_1, 0);
    check(BuiltInFormat.JUL, "Mar 01, 2026 5:00:00 AM mock.Service run", MAR_1, 0);
    check(
        BuiltInFormat.JUL,
        "Mar 01, 2026 12:30:00 PM mock.Service run",
        nanos("2026-03-01T12:30:00Z"),
        0);
    check(BuiltInFormat.JUL, "Mar 01, 2026 12:00:00 am mock", nanos("2026-03-01T00:00:00Z"), 0);
  }

  @Test
  void fractionsOfZeroToNineDigitsWithDotOrComma() {
    String digits = "123456789";
    for (int n = 0; n <= 9; n++) {
      for (String separator : new String[] {".", ","}) {
        String line =
            "2026-03-01 05:00:00" + (n == 0 ? "" : separator + digits.substring(0, n)) + " mock";
        long fraction =
            n == 0 ? 0 : Long.parseLong(digits.substring(0, n) + "000000000".substring(n));
        check(BuiltInFormat.ISO, line, MAR_1 + fraction, n);
      }
    }
    none(BuiltInFormat.ISO, "2026-03-01 05:00:00.1234567890 mock");
  }

  @Test
  void eachOffsetForm() {
    long four = nanos("2026-03-01T04:00:00Z");
    check(BuiltInFormat.ISO, "2026-03-01T05:00:00Z", MAR_1, 0);
    check(BuiltInFormat.ISO, "2026-03-01T05:00:00+01:00 mock", four, 0);
    check(BuiltInFormat.ISO, "2026-03-01 05:00:00.002+0100 mock", four + 2_000_000L, 3);
    check(BuiltInFormat.ISO, "2026-03-01T05:00:00-05:30", nanos("2026-03-01T10:30:00Z"), 0);
    check(
        BuiltInFormat.CLF,
        "host-a - - [01/Mar/2026:05:00:00 +0100] \"GET /mock HTTP/1.1\" 200 12",
        four,
        0);
    none(BuiltInFormat.CLF, "host-a - - [01/Mar/2026:05:00:00] \"GET /mock\"");
  }

  @Test
  void invalidFieldsDoNotParse() {
    check(BuiltInFormat.ISO, "2024-02-29 05:00:00", nanos("2024-02-29T05:00:00Z"), 0);
    none(BuiltInFormat.ISO, "2026-02-29 05:00:00");
    none(BuiltInFormat.ISO, "2026-13-01 05:00:00");
    none(BuiltInFormat.ISO, "2026-03-01 24:00:00");
    none(BuiltInFormat.ISO, "2026-03-01 05:60:00");
    none(BuiltInFormat.ISO, "2026-03-01");
    none(BuiltInFormat.MDY_SLASH, "13/01/2026 05:00:00");
    none(BuiltInFormat.SYSLOG, "Mak  1 05:00:00");
    none(BuiltInFormat.JUL, "Mar 01, 2026 13:00:00 PM");
    none(BuiltInFormat.ISO, "2263-01-01 00:00:00");
    none(BuiltInFormat.ISO, "");
  }

  @Test
  void leadingBracketAndClfInsideTheLine() {
    check(BuiltInFormat.ISO, "[2026-03-01 05:00:00.002] mock", MAR_1_002, 3);
    check(BuiltInFormat.SYSLOG, "[Mar  1 05:00:00] mock", MAR_1, 0);
    none(BuiltInFormat.ISO, "x [2026-03-01 05:00:00] mock");
    check(BuiltInFormat.CLF, "[x] [01/Mar/2026:05:00:00 +0000] mock", MAR_1, 0);
    none(BuiltInFormat.CLF, "x".repeat(TimeReader.PREFIX_CHARS) + "[01/Mar/2026:05:00:00 +0000]");
  }

  @Test
  void epochSecondsAndMilliseconds() {
    long seconds = Instant.parse("2026-03-01T05:00:00Z").getEpochSecond();
    check(BuiltInFormat.EPOCH, seconds + " mock", MAR_1, 0);
    check(BuiltInFormat.EPOCH, seconds + ".002 mock", MAR_1_002, 3);
    check(BuiltInFormat.EPOCH, seconds + "002 mock", MAR_1_002, 3);
    check(BuiltInFormat.EPOCH, seconds + "002.5 mock", MAR_1_002 + 500_000L, 4);
    check(BuiltInFormat.EPOCH, "[" + seconds + "]", MAR_1, 0);
    check(BuiltInFormat.EPOCH, String.valueOf(seconds), MAR_1, 0);
    none(BuiltInFormat.EPOCH, seconds + "0 mock");
    none(BuiltInFormat.EPOCH, "123456789 mock");
    none(BuiltInFormat.EPOCH, seconds + ".1234567890");
  }

  @Test
  void dstOverlapMapsOntoTheFirstOccurrenceAndAGapTakesTheOffsetBefore() {
    TimestampParser parser =
        BuiltInFormat.ISO.parser(new TimeContext(ZoneId.of("America/New_York"), 2025, 11));
    assertEquals(nanos("2025-11-02T04:59:59Z"), parser.parse("2025-11-02 00:59:59"));
    assertEquals(nanos("2025-11-02T05:30:00Z"), parser.parse("2025-11-02 01:30:00"));
    assertEquals(nanos("2025-11-02T08:00:00Z"), parser.parse("2025-11-02 03:00:00"));
    assertEquals(nanos("2025-03-09T07:30:00Z"), parser.parse("2025-03-09 02:30:00"));
    assertEquals(nanos("2025-03-09T07:00:00Z"), parser.parse("2025-03-09 03:00:00"));
    assertEquals(nanos("2025-03-09T06:59:59Z"), parser.parse("2025-03-09 01:59:59"));
  }

  /** Reference 2026-03: 1976-04 is 599 months back, 2076-02 is 599 ahead, 03 ties. */
  @Test
  void twoDigitYearsTakeTheCenturyNearestTheModificationTime() {
    check(BuiltInFormat.DMY_DOT, "01.04.76 05:00:00", nanos("1976-04-01T05:00:00Z"), 0);
    check(BuiltInFormat.DMY_DOT, "01.02.76 05:00:00", nanos("2076-02-01T05:00:00Z"), 0);
    check(BuiltInFormat.DMY_DOT, "01.03.76 05:00:00", nanos("1976-03-01T05:00:00Z"), 0);
    check(BuiltInFormat.DMY_DOT, "31.12.99 23:59:59", nanos("1999-12-31T23:59:59Z"), 0);
    TimestampParser late = BuiltInFormat.DMY_DOT.parser(new TimeContext(ZoneId.of("UTC"), 2098, 6));
    assertEquals(nanos("2102-03-01T05:00:00Z"), late.parse("01.03.02 05:00:00"));
  }

  @Test
  void withoutAModificationTimeTheCurrentDateIsTheReference() {
    TimeContext current = TimeContext.current(ZoneId.of("UTC"));
    assertEquals(Year.now(ZoneId.of("UTC")).getValue(), current.fileYear());
    int year = current.fileYear();
    String line = String.format("01.%02d.%02d 05:00:00", current.fileMonth(), year % 100);
    String instant = String.format("%d-%02d-01T05:00:00Z", year, current.fileMonth());
    assertEquals(nanos(instant), BuiltInFormat.DMY_DOT.parser(current).parse(line));
  }

  @Test
  void syslogYearFollowsTheModificationMonth() {
    TimestampParser parser =
        BuiltInFormat.SYSLOG.parser(new TimeContext(ZoneId.of("UTC"), 2026, 1));
    assertEquals(nanos("2025-12-31T23:59:59Z"), parser.parse("Dec 31 23:59:59 host-a mock"));
    assertEquals(nanos("2026-01-01T00:00:01Z"), parser.parse("Jan  1 00:00:01 host-a mock"));
    assertEquals(
        2024, new TimeContext(ZoneId.of("UTC"), 2025, 2).yearOf(3), "March before a February file");
  }

  @Test
  void idsRoundTrip() {
    for (BuiltInFormat format : BuiltInFormat.values()) {
      assertSame(format, BuiltInFormat.byId(format.id()));
    }
    assertNull(BuiltInFormat.byId("none"));
  }

  private static void check(BuiltInFormat format, String line, long expected, int digits) {
    TimestampParser parser = format.parser(UTC);
    assertEquals(expected, parser.parse(line), line);
    assertEquals(digits, parser.fractionDigits(), line);
  }

  private static void none(BuiltInFormat format, String line) {
    assertEquals(TimestampParser.NONE, format.parser(UTC).parse(line), line);
  }
}
