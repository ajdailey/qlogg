package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.bench.Harness;
import io.github.ajdailey.qlogg.bench.LogGenerator;
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
 * Repeated whole-file literal searches for {@link LogGenerator#COMMON_TOKEN} with the default
 * settings and every CPU, with {@code trim=true} (the shipped engine: the read-buffer pool is
 * trimmed to {@code ReadBuffers.MIN_BUFFERS} when each search ends, so the next one allocates its
 * direct buffers again) against {@code trim=false} (the pool keeps every buffer). The difference is
 * the trim's cost per search. In this package for the engine's package-private constructor. The
 * {@code ascii-short} file is written once to {@code -Dqlogg.bench.dir} and reused; the cache is
 * warm.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 10)
@Fork(value = 1, jvmArgsPrepend = Harness.LAUNCHER_HEAP)
public class SearchTrimProbe {
  /** File size in MiB. */
  @Param({"16", "256"})
  public int fileMib;

  /** Whether the engine trims its pool when a search ends. */
  @Param({"true", "false"})
  public boolean trim;

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
    Path file = Path.of(dir).resolve("trim-probe-" + fileMib + "m.log");
    if (!Files.exists(file)) {
      new LogGenerator(LogGenerator.Profile.ASCII_SHORT, 1).write(file, (long) fileMib << 20);
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
            SearchSettings.defaults(workers.cpuThreads(), budget),
            trim);
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
   * One search.
   *
   * @return match count
   * @throws InterruptedException when interrupted
   */
  @Benchmark
  public long search() throws InterruptedException {
    MutableLineSet results = new MutableLineSet();
    final CountDownLatch done = new CountDownLatch(1);
    final OperationOutcome[] outcome = new OperationOutcome[1];
    SearchQuery query =
        new SearchQuery(
            new SearchExpression.Term(LogGenerator.COMMON_TOKEN, MatchMode.LITERAL),
            true,
            false,
            false,
            null);
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
