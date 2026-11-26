package io.github.ajdailey.qlogg.bench;

import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.columns.FieldParser;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Parses one screen of generated records, {@link #ROWS} rows of about 200 chars, with the {@code
 * format}'s {@link FieldParser}: the parse share of the column view's visible-row read.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(
    value = 1,
    jvmArgsPrepend = {Harness.LAUNCHER_HEAP, Harness.LAUNCHER_GC})
public class ColumnProbe {
  /** Rows per screen. */
  public static final int ROWS = 60;

  private static final TimeContext UTC = new TimeContext(ZoneOffset.UTC, 2026, 11);

  /** Format id: {@code csv}, {@code clf}, {@code clef}, {@code log4j-xml} or {@code time}. */
  @Param({"csv", "clf", "clef", "log4j-xml", "time"})
  public String format;

  private FieldParser parser;
  private String[] rows;

  /** Builds the parser and the rows. */
  @Setup(Level.Trial)
  public void prepare() {
    ColumnFormat columns = ColumnFormat.byId(format);
    ColumnSchema schema =
        switch (columns) {
          case CSV, TSV -> ColumnSchema.delimited(columns, ',', names(8), true);
          case TIME -> ColumnSchema.timestamp(BuiltInFormat.ISO);
          default -> ColumnSchema.fixed(columns);
        };
    parser = schema.parser(UTC);
    Random random = new Random(3);
    rows = new String[ROWS];
    for (int i = 0; i < ROWS; i++) {
      rows[i] = row(columns, random, i);
    }
  }

  /**
   * Parses every row once.
   *
   * @return a checksum
   */
  @Benchmark
  public long screen() {
    long sum = 0;
    for (int i = 0; i < ROWS; i++) {
      parser.parse(rows[i]);
      sum += parser.values().end(parser.values().count() - 1);
    }
    return sum;
  }

  private static List<String> names(int count) {
    List<String> names = new ArrayList<String>();
    for (int i = 0; i < count; i++) {
      names.add("column" + i);
    }
    return names;
  }

  private static String row(ColumnFormat format, Random random, int i) {
    String words = words(random, 90);
    return switch (format) {
      case CSV, TSV ->
          "2026-11-01 12:00:0"
              + i % 10
              + ",INFO,worker-"
              + i
              + ",\""
              + words
              + ", quoted\",mock,12345,flagword,\"say \"\"hi\"\"\"";
      case CLF ->
          "10.0.0."
              + i
              + " - mock [01/Nov/2026:12:00:00 +0000] \"GET /mock/"
              + i
              + " HTTP/1.1\" 200 1234 \"http://mock.example/\" \"Mock/1.0 ("
              + words
              + ")\"";
      case CLEF ->
          "{\"@t\":\"2026-11-01T12:00:00.123Z\",\"@mt\":\"Run {Name} for {Count} "
              + words
              + "\",\"Name\":\"mock\",\"Count\":"
              + i
              + ",\"@l\":\"Warning\"}";
      case LOG4J_XML ->
          "<log4j:event logger=\"mock.Logger\" timestamp=\"1793534400000\" "
              + "level=\"INFO\" thread=\"main\">\n<log4j:message><![CDATA["
              + words
              + "]]></log4j:message>\n</log4j:event>";
      case TIME -> "2026-11-01 12:00:00.123 INFO [worker-" + i + "] " + words + words;
    };
  }

  private static String words(Random random, int length) {
    String[] words = {"mock", "event", "flagword", "queue", "worker", "record", "batch"};
    StringBuilder text = new StringBuilder();
    while (text.length() < length) {
      text.append(words[random.nextInt(words.length)]).append(' ');
    }
    return text.toString();
  }
}
