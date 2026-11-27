package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.io.ReadBuffers;
import io.github.ajdailey.qlogg.core.testkit.SyntheticRecords;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * {@link RecordScan} against {@link RecordReference} over generated CSV, TSV and log4j XML: block
 * sizes that cut lines, quotes and escapes, UTF-16, growth that splits a record or a line across
 * passes, 4 MiB blocks with and without quotes, the line and byte bounds, and a {@code RESULTS}
 * refusal.
 */
class RecordScanTest {
  private static final MemoryBudget BUDGET = MemoryBudget.of(1L << 30);
  private static final int[] BLOCKS = {16, 61, 1000, 4096, 1 << 20};

  private SearchFixture fixture;

  @BeforeEach
  void create() {
    fixture = new SearchFixture();
  }

  @AfterEach
  void close() {
    fixture.close();
  }

  static ColumnSchema schema(ColumnFormat format) {
    return switch (format) {
      case CSV, TSV ->
          ColumnSchema.delimited(
              format,
              format == ColumnFormat.CSV ? ',' : '\t',
              List.of("time", "level", "logger", "message", "count"),
              true);
      default -> ColumnSchema.fixed(format);
    };
  }

  private RecordScan scan(
      ColumnSchema schema, RecordReference.Source source, int block, MemoryBudget budget)
      throws Exception {
    RecordScan scan = new RecordScan(schema, source.encoding(), budget);
    scan.scan(source.bytes(), source.index(), buffers(block), new CancellationToken());
    return scan;
  }

  private static ReadBuffers buffers(int block) {
    return new ReadBuffers(block, 2, BUDGET);
  }

  private void assertScans(String text, ColumnSchema schema, Charset charset, int[] blocks)
      throws Exception {
    RecordReference.Source source =
        RecordReference.index(fixture.workers, text.getBytes(charset), charset, BUDGET);
    List<String> lines = RecordReference.lines(text);
    boolean complete = text.endsWith("\n");
    List<Long> expected = RecordReference.recordStarts(lines, complete, schema, charset);
    for (int block : blocks) {
      RecordScan scan = scan(schema, source, block, BUDGET);
      assertEquals(complete ? lines.size() : lines.size() - 1, scan.classifiedLines());
      RecordReference.assertSameStarts(
          expected,
          RecordReference.snapshotStarts(scan.snapshot(), lines.size()),
          schema.format().id() + " " + charset + " block " + block);
    }
  }

  @Test
  void scanEqualsTheReferenceForEveryFormatBlockAndEncoding() throws Exception {
    ColumnFormat[] formats = {ColumnFormat.CSV, ColumnFormat.TSV, ColumnFormat.LOG4J_XML};
    for (ColumnFormat format : formats) {
      String text = new SyntheticRecords(format, 11, true, 0.2).text(1500);
      assertScans(text, schema(format), StandardCharsets.UTF_8, BLOCKS);
      assertScans(text, schema(format), StandardCharsets.UTF_16LE, new int[] {64, 4096});
      assertScans(text + "a\"b,\"c", schema(format), StandardCharsets.UTF_8, new int[] {61});
    }
  }

  @Test
  void growthInPiecesEqualsOnePass() throws Exception {
    Random random = new Random(5);
    ColumnFormat[] formats = {ColumnFormat.CSV, ColumnFormat.LOG4J_XML};
    for (ColumnFormat format : formats) {
      ColumnSchema schema = schema(format);
      byte[] bytes =
          new SyntheticRecords(format, 12, true, 0.2).text(800).getBytes(StandardCharsets.UTF_8);
      RecordReference.Source full =
          RecordReference.index(fixture.workers, bytes, StandardCharsets.UTF_8, BUDGET);
      List<String> lines = RecordReference.lines(new String(bytes, StandardCharsets.UTF_8));
      List<Long> expected =
          RecordReference.recordStarts(lines, true, schema, StandardCharsets.UTF_8);
      for (int trial = 0; trial < 6; trial++) {
        RecordScan scan = new RecordScan(schema, full.encoding(), BUDGET);
        int cut = 0;
        while (cut < bytes.length) {
          cut = Math.min(bytes.length, cut + 1 + random.nextInt(bytes.length / 4));
          byte[] prefix = Arrays.copyOf(bytes, cut);
          RecordReference.Source part =
              RecordReference.index(fixture.workers, prefix, StandardCharsets.UTF_8, BUDGET);
          scan.scan(part.bytes(), part.index(), buffers(61), new CancellationToken());
        }
        assertEquals(full.index().lineCount(), scan.classifiedLines());
        RecordReference.assertSameStarts(
            expected, RecordReference.snapshotStarts(scan.snapshot(), lines.size()), "growth");
      }
    }
  }

  @Test
  void fourMibBlocksWithAndWithoutQuotesEqualTheReference() throws Exception {
    for (double quoted : new double[] {0, 0.3}) {
      StringBuilder text = new StringBuilder();
      SyntheticRecords records = new SyntheticRecords(ColumnFormat.CSV, 13, false, quoted);
      while (text.length() < 10 << 20) {
        records.append(text);
      }
      text.append("1,INFO,mock,\"a\nb\",2\n");
      assertScans(
          text.toString(), schema(ColumnFormat.CSV), StandardCharsets.UTF_8, new int[] {4 << 20});
    }
  }

  @Test
  void aRecordEndsAtTheLineAndByteBounds() throws Exception {
    StringBuilder lines = new StringBuilder("a,b\n1,\"open\n");
    for (int i = 0; i < 25_000; i++) {
      lines.append("x").append(i).append('\n');
    }
    assertScans(
        lines.toString(), schema(ColumnFormat.CSV), StandardCharsets.UTF_8, new int[] {4096});
    StringBuilder wide = new StringBuilder("a,b\n1,\"open\n");
    String row = "y".repeat(300_000) + "\n";
    for (int i = 0; i < 10; i++) {
      wide.append(row);
    }
    assertScans(
        wide.toString(), schema(ColumnFormat.CSV), StandardCharsets.UTF_8, new int[] {4096});
    StringBuilder events = new StringBuilder("<log4j:event level=\"INFO\">\n");
    for (int i = 0; i < 25_000; i++) {
      events.append("text ").append(i).append('\n');
    }
    assertScans(
        events.toString(),
        schema(ColumnFormat.LOG4J_XML),
        StandardCharsets.UTF_8,
        new int[] {1000});
  }

  @Test
  void aResultsRefusalStopsTheScanWithTheClassifiedLinesCorrect() throws Exception {
    String text = new SyntheticRecords(ColumnFormat.LOG4J_XML, 14, false, 0).text(40_000);
    ColumnSchema schema = schema(ColumnFormat.LOG4J_XML);
    RecordReference.Source source =
        RecordReference.index(
            fixture.workers, text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, BUDGET);
    RecordScan scan = scan(schema, source, 4096, MemoryBudget.of(100_000));
    assertTrue(scan.refused());
    long classified = scan.classifiedLines();
    assertTrue(classified < source.index().lineCount(), String.valueOf(classified));
    List<String> lines = RecordReference.lines(text);
    List<Long> expected =
        RecordReference.recordStarts(
            lines.subList(0, (int) classified), true, schema, StandardCharsets.UTF_8);
    List<Long> actual = RecordReference.snapshotStarts(scan.snapshot(), classified);
    RecordReference.assertSameStarts(expected, actual, "refused");
    assertTrue(scan.snapshot().starts(classified + 1));
    scan.scan(source.bytes(), source.index(), buffers(4096), new CancellationToken());
    assertEquals(classified, scan.classifiedLines());
  }

  @Test
  void singleLineFormatsHaveNoScanAndCancelStopsBeforeABlock() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            new RecordScan(
                ColumnSchema.fixed(ColumnFormat.CLEF),
                TextEncoding.of(StandardCharsets.UTF_8, 0),
                BUDGET);
          }
        });
    String text = new SyntheticRecords(ColumnFormat.CSV, 15, false, 0.5).text(200);
    final RecordReference.Source source =
        RecordReference.index(
            fixture.workers, text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, BUDGET);
    final RecordScan scan = new RecordScan(schema(ColumnFormat.CSV), source.encoding(), BUDGET);
    final CancellationToken token = new CancellationToken();
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            scan.scan(source.bytes(), source.index(), buffers(61), token);
          }
        });
    assertEquals(0, scan.classifiedLines());
    assertFalse(scan.refused());
    scan.scan(source.bytes(), source.index(), buffers(61), new CancellationToken());
    assertEquals(source.index().lineCount(), scan.classifiedLines());
  }
}
