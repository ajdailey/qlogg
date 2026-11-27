package io.github.ajdailey.qlogg.bench;

import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.ReadBuffers;
import io.github.ajdailey.qlogg.core.model.DocumentListener;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.search.ColumnSearch;
import io.github.ajdailey.qlogg.core.search.ExpressionException;
import io.github.ajdailey.qlogg.core.search.ExpressionParser;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.search.RecordLines;
import io.github.ajdailey.qlogg.core.search.RecordScan;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchLimit;
import io.github.ajdailey.qlogg.core.search.SearchListener;
import io.github.ajdailey.qlogg.core.search.SearchQuery;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.testkit.SyntheticRecords;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
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
 * Record scan and column search over a generated, indexed file of records ({@link
 * SyntheticRecords}, edges off) of {@code fileMib} MiB, written once to {@code -Dqlogg.bench.dir}
 * and reused; the cache is warm. {@code scan} runs a {@link RecordScan} through read buffers of the
 * indexer's block size; {@code wholeLine} is the text-view literal search for {@link
 * SyntheticRecords#RARE}; {@code column} searches that literal in one verbatim column, so the
 * prefilter skips the parse of most records; {@code columnEveryLine} and {@code
 * columnEveryLineOneThread} search a regex with no required literal, so every record is parsed, on
 * every CPU and on one.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(
    value = 1,
    jvmArgsPrepend = {Harness.LAUNCHER_HEAP, Harness.LAUNCHER_INITIAL_HEAP, Harness.LAUNCHER_GC})
public class RecordProbe {
  private static final TimeContext UTC = new TimeContext(ZoneOffset.UTC, 2026, 11);

  /** File size in MiB. */
  @Param({"1024"})
  public int fileMib;

  /**
   * {@code csv} (no quotes), {@code csv-quoted} (30% of values quoted), {@code clef} or {@code
   * log4j-xml}; {@code scan} does nothing for {@code clef}, whose records are lines.
   */
  @Param({"csv", "csv-quoted", "clef", "log4j-xml"})
  public String format;

  private Workers workers;
  private Workers single;
  private MemoryBudget budget;
  private SearchEngine engine;
  private SearchEngine oneThread;
  private LogDocument document;
  private ColumnSchema schema;
  private ColumnSearch columns;
  private ReadBuffers buffers;

  /**
   * Writes the file when it does not exist, opens and indexes it, and scans its records.
   *
   * @throws IOException when the directory is missing, the write fails or indexing fails
   * @throws InterruptedException when interrupted while indexing
   */
  @Setup(Level.Trial)
  public void prepare() throws IOException, InterruptedException {
    String dir = System.getProperty("qlogg.bench.dir");
    if (dir == null) {
      throw new IOException("Set -Dqlogg.bench.dir to a scratch directory");
    }
    boolean quoted = format.equals("csv-quoted");
    ColumnFormat columnFormat = ColumnFormat.byId(quoted ? "csv" : format);
    String extension = columnFormat == ColumnFormat.CSV ? ".csv" : ".log";
    Path file = Path.of(dir).resolve("record-probe-" + format + "-" + fileMib + "m" + extension);
    if (!Files.exists(file)) {
      new SyntheticRecords(columnFormat, 7, false, quoted ? 0.3 : 0)
          .write(file, (long) fileMib << 20);
    }
    schema =
        columnFormat == ColumnFormat.CSV
            ? ColumnSchema.delimited(
                columnFormat, ',', List.of("time", "level", "logger", "message", "count"), true)
            : ColumnSchema.fixed(columnFormat);
    Thread.UncaughtExceptionHandler handler =
        new Thread.UncaughtExceptionHandler() {
          @Override
          public void uncaughtException(Thread thread, Throwable error) {
            error.printStackTrace();
          }
        };
    workers = new Workers(Runtime.getRuntime().availableProcessors(), handler);
    single = new Workers(1, handler);
    budget = MemoryBudget.fromRuntime();
    buffers = new ReadBuffers(IndexSettings.DEFAULT_BLOCK_BYTES, 2, budget);
    engine =
        new SearchEngine(
            workers,
            StandardMatchers.INSTANCE,
            SearchSettings.defaults(workers.cpuThreads(), budget));
    oneThread =
        new SearchEngine(single, StandardMatchers.INSTANCE, SearchSettings.defaults(1, budget));
    DocumentServices services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(workers.cpuThreads(), budget)),
            engine,
            new BlockCache(65536, 1 << 20),
            budget,
            StandardCharsets.ISO_8859_1);
    document = LogDocument.open(file, StandardCharsets.UTF_8, services);
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
    if (!indexed.await(10, TimeUnit.MINUTES)) {
      throw new IOException("Indexing did not finish");
    }
    RecordLines records = null;
    if (columnFormat.multiLine()) {
      RecordScan scan = new RecordScan(schema, document.encoding(), budget);
      scan.scan(document.bytes(), document.index(), buffers, new CancellationToken());
      records = scan.snapshot();
    }
    columns = new ColumnSearch(schema, UTC, records);
  }

  /** Closes the document and the workers. */
  @TearDown(Level.Trial)
  public void tearDown() {
    document.close();
    single.close();
    workers.close();
  }

  /**
   * One record scan of the whole file.
   *
   * @return lines classified, or -1 for a single-line format
   * @throws IOException when a read fails
   * @throws InterruptedException when interrupted
   */
  @Benchmark
  public long scan() throws IOException, InterruptedException {
    if (!schema.format().multiLine()) {
      return -1;
    }
    RecordScan scan = new RecordScan(schema, document.encoding(), budget);
    scan.scan(document.bytes(), document.index(), buffers, new CancellationToken());
    long lines = scan.classifiedLines();
    scan.lines().clear();
    return lines;
  }

  /**
   * Text-view literal search.
   *
   * @return match count
   * @throws Exception when the search fails
   */
  @Benchmark
  public long wholeLine() throws Exception {
    return run(engine, "\"" + SyntheticRecords.RARE + "\"", MatchMode.LITERAL, null);
  }

  /**
   * The literal in one verbatim column.
   *
   * @return match count
   * @throws Exception when the search fails
   */
  @Benchmark
  public long column() throws Exception {
    String name = schema.format() == ColumnFormat.CLEF ? "template" : "message";
    return run(engine, name + ":\"" + SyntheticRecords.RARE + "\"", MatchMode.LITERAL, columns);
  }

  /**
   * A regex with no required literal in the message column, every CPU.
   *
   * @return match count
   * @throws Exception when the search fails
   */
  @Benchmark
  public long columnEveryLine() throws Exception {
    return run(engine, "message:\"[f]lagword\"", MatchMode.REGEX, columns);
  }

  /**
   * As {@link #columnEveryLine} on one CPU.
   *
   * @return match count
   * @throws Exception when the search fails
   */
  @Benchmark
  public long columnEveryLineOneThread() throws Exception {
    return run(oneThread, "message:\"[f]lagword\"", MatchMode.REGEX, columns);
  }

  private long run(SearchEngine with, String typed, MatchMode mode, ColumnSearch search)
      throws ExpressionException, InterruptedException {
    MutableLineSet results = new MutableLineSet();
    final CountDownLatch done = new CountDownLatch(1);
    final OperationOutcome[] outcome = new OperationOutcome[1];
    SearchQuery query =
        new SearchQuery(ExpressionParser.parse(typed, mode, true), true, false, false, null)
            .withColumns(search);
    OperationHandle handle =
        with.search(
            document,
            query,
            results,
            new SearchListener() {
              @Override
              public void progressed(long searchedLines, long totalLines, long matchCount) {
                // Only the end matters.
              }

              @Override
              public void finished(
                  OperationOutcome result, SearchLimit limit, long skipped, Exception failure) {
                outcome[0] = result;
                done.countDown();
              }
            });
    if (!handle.await(TimeUnit.MINUTES.toMillis(10)) || outcome[0] != OperationOutcome.COMPLETED) {
      throw new IllegalStateException("Search did not complete: " + outcome[0]);
    }
    return results.size();
  }
}
