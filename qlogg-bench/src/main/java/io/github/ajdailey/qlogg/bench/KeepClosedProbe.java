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
 * Full indexing and a whole-file regex search of a generated log, with the document holding its
 * file ({@code keepClosed=false}, {@code FileByteSource}) or opening it for each read ({@code
 * keepClosed=true}, {@code ReopeningByteSource}). {@code cache=cold} drops the file from the page
 * cache before each invocation with GNU {@code dd iflag=nocache count=0} (Linux only); {@code warm}
 * leaves it cached. The file is written once to {@code -Dqlogg.bench.dir} and reused.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(
    value = 1,
    jvmArgsPrepend = {Harness.LAUNCHER_HEAP, Harness.LAUNCHER_GC})
public class KeepClosedProbe {
  /** File size in MiB. */
  @Param({"256", "1024"})
  public int fileMib;

  /** True to read through {@code ReopeningByteSource}. */
  @Param({"false", "true"})
  public boolean keepClosed;

  /** {@code warm} or {@code cold} page cache. */
  @Param({"warm", "cold"})
  public String cache;

  private Path file;
  private Workers workers;
  private DocumentServices services;
  private LogDocument indexed;

  /**
   * Writes the file when it does not exist, then opens and indexes the document the search reads.
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
    file = Path.of(dir).resolve("keep-closed-probe-" + fileMib + "m.log");
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
    services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(workers.cpuThreads(), budget)),
            new SearchEngine(
                workers,
                StandardMatchers.INSTANCE,
                SearchSettings.defaults(workers.cpuThreads(), budget)),
            BlockCache.fromBudget(budget),
            budget,
            StandardCharsets.ISO_8859_1);
    indexed = openIndexed();
  }

  /**
   * Drops the file from the page cache for {@code cache=cold}.
   *
   * @throws IOException when {@code dd} cannot run or fails
   * @throws InterruptedException when interrupted while waiting for it
   */
  @Setup(Level.Invocation)
  public void evict() throws IOException, InterruptedException {
    if (!"cold".equals(cache)) {
      return;
    }
    Process dd =
        new ProcessBuilder("dd", "if=" + file, "iflag=nocache", "count=0")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    if (dd.waitFor() != 0) {
      throw new IOException("dd iflag=nocache failed");
    }
  }

  /** Closes the document and the workers. */
  @TearDown(Level.Trial)
  public void tearDown() {
    indexed.close();
    workers.close();
  }

  /**
   * Opens and fully indexes the file, then closes it.
   *
   * @return line count
   * @throws IOException when the open or indexing fails
   * @throws InterruptedException when interrupted
   */
  @Benchmark
  public long index() throws IOException, InterruptedException {
    LogDocument document = openIndexed();
    try {
      return document.index().lineCount();
    } finally {
      document.close();
    }
  }

  /**
   * Regex on decoded text over the indexed document.
   *
   * @return match count
   * @throws InterruptedException when interrupted
   */
  @Benchmark
  public long regex() throws InterruptedException {
    MutableLineSet results = new MutableLineSet();
    final OperationOutcome[] outcome = new OperationOutcome[1];
    SearchQuery query =
        new SearchQuery(
            new SearchExpression.Term("worker-1[0-5]\\] #\\d+7", MatchMode.REGEX),
            true,
            false,
            false,
            null);
    OperationHandle handle =
        services
            .search()
            .search(
                indexed,
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
                  }
                });
    if (!handle.await(TimeUnit.MINUTES.toMillis(10)) || outcome[0] != OperationOutcome.COMPLETED) {
      throw new IllegalStateException("Search did not complete: " + outcome[0]);
    }
    return results.size();
  }

  private LogDocument openIndexed() throws IOException, InterruptedException {
    LogDocument document = LogDocument.open(file, null, services, true, keepClosed);
    final CountDownLatch done = new CountDownLatch(1);
    final IOException[] failed = new IOException[1];
    document.addListener(
        new DocumentListener() {
          @Override
          public void indexProgressed(IndexProgress progress) {
            // Only the end matters.
          }

          @Override
          public void indexFinished(OperationOutcome outcome, IOException failure) {
            failed[0] = failure;
            done.countDown();
          }

          @Override
          public void fileChanged(FileChange change) {}

          @Override
          public void closed() {
            // Closed by the caller.
          }
        });
    document.startIndexing();
    if (!done.await(10, TimeUnit.MINUTES) || failed[0] != null) {
      document.close();
      throw new IOException("Indexing did not finish", failed[0]);
    }
    return document;
  }
}
