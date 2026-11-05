package io.github.ajdailey.qlogg.core.time;

import static io.github.ajdailey.qlogg.core.time.TimeFixture.UTC;
import static io.github.ajdailey.qlogg.core.time.TimeFixture.nanos;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class UserFormatTest {
  @Test
  void locatesAndParsesTheGroup() {
    UserFormat format = new UserFormat("mock", "at (?<ts>\\S+ \\S+)", "yyyy-MM-dd HH:mm:ss.SSS");
    assertEquals("user:mock", format.id());
    TimestampParser parser = format.parser(UTC);
    assertEquals(
        nanos("2026-03-01T05:00:00.002Z"), parser.parse("id=7 at 2026-03-01 05:00:00.002 entry"));
    assertEquals(3, parser.fractionDigits());
    assertEquals(TimestampParser.NONE, parser.parse("\tat mock.Frame.run"));
    assertEquals(TimestampParser.NONE, parser.parse("id=8 at 2026-13-01 05:00:00.002 entry"));
  }

  @Test
  void anOffsetInTheTextGivesTheInstant() {
    TimestampParser parser =
        new UserFormat("offset", "^(?<ts>\\S+)", "yyyy-MM-dd'T'HH:mm:ssXXX")
            .parser(new TimeContext(ZoneId.of("Asia/Tokyo"), 2026, 3));
    assertEquals(nanos("2026-03-01T04:00:00Z"), parser.parse("2026-03-01T05:00:00+01:00 mock"));
  }

  @Test
  void monthAndDayTakeTheFileYearAndATimeAloneIsNoStamp() {
    TimeContext january = new TimeContext(ZoneId.of("UTC"), 2026, 1);
    TimestampParser parser =
        new UserFormat("md", "^(?<ts>\\d\\d-\\d\\d \\S+)", "MM-dd HH:mm:ss").parser(january);
    assertEquals(nanos("2025-12-31T23:00:00Z"), parser.parse("12-31 23:00:00 mock"));
    TimestampParser timeOnly = new UserFormat("t", "^(?<ts>\\S+)", "HH:mm:ss").parser(january);
    assertEquals(TimestampParser.NONE, timeOnly.parse("05:00:00 mock"));
  }

  @Test
  void reachingTheBoundCountsAsNoStamp() {
    TimestampParser parser = new UserFormat("slow", "(?<ts>(a+)+b)", "yyyy").parser(UTC);
    long start = System.nanoTime();
    assertEquals(TimestampParser.NONE, parser.parse("a".repeat(200)));
    assertTrue(System.nanoTime() - start < 10_000_000_000L, "bounded");
  }

  @Test
  void aStackOverflowCountsAsNoStamp() {
    TimestampParser parser = new UserFormat("deep", "(?<ts>.+)", "yyyy").parser(UTC);
    CharSequence overflowing =
        new CharSequence() {
          @Override
          public int length() {
            return 10;
          }

          @Override
          public char charAt(int index) {
            throw new StackOverflowError();
          }

          @Override
          public CharSequence subSequence(int from, int to) {
            return this;
          }
        };
    assertEquals(TimestampParser.NONE, parser.parse(overflowing));
  }

  @Test
  void invalidDefinitionsAreRefused() {
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            new UserFormat("bad", "(?<ts>.+)", "yyyy-MM-dd {");
          }
        });
    assertThrows(
        PatternSyntaxException.class,
        new Executable() {
          @Override
          public void execute() {
            new UserFormat("bad", "(?<ts>", "yyyy");
          }
        });
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            new UserFormat("bad", "(?<time>.+)", "yyyy");
          }
        });
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            new UserFormat(" ", "(?<ts>.+)", "yyyy");
          }
        });
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            new UserFormat("long", "(?<ts>.+)" + "x".repeat(UserFormat.MAX_CHARS), "yyyy");
          }
        });
  }

  @Test
  void twoDigitYearsTakeTheCenturyNearestTheModificationTime() {
    TimestampParser local =
        new UserFormat("yy", "^(?<ts>\\S+ \\S+)", "dd.MM.yy HH:mm:ss").parser(UTC);
    assertEquals(nanos("1976-04-01T05:00:00Z"), local.parse("01.04.76 05:00:00 mock"));
    assertEquals(nanos("2076-02-01T05:00:00Z"), local.parse("01.02.76 05:00:00 mock"));
    TimestampParser offset =
        new UserFormat("yy", "^(?<ts>\\S+)", "yy-MM-dd'T'HH:mm:ssXXX").parser(UTC);
    assertEquals(nanos("1980-01-01T04:00:00Z"), offset.parse("80-01-01T05:00:00+01:00 mock"));
    assertTrue(UserFormat.twoDigitYear("uu-MM-dd"));
    assertFalse(UserFormat.twoDigitYear("yyyy-MM-dd 'yy'"));
    assertFalse(UserFormat.twoDigitYear("y-MM-dd"));
  }

  @Test
  void fractionDigitsComeFromThePattern() {
    assertEquals(0, UserFormat.fractionDigits("HH:mm:ss"));
    assertEquals(6, UserFormat.fractionDigits("HH:mm:ss.SSSSSS"));
    assertEquals(0, UserFormat.fractionDigits("HH:mm:ss 'SSS'"));
  }
}
