package io.github.ajdailey.qlogg.bench;

import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.columns.FieldParser;
import io.github.ajdailey.qlogg.core.columns.TimeCell;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.CellReader;
import io.github.ajdailey.qlogg.core.model.ColumnLayout;
import io.github.ajdailey.qlogg.core.model.ColumnRead;
import io.github.ajdailey.qlogg.core.model.DocumentListener;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.LineBlock;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.search.RecordLines;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.SortedLines;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Parses one screen of generated records, {@link #ROWS} rows of about 200 chars, with the {@code
 * format}'s {@link FieldParser}: the parse share of the column view's visible-row read ({@link
 * #screen}). {@link #compose} reads the same rows from an in-heap document through a {@link
 * CellReader} with syntax colors, as the column view's read worker does, its row cache emptied
 * every call; {@link #composeCached} repeats one read, so every row comes from the cache.
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
  private Workers workers;
  private LogDocument document;
  private CellReader cells;
  private ColumnRead view;
  private long[] lines;
  private long shift;

  /**
   * Builds the parser, the rows and the document holding them.
   *
   * @throws IOException when the document cannot be created or indexed
   * @throws InterruptedException when interrupted while indexing
   */
  @Setup(Level.Trial)
  public void prepare() throws IOException, InterruptedException {
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
    openDocument(schema);
  }

  private void openDocument(ColumnSchema schema) throws IOException, InterruptedException {
    StringBuilder text = new StringBuilder();
    long[] starts = new long[ROWS];
    lines = new long[ROWS];
    long line = 0;
    for (int i = 0; i < ROWS; i++) {
      starts[i] = line;
      lines[i] = line;
      text.append(rows[i]).append('\n');
      line++;
      for (int k = 0; k < rows[i].length(); k++) {
        if (rows[i].charAt(k) == '\n') {
          line++;
        }
      }
    }
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                error.printStackTrace();
              }
            });
    MemoryBudget budget = MemoryBudget.fromRuntime();
    DocumentServices services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(2, budget)),
            new SearchEngine(
                workers, StandardMatchers.INSTANCE, SearchSettings.defaults(2, budget)),
            new BlockCache(65536, 1 << 20),
            budget,
            StandardCharsets.UTF_8);
    document = LogDocument.ofText("probe", text.toString(), 1 << 20, services);
    final CountDownLatch indexed = new CountDownLatch(1);
    document.addListener(
        new DocumentListener() {
          @Override
          public void indexProgressed(IndexProgress progress) {
            // Only the end matters.
          }

          @Override
          public void indexFinished(OperationOutcome outcome, IOException failure) {
            indexed.countDown();
          }

          @Override
          public void fileChanged(FileChange change) {}

          @Override
          public void closed() {
            // Closed in tearDown.
          }
        });
    document.startIndexing();
    if (!indexed.await(1, TimeUnit.MINUTES)) {
      throw new IOException("Indexing did not finish");
    }
    RecordLines records =
        schema.format().multiLine() ? RecordLines.starts(new SortedLines(starts), line) : null;
    view =
        new ColumnRead(
            ColumnLayout.of(schema),
            UTC,
            new TimeCell(ZoneOffset.UTC, 0),
            records,
            new SortedLines(new long[0]));
    cells = new CellReader(document.newReader());
  }

  /** Closes the document and the workers. */
  @TearDown(Level.Trial)
  public void tearDown() {
    document.close();
    workers.close();
  }

  /**
   * Reads, parses and composes every row once, with syntax colors, the cache emptied first.
   *
   * @return a checksum
   * @throws IOException when a read fails
   */
  @Benchmark
  public long compose() throws IOException {
    shift ^= 1;
    LineBlock block =
        cells.readRows(document.index(), 0, lines, ROWS, shift, 200, view, null, true);
    return block.maxWidth() + block.count();
  }

  /**
   * Reads every row once from the row cache.
   *
   * @return a checksum
   * @throws IOException when a read fails
   */
  @Benchmark
  public long composeCached() throws IOException {
    LineBlock block = cells.readRows(document.index(), 0, lines, ROWS, 0, 200, view, null, true);
    return block.maxWidth() + block.count();
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
