package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import io.github.ajdailey.qlogg.core.watch.FileMonitor;
import io.github.ajdailey.qlogg.core.watch.MonitorSettings;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** Documents over pasted text, and closing several documents at once. */
class InMemoryDocumentTest {
  @TempDir Path dir;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Workers workers;
  private MemoryBudget budget;
  private DocumentServices services;

  @BeforeEach
  void createServices() {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    budget = MemoryBudget.of(1L << 20);
    services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(2, budget)),
            new SearchEngine(
                workers, StandardMatchers.INSTANCE, SearchSettings.defaults(2, budget)),
            new BlockCache(65536, 1 << 20),
            budget,
            StandardCharsets.ISO_8859_1);
  }

  @AfterEach
  void closeServices() {
    assertTrue(workers.close(Workers.CLOSE_BOUND));
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private long pasted() {
    return budget.limit(MemoryShare.PASTED) - budget.available(MemoryShare.PASTED);
  }

  /** Opens a latch when indexing ends. */
  private static final class Finished implements DocumentListener {
    final CountDownLatch done = new CountDownLatch(1);

    @Override
    public void indexProgressed(IndexProgress progress) {}

    @Override
    public void indexFinished(OperationOutcome outcome, IOException failure) {
      done.countDown();
    }

    @Override
    public void fileChanged(FileChange change) {}

    @Override
    public void closed() {}
  }

  @Test
  void pastedTextIsIndexedAsUtf8AndChargedUntilClose() throws Exception {
    String text = "café\nsecond\n";
    LogDocument document = LogDocument.ofText("Clipboard 1", text, 1000, services);
    assertEquals(13, pasted());
    assertTrue(document.inMemory());
    assertNull(document.path());
    assertEquals("Clipboard 1", document.name());
    assertEquals(StandardCharsets.UTF_8, document.encoding().charset());
    FileMonitor monitor = new FileMonitor(workers, MonitorSettings.DEFAULTS);
    document.startWatching(monitor);
    assertEquals(0, monitor.watchedDirectories());
    Finished finished = new Finished();
    document.addListener(finished);
    document.startIndexing();
    assertTrue(finished.done.await(30, TimeUnit.SECONDS));
    LineBlock block = document.newReader().read(document.index(), 0, 5, 0, 80);
    assertEquals(2, block.count());
    assertEquals("café", block.text(0));
    assertTrue(document.close(Duration.ofSeconds(5)));
    assertEquals(0, pasted());
    final ByteBuffer buffer = ByteBuffer.allocate(4);
    assertThrows(
        ClosedChannelException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            document.bytes().read(0, buffer);
          }
        });
  }

  @Test
  void textOverTheCapOrTheShareIsRefused() {
    LogDocument.PastedTextException capped =
        assertThrows(
            LogDocument.PastedTextException.class,
            new Executable() {
              @Override
              public void execute() throws Throwable {
                LogDocument.ofText("big", "x".repeat(101), 100, services);
              }
            });
    assertEquals(101, capped.size());
    assertEquals(100, capped.limit());
    final String share = "y".repeat((int) budget.limit(MemoryShare.PASTED) + 1);
    assertThrows(
        LogDocument.PastedTextException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            LogDocument.ofText("share", share, Long.MAX_VALUE, services);
          }
        });
    assertEquals(0, pasted());
  }

  @Test
  void closeAllWaitsForEveryDocumentAtOnce() throws Exception {
    List<LogDocument> stuck = new CopyOnWriteArrayList<LogDocument>();
    for (int i = 0; i < 3; i++) {
      Path file = Files.writeString(dir.resolve("stuck" + i + ".log"), "a\n");
      LogDocument document = LogDocument.open(file, null, services);
      assertTrue(document.enter());
      stuck.add(document);
    }
    long start = System.nanoTime();
    List<LogDocument> late = LogDocument.closeAll(stuck, workers, Duration.ofMillis(800));
    long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertEquals(3, late.size());
    assertTrue(millis < 2000, "closed one after another: " + millis + " ms");
    for (LogDocument document : stuck) {
      document.exit();
    }
  }
}
