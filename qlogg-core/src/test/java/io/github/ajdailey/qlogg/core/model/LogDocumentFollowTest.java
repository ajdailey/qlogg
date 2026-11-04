package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.FileByteSource;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import io.github.ajdailey.qlogg.core.watch.FileMonitor;
import io.github.ajdailey.qlogg.core.watch.MonitorSettings;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Growth, truncation, rotation and deletion of a watched file, and the close order. The writer
 * renames and deletes the file while the document holds it open, which Windows allows because the
 * file is opened with delete sharing and read by position.
 */
class LogDocumentFollowTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path dir;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Workers workers;
  private MemoryBudget budget;
  private DocumentServices services;
  private FileMonitor monitor;

  /** Records file changes and index outcomes. */
  private static final class Events implements DocumentListener {
    final List<FileChange> changes = new CopyOnWriteArrayList<FileChange>();
    final CountDownLatch firstIndex = new CountDownLatch(1);

    @Override
    public void indexProgressed(IndexProgress progress) {}

    @Override
    public void indexFinished(OperationOutcome outcome, IOException failure) {
      firstIndex.countDown();
    }

    @Override
    public void fileChanged(FileChange change) {
      changes.add(change);
    }

    @Override
    public void closed() {}
  }

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
    budget = MemoryBudget.of(1L << 28);
    services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(2, budget)),
            new SearchEngine(
                workers, StandardMatchers.INSTANCE, SearchSettings.defaults(2, budget)),
            new BlockCache(65536, 1 << 20),
            budget,
            StandardCharsets.ISO_8859_1);
    monitor = new FileMonitor(workers, new MonitorSettings(20, true));
  }

  @AfterEach
  void closeServices() {
    assertTrue(workers.close(Workers.CLOSE_BOUND));
    assertEquals(0, workers.liveThreads());
    assertEquals(0, budget.reserved(MemoryShare.LINE_INDEX));
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private LogDocument watched(Path file, Events events) throws Exception {
    LogDocument document = LogDocument.open(file, null, services);
    document.addListener(events);
    document.startIndexing();
    assertTrue(events.firstIndex.await(BOUND.toMillis(), TimeUnit.MILLISECONDS));
    document.startWatching(monitor);
    return document;
  }

  private static BooleanSupplier lines(final LogDocument document, final long count) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return document.index().lineCount() == count && !document.status().indexing();
      }
    };
  }

  /** REPLACED; without a file key also TRUNCATED. */
  private static BooleanSupplier reopens(final Events events, final boolean keyless) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return events.changes.contains(FileChange.REPLACED)
            || (keyless && events.changes.contains(FileChange.TRUNCATED));
      }
    };
  }

  private static BooleanSupplier reported(final Events events, final FileChange change) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return events.changes.contains(change);
      }
    };
  }

  /**
   * Regression: a check between an indexing pass's last snapshot and its new tail record compared
   * the grown end of the file with the previous pass's tail digest and reported MODIFIED, which
   * stopped following. Fast appends with a 20 ms poll hit that window.
   */
  @Test
  void fastGrowthIsOnlyAppended() throws Exception {
    Path file = dir.resolve("fast.log");
    Files.writeString(file, "line 0\n", StandardCharsets.US_ASCII);
    Events events = new Events();
    LogDocument document = watched(file, events);
    try {
      long lines = 1;
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      StringBuilder batch = new StringBuilder();
      while (System.nanoTime() < end) {
        batch.setLength(0);
        for (int i = 0; i < 5000; i++) {
          batch.append("line ").append(lines++).append('\n');
        }
        Files.writeString(file, batch, StandardCharsets.US_ASCII, StandardOpenOption.APPEND);
        Thread.sleep(20);
      }
      assertTrue(Poll.until(lines(document, lines), BOUND), events.changes.toString());
      for (FileChange change : events.changes) {
        assertTrue(
            change == FileChange.APPENDED || change == FileChange.UNCHANGED,
            events.changes.toString());
      }
    } finally {
      document.close();
    }
  }

  @Test
  void appendedLinesAreIndexedIncludingAGrowingLastLine() throws Exception {
    Path file = Files.writeString(dir.resolve("grow.log"), "one\ntw");
    Events events = new Events();
    try (final LogDocument document = watched(file, events)) {
      assertEquals(2, document.index().lineCount());
      Files.writeString(file, "o\nthree\nfour\n", StandardOpenOption.APPEND);
      assertTrue(Poll.until(lines(document, 4), BOUND));
      assertEquals("two", document.newReader().read(document.index(), 1, 1, 0, 80).text(1));
      final long size = Files.size(file);
      assertTrue(
          Poll.until(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return document.status().fileBytes() == size;
                }
              },
              BOUND));
      assertTrue(events.changes.contains(FileChange.APPENDED));
    }
  }

  /**
   * A check before indexing's first progress event saw 0 bytes covered and reported the whole file
   * as appended, which started a second indexing pass right after the first.
   */
  @Test
  void aCheckBeforeTheFirstProgressIsNotAnAppend() throws Exception {
    Path file = Files.writeString(dir.resolve("early.log"), "one\ntwo\n");
    final CountDownLatch release = new CountDownLatch(1);
    Runnable blocker =
        new Runnable() {
          @Override
          public void run() {
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        };
    Events events = new Events();
    try (LogDocument document = LogDocument.open(file, null, services)) {
      try {
        for (int i = 0; i < workers.cpuThreads(); i++) {
          workers.executeCpu(blocker);
        }
        document.addListener(events);
        document.startIndexing();
        document.startWatching(monitor);
        // A new modification time makes the check report what it classified.
        Files.setLastModifiedTime(
            file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() - 3_600_000L));
        assertTrue(Poll.until(changeReported(events), BOUND));
        assertEquals(FileChange.UNCHANGED, events.changes.get(0));
      } finally {
        release.countDown();
      }
      assertTrue(Poll.until(lines(document, 2), BOUND));
    }
  }

  private static BooleanSupplier changeReported(final Events events) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return !events.changes.isEmpty();
      }
    };
  }

  @Test
  void truncationIsReportedOnce() throws Exception {
    Path file = Files.writeString(dir.resolve("cut.log"), "one\ntwo\nthree\n");
    Events events = new Events();
    try (LogDocument document = watched(file, events)) {
      Files.writeString(file, "x\n", StandardOpenOption.TRUNCATE_EXISTING);
      assertTrue(Poll.until(reported(events, FileChange.TRUNCATED), BOUND));
      Files.writeString(file, "y\n", StandardOpenOption.APPEND);
      assertTrue(document.close(LogDocument.CLOSE_BOUND));
      assertEquals(List.of(FileChange.TRUNCATED), events.changes);
    }
  }

  /**
   * Regression: a copy-truncate rotation regrown past the indexed bytes before the next check was
   * reported APPENDED and indexed the new bytes after the old ones. A check that lands between the
   * truncation and the write reports TRUNCATED, which reopens the same way.
   */
  @Test
  void truncateAndRegrowBetweenChecksIsReplaced() throws Exception {
    Path file = Files.writeString(dir.resolve("copytruncate.log"), "");
    Events events = new Events();
    try (LogDocument document = watched(file, events)) {
      Files.writeString(file, "old one\nold two\n", StandardOpenOption.APPEND);
      assertTrue(Poll.until(lines(document, 2), BOUND));
      assertTrue(Poll.until(reported(events, FileChange.APPENDED), BOUND));
      int before = events.changes.size();
      try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
        channel.truncate(0);
        channel.write(
            ByteBuffer.wrap(
                "new content after rotation\n".repeat(4).getBytes(StandardCharsets.US_ASCII)),
            0);
      }
      assertTrue(Poll.until(reopens(events, true), BOUND), events.changes.toString());
      List<FileChange> after = events.changes.subList(before, events.changes.size());
      assertFalse(after.contains(FileChange.APPENDED), events.changes.toString());
    }
  }

  @Test
  void renameRotationReopensByName() throws Exception {
    Path file = Files.writeString(dir.resolve("app.log"), "first\nsecond\n");
    Events events = new Events();
    try (LogDocument document = watched(file, events)) {
      Files.move(file, dir.resolve("app.log.1"));
      Files.writeString(file, "rotated after the move\n");
      // Without a file key (Windows) the new file keeps the old creation time (NTFS tunneling), so
      // a check that sees it before the write lands reports TRUNCATED, which reopens the same way.
      boolean keyless = document.bytes().identity().fileKey() == null;
      assertTrue(Poll.until(reopens(events, keyless), BOUND), events.changes.toString());
      assertEquals("first", document.newReader().read(document.index(), 0, 1, 0, 80).text(0));
    }
  }

  @Test
  void deleteThenRecreateIsMissingThenReplaced() throws Exception {
    Path file = Files.writeString(dir.resolve("gone.log"), "kept\n");
    Events events = new Events();
    try (LogDocument document = watched(file, events)) {
      Files.delete(file);
      assertTrue(Poll.until(reported(events, FileChange.MISSING), BOUND));
      assertTrue(document.status().missing());
      assertEquals("kept", document.newReader().read(document.index(), 0, 1, 0, 80).text(0));
      Files.writeString(file, "again\n");
      assertTrue(Poll.until(reported(events, FileChange.REPLACED), BOUND));
      assertFalse(document.status().missing());
    }
  }

  @Test
  void stoppedLoadingLeavesGrowthUnindexed() throws Exception {
    Path file = Files.writeString(dir.resolve("stop.log"), "one\n");
    Events events = new Events();
    try (LogDocument document = watched(file, events)) {
      assertFalse(document.stopIndexing());
      Files.writeString(file, "two\n", StandardOpenOption.APPEND);
      assertTrue(Poll.until(reported(events, FileChange.APPENDED), BOUND));
      assertEquals(1, document.index().lineCount());
      assertTrue(document.status().loadingStopped());
    }
  }

  /**
   * The file stays open until an admitted operation exits; close does not rely on a closed read.
   */
  @Test
  void closeWaitsForAdmittedOperationsBeforeClosingTheFile() throws Exception {
    Path file = Files.writeString(dir.resolve("order.log"), "a\nb\n");
    Events events = new Events();
    final LogDocument document = watched(file, events);
    FileByteSource source = (FileByteSource) document.bytes();
    assertTrue(document.enter());
    final CountDownLatch closed = new CountDownLatch(1);
    workers.start(
        Workers.Kind.LIFECYCLE,
        new Runnable() {
          @Override
          public void run() {
            if (document.close(LogDocument.CLOSE_BOUND)) {
              closed.countDown();
            }
          }
        });
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                if (document.enter()) {
                  document.exit();
                  return false;
                }
                return true;
              }
            },
            BOUND));
    assertTrue(source.isOpen());
    assertEquals("b", document.newReader().read(document.index(), 1, 1, 0, 80).text(1));
    document.exit();
    assertTrue(closed.await(BOUND.toMillis(), TimeUnit.MILLISECONDS));
    assertFalse(source.isOpen());
  }
}
