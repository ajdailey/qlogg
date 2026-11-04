package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Workers, services and helpers for search tests over real files. */
final class SearchFixture implements AutoCloseable {
  static final Duration BOUND = Duration.ofSeconds(60);

  final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  final Workers workers;
  final MemoryBudget budget;
  final DocumentServices services;

  SearchFixture(MemoryBudget budget) {
    this.budget = budget;
    this.workers =
        new Workers(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    this.services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(workers.cpuThreads(), budget)),
            engine(SearchSettings.defaults(workers.cpuThreads(), budget)),
            new BlockCache(65536, 1 << 20),
            budget,
            StandardCharsets.ISO_8859_1);
  }

  SearchFixture() {
    this(MemoryBudget.of(1L << 30));
  }

  SearchEngine engine(SearchSettings settings) {
    return new SearchEngine(workers, StandardMatchers.INSTANCE, settings);
  }

  /** Opens and fully indexes a file. */
  LogDocument open(Path file, Charset forced) throws IOException, InterruptedException {
    LogDocument document = LogDocument.open(file, forced, services);
    final CountDownLatch indexed = new CountDownLatch(1);
    final OperationOutcome[] outcome = new OperationOutcome[1];
    document.addListener(
        new DocumentListener() {
          @Override
          public void indexProgressed(IndexProgress progress) {
            // Only the end matters here.
          }

          @Override
          public void indexFinished(OperationOutcome result, IOException failure) {
            outcome[0] = result;
            indexed.countDown();
          }

          @Override
          public void fileChanged(FileChange change) {}

          @Override
          public void closed() {
            // Closed by the test.
          }
        });
    document.startIndexing();
    assertTrue(indexed.await(BOUND.toMillis(), TimeUnit.MILLISECONDS));
    assertEquals(OperationOutcome.COMPLETED, outcome[0]);
    return document;
  }

  /** Runs a search to its end. */
  Recording search(SearchEngine engine, LineSource source, SearchQuery query, MutableLineSet set)
      throws InterruptedException {
    Recording recording = new Recording();
    OperationHandle handle = engine.search(source, query, set, recording);
    assertTrue(handle.await(BOUND.toMillis()));
    assertTrue(handle.isDone());
    return recording;
  }

  Recording search(SearchEngine engine, LineSource source, SearchQuery query)
      throws InterruptedException {
    return search(engine, source, query, new MutableLineSet());
  }

  @Override
  public void close() {
    assertTrue(workers.close(Workers.CLOSE_BOUND));
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  /** Records a search's events. */
  static final class Recording implements SearchListener {
    final CountDownLatch finished = new CountDownLatch(1);
    volatile OperationOutcome outcome;
    volatile SearchLimit limit;
    volatile Exception failure;
    volatile long searched;
    volatile long total;
    volatile long matches;
    volatile long skipped;
    volatile int progressCalls;

    @Override
    public void progressed(long searchedLines, long totalLines, long matchCount) {
      searched = searchedLines;
      total = totalLines;
      matches = matchCount;
      progressCalls++;
    }

    @Override
    public void finished(
        OperationOutcome result, SearchLimit stop, long skippedLines, Exception cause) {
      outcome = result;
      skipped = skippedLines;
      limit = stop;
      failure = cause;
      finished.countDown();
    }
  }
}
