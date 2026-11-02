package io.github.ajdailey.qlogg.core.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.FileByteSource;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Indexes a file past 2^31 bytes. The file is sparse (holes read as zero bytes), so it takes little
 * disk space: 32 lines of 64 MiB, three short lines just past 2^31, then a partial last line.
 */
class LargeFileTest {
  private static final long MIB64 = 1L << 26;
  private static final long TWO_GIB = 1L << 31;

  @TempDir Path dir;

  @Test
  void indexesPastTwoGigabytes() throws Exception {
    Path file = dir.resolve("large.log");
    long size = writeSparse(file);
    assertTrue(size > TWO_GIB);
    final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
    Thread.UncaughtExceptionHandler handler =
        new Thread.UncaughtExceptionHandler() {
          @Override
          public void uncaughtException(Thread thread, Throwable error) {
            failures.add(error);
          }
        };
    try (Workers workers =
            new Workers(Math.max(2, Runtime.getRuntime().availableProcessors()), handler);
        FileByteSource source = FileByteSource.open(file)) {
      MemoryBudget budget = MemoryBudget.of(1L << 30);
      TextEncoding encoding = TextEncoding.of(StandardCharsets.UTF_8, 0);
      try (GrowableLineIndex index =
          new GrowableLineIndex(encoding, budget, source, new BlockCache(65536, 1 << 20))) {
        RecordingListener listener = new RecordingListener();
        OperationHandle handle =
            new Indexer(workers, IndexSettings.defaults(workers.cpuThreads(), budget))
                .index(source, index, index.resumeOffset(), listener);
        assertTrue(handle.await(Duration.ofMinutes(5).toMillis()));
        assertEquals(OperationOutcome.COMPLETED, listener.outcome);
        LineIndex snapshot = index.snapshot();
        assertEquals(36, snapshot.lineCount());
        assertEquals(size, snapshot.indexedBytes());
        assertFalse(snapshot.lastLineComplete());
        assertEquals(MIB64 - 1, snapshot.maxLineBytes());
        assertEquals(31 * MIB64, snapshot.lineStart(31));
        assertEquals(TWO_GIB, snapshot.lineStart(32));
        assertEquals(TWO_GIB + 6, snapshot.lineStart(33));
        assertEquals(TWO_GIB + 10, snapshot.lineEnd(33));
        assertEquals(33, snapshot.lineAt(TWO_GIB + 8));
        assertEquals(31, snapshot.lineAt(TWO_GIB - 1));
        assertEquals(35, snapshot.lineAt(size - 1));
        assertEquals(size, snapshot.lineEnd(35));
      }
    }
    assertTrue(failures.isEmpty(), failures.toString());
    Files.delete(file);
  }

  /** Returns the file size. */
  private static long writeSparse(Path file) throws IOException {
    try (FileChannel channel =
        FileChannel.open(
            file,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            StandardOpenOption.SPARSE)) {
      for (long line = 1; line <= 32; line++) {
        channel.write(ByteBuffer.wrap(new byte[] {'\n'}), line * MIB64 - 1);
      }
      channel.write(
          ByteBuffer.wrap("alpha\nbeta\ngamma\n".getBytes(StandardCharsets.US_ASCII)), TWO_GIB);
      long tail = TWO_GIB + MIB64 / 2;
      channel.write(ByteBuffer.wrap("tail".getBytes(StandardCharsets.US_ASCII)), tail);
      return tail + 4;
    }
  }
}
