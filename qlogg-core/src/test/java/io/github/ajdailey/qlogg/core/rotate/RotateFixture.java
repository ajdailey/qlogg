package io.github.ajdailey.qlogg.core.rotate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.GrowableLineIndex;
import io.github.ajdailey.qlogg.core.index.IndexListener;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.search.LineSource;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Workers, an indexer with 64-byte blocks and a small block cache for rotated-set tests. */
final class RotateFixture implements AutoCloseable {
  static final long WAIT_MILLIS = 60_000;
  static final MemoryBudget LARGE = MemoryBudget.of(1L << 30);

  /**
   * Returns a budget that refuses a full index's first sealed block, so an index past 128 lines
   * converts to sparse; one per index.
   */
  static MemoryBudget sparse() {
    return MemoryBudget.of(150_000);
  }

  final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  final Workers workers;
  final Indexer indexer;
  final BlockCache cache = new BlockCache(64, 1 << 16);

  RotateFixture() {
    workers =
        new Workers(
            3,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    indexer = new Indexer(workers, new IndexSettings(64, 4, LARGE));
  }

  /** Indexes a source to its current size; the caller closes the index. */
  GrowableLineIndex index(ByteSource source, TextEncoding encoding, MemoryBudget budget)
      throws Exception {
    GrowableLineIndex index = new GrowableLineIndex(encoding, budget, source, cache);
    extend(source, index);
    return index;
  }

  /** Indexes from the resume offset to the source's current size. */
  void extend(ByteSource source, GrowableLineIndex index) throws Exception {
    final OperationOutcome[] outcome = new OperationOutcome[1];
    OperationHandle handle =
        indexer.index(
            source,
            index,
            index.resumeOffset(),
            new IndexListener() {
              @Override
              public void progressed(IndexProgress progress) {
                // Only the outcome matters.
              }

              @Override
              public void finished(OperationOutcome result, IOException failure) {
                outcome[0] = result;
              }
            });
    assertTrue(handle.await(WAIT_MILLIS));
    assertEquals(OperationOutcome.COMPLETED, outcome[0]);
  }

  static LineSource lines(
      final LineIndex index, final ByteSource bytes, final TextEncoding encoding) {
    return new LineSource() {
      @Override
      public LineIndex index() {
        return index;
      }

      @Override
      public ByteSource bytes() {
        return bytes;
      }

      @Override
      public TextEncoding encoding() {
        return encoding;
      }
    };
  }

  @Override
  public void close() {
    assertTrue(workers.close(Duration.ofSeconds(10)));
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }
}
