package io.github.ajdailey.qlogg.core.columns;

import static io.github.ajdailey.qlogg.core.columns.ColumnFixture.UTC;
import static io.github.ajdailey.qlogg.core.columns.ColumnFixture.parsed;
import static io.github.ajdailey.qlogg.core.columns.ColumnFixture.unparsed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.time.TimestampParser;
import io.github.ajdailey.qlogg.core.time.UserFormat;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** The {@code time} format, the schema factories and the format ids. */
class StampParserTest {
  private static final long MAR_1_002 = nanos("2026-03-01T05:00:00.002Z");

  @Test
  void timeIsTheStampAndTextTheRest() {
    FieldParser parser = ColumnSchema.timestamp(BuiltInFormat.ISO).parser(UTC);
    assertEquals(
        List.of("2026-03-01 05:00:00.002", "INFO [main] mock"),
        parsed(parser, "2026-03-01 05:00:00.002 \tINFO [main] mock"));
    assertEquals(MAR_1_002, parser.nanos());
    assertEquals(3, parser.fractionDigits());
    assertEquals(
        List.of("[2026-03-01 05:00:00.002", "mock"),
        parsed(parser, "[2026-03-01 05:00:00.002] mock"));
    assertEquals(List.of("2026-03-01 05:00:00", ""), parsed(parser, "2026-03-01 05:00:00"));
    unparsed(parser, "\tat mock.Frame.run(Frame.java:1)");
    assertEquals(TimestampParser.NONE, parser.nanos());
  }

  @Test
  void aUserFormatEndsAtItsGroup() {
    UserFormat format = new UserFormat("mock", "at (?<ts>\\S+ \\S+)", "yyyy-MM-dd HH:mm:ss.SSS");
    FieldParser parser = ColumnSchema.timestamp(format).parser(UTC);
    assertEquals(
        List.of("id=7 at 2026-03-01 05:00:00.002", "entry"),
        parsed(parser, "id=7 at 2026-03-01 05:00:00.002 entry"));
  }

  @Test
  void schemasCheckTheirSettings() {
    assertEquals(9, ColumnSchema.fixed(ColumnFormat.CLF).columns().size());
    assertEquals(3, ColumnSchema.fixed(ColumnFormat.CLF).timeColumn());
    assertEquals(0, ColumnSchema.fixed(ColumnFormat.LOG4J_XML).timeColumn());
    assertEquals(true, ColumnFormat.CLF.columns().get(1).hidden());
    assertEquals("Ident", ColumnFormat.CLF.columns().get(1).name());
    ColumnSchema csv =
        ColumnSchema.delimited(ColumnFormat.CSV, ',', List.of("a", "x".repeat(300)), true);
    assertEquals(ColumnSchema.MAX_NAME_CHARS, csv.columns().get(1).name().length());
    assertEquals(-1, csv.timeColumn());
    ColumnSchema numbered =
        ColumnSchema.delimited(ColumnFormat.TSV, '\t', List.of("a", "b"), false);
    assertEquals("2", numbered.columns().get(1).name());
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            ColumnSchema.delimited(ColumnFormat.CSV, ',', List.of(), true);
          }
        });
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            ColumnSchema.fixed(ColumnFormat.CSV);
          }
        });
    for (ColumnFormat format : ColumnFormat.values()) {
      assertSame(format, ColumnFormat.byId(format.id()));
    }
    assertNull(ColumnFormat.byId("none"));
  }

  private static long nanos(String instant) {
    Instant parsed = Instant.parse(instant);
    return parsed.getEpochSecond() * 1_000_000_000L + parsed.getNano();
  }
}
