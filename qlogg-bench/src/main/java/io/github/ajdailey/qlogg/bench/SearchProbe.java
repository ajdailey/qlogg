package io.github.ajdailey.qlogg.bench;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentListener;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchExpression;
import io.github.ajdailey.qlogg.core.search.SearchLimit;
import io.github.ajdailey.qlogg.core.search.SearchListener;
import io.github.ajdailey.qlogg.core.search.SearchQuery;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.testkit.SyntheticLog;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * A whole-file search of a generated, indexed log through {@link SearchEngine} with the default
 * settings and every CPU: a case-sensitive literal (byte search), an ASCII literal with case folded
 * (byte search), and a regex (decoded text). The file is written once to {@code -Dqlogg.bench.dir}
 * and reused; the cache is warm.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(1)
public class SearchProbe {
  /** File size in MiB. */
  @Param({"1024"})
  public int fileMib;

  private Workers workers;
  private SearchEngine engine;
  private LogDocument document;

  /**
   * Writes the file when it does not exist, then opens and indexes it.
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
    Path file = Path.of(dir).resolve("search-probe-" + fileMib + "m.log");
    if (!Files.exists(file)) {
      SyntheticLog.write(file, (long) fileMib << 20, 2, "\n");
    }
    workers =
        new Workers(
            Runtime.getRuntime().availableProcessors(),
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                error.printStackTrace();
              }
            });
    MemoryBudget budget = MemoryBudget.fromRuntime();
    engine =
        new SearchEngine(
            workers,
            StandardMatchers.INSTANCE,
            SearchSettings.defaults(workers.cpuThreads(), budget));
    DocumentServices services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(workers.cpuThreads(), budget)),
            engine,
            new BlockCache(65536, 1 << 20),
            budget,
            StandardCharsets.ISO_8859_1);
    document = LogDocument.open(file, null, services);
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
  }

  /** Closes the document and the workers. */
  @TearDown(Level.Trial)
  public void tearDown() {
    document.close();
    workers.close();
  }

  /**
   * Case-sensitive literal.
   *
   * @return match count
   * @throws InterruptedException when interrupted
   */
  @Benchmark
  public long literal() throws InterruptedException {
    return run("flagword", MatchMode.LITERAL, true);
  }

  /**
   * ASCII literal, case folded.
   *
   * @return match count
   * @throws InterruptedException when interrupted
   */
  @Benchmark
  public long literalIgnoreCase() throws InterruptedException {
    return run("blockterm", MatchMode.LITERAL, false);
  }

  /**
   * Regex on decoded text.
   *
   * @return match count
   * @throws InterruptedException when interrupted
   */
  @Benchmark
  public long regex() throws InterruptedException {
    return run("worker-1[0-5]\\] #\\d+7", MatchMode.REGEX, true);
  }

  private long run(String pattern, MatchMode mode, boolean caseSensitive)
      throws InterruptedException {
    MutableLineSet results = new MutableLineSet();
    final CountDownLatch done = new CountDownLatch(1);
    final OperationOutcome[] outcome = new OperationOutcome[1];
    SearchQuery query =
        new SearchQuery(
            new SearchExpression.Term(pattern, mode), caseSensitive, false, false, null);
    OperationHandle handle =
        engine.search(
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
