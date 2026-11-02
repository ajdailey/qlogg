package io.github.ajdailey.qlogg.core.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GrowableLineIndexTest {
  private static final TextEncoding UTF8 = TextEncoding.of(StandardCharsets.UTF_8, 0);
  private static final int LINES = 1 << 20;

  /**
   * Full mode holds 13 bytes per 128-line block plus the block's 127 lengths packed at width w in
   * whole words, allocated in 1,024-block chunks and 8,192-word pages, plus directories and tail.
   */
  @ParameterizedTest
  @CsvSource({"10, 4", "100, 7", "200, 8", "1000, 10", "40000, 16"})
  void fullRetainedBytesMatchFormula(long lineBytes, int width) throws Exception {
    MemoryBudget budget = MemoryBudget.of(1L << 34);
    try (GrowableLineIndex index = new GrowableLineIndex(UTF8, budget, null, null)) {
      for (long line = 1; line <= LINES; line++) {
        index.addLineStart(line * lineBytes);
      }
      index.publish();
      long blocks = LINES / PackedStarts.BLOCK_LINES;
      long chunks = ceil(blocks, PackedStarts.CHUNK_BLOCKS);
      long words = blocks * PackedStarts.wordsFor(width);
      long pages = ceil(words, PackedStarts.PAGE_WORDS);
      long expected =
          PackedStarts.BLOCK_LINES * 8L
              + 8L * (3 * directory(chunks) + directory(pages))
              + chunks * PackedStarts.CHUNK_BLOCKS * PackedStarts.BLOCK_META_BYTES
              + pages * PackedStarts.PAGE_WORDS * 8L;
      assertEquals(expected, index.retainedBytes());
      assertEquals(expected, budget.reserved(MemoryShare.LINE_INDEX));
      double perLine = (double) index.retainedBytes() / LINES;
      double formula = (13 + 8.0 * PackedStarts.wordsFor(width)) / 128;
      assertEquals(formula, perLine, formula * 0.01, "bytes per line");
      assertFalse(index.snapshot().sparse());
    }
    assertEquals(0, budget.reserved(MemoryShare.LINE_INDEX));
  }

  /** Sparse mode holds a line and an offset per checkpoint, in 1,024-checkpoint chunks. */
  @ParameterizedTest
  @CsvSource({"100, 1024", "4096, 256", "40000, 27"})
  void sparseRetainedBytesMatchFormula(long lineBytes, long linesPerCheckpoint) throws Exception {
    MemoryBudget budget = MemoryBudget.of(150_000);
    try (GrowableLineIndex index = new GrowableLineIndex(UTF8, budget, null, null)) {
      long lines = 50_000;
      for (long line = 1; line <= lines; line++) {
        index.addLineStart(line * lineBytes);
      }
      index.publish();
      assertTrue(index.snapshot().sparse());
      long checkpoints = lines / linesPerCheckpoint + 1;
      long chunks = ceil(checkpoints, SparseStarts.CHUNK);
      long expected = 8L * 2 * directory(chunks) + chunks * SparseStarts.CHUNK * 16L;
      assertEquals(expected, index.retainedBytes());
      assertEquals(expected, budget.reserved(MemoryShare.LINE_INDEX));
    }
    assertEquals(0, budget.reserved(MemoryShare.LINE_INDEX));
  }

  @Test
  void randomStartsIncludingWideBlocks() throws Exception {
    Random random = new Random(42);
    List<Long> starts = new ArrayList<Long>();
    try (GrowableLineIndex index =
        new GrowableLineIndex(UTF8, MemoryBudget.of(1L << 30), null, null)) {
      long start = 0;
      starts.add(start);
      for (int i = 0; i < 20_000; i++) {
        long delta;
        int kind = random.nextInt(100);
        if (kind == 0) {
          delta = (1L << (33 + random.nextInt(10))) + random.nextInt(1000);
        } else if (kind < 5) {
          delta = 1 + random.nextInt(1 << 20);
        } else {
          delta = 1 + random.nextInt(200);
        }
        start += delta;
        starts.add(start);
        index.addLineStart(start);
      }
      index.advanceTo(start + 5);
      LineIndex snapshot = index.publish();
      assertEquals(starts.size(), snapshot.lineCount());
      for (int line = 0; line < starts.size(); line++) {
        long first = starts.get(line);
        assertEquals(first, snapshot.lineStart(line), "lineStart " + line);
        assertEquals(line, snapshot.lineAt(first), "lineAt start " + line);
        if (line + 1 < starts.size()) {
          long next = starts.get(line + 1);
          assertEquals(next - 1, snapshot.lineEnd(line), "lineEnd " + line);
          assertEquals(line, snapshot.lineAt(next - 1), "lineAt last byte " + line);
        }
      }
      assertEquals(start + 5, snapshot.lineEnd(starts.size() - 1));
    }
  }

  @Test
  void publishedSnapshotsDoNotChange() throws Exception {
    try (GrowableLineIndex index =
        new GrowableLineIndex(UTF8, MemoryBudget.of(1L << 30), null, null)) {
      for (long line = 1; line <= 300; line++) {
        index.addLineStart(line * 10);
      }
      LineIndex first = index.publish();
      for (long line = 301; line <= 1000; line++) {
        index.addLineStart(line * 10);
      }
      index.advanceTo(10_005);
      LineIndex second = index.publish();
      assertEquals(300, first.lineCount());
      assertTrue(first.lastLineComplete());
      assertEquals(2990, first.lineStart(299));
      assertEquals(299, first.lineAt(1_000_000));
      assertEquals(1001, second.lineCount());
      assertFalse(second.lastLineComplete());
      assertTrue(second.generation() > first.generation());
      assertEquals(10_000, index.resumeOffset());
    }
  }

  @Test
  void lookupsOutsideTheIndexAreRejected() throws Exception {
    try (GrowableLineIndex index =
        new GrowableLineIndex(UTF8, MemoryBudget.of(1L << 30), null, null)) {
      final LineIndex empty = index.snapshot();
      assertEquals(0, empty.lineCount());
      assertEquals(0, empty.lineAt(5));
      assertTrue(empty.lastLineComplete());
      assertThrows(
          IndexOutOfBoundsException.class,
          new Executable() {
            @Override
            public void execute() {
              empty.lineStart(0);
            }
          });
    }
  }

  @Test
  void byteOrderMarkPrecedesTheFirstLine() throws Exception {
    TextEncoding utf16 = TextEncoding.of(StandardCharsets.UTF_16LE, 2);
    try (GrowableLineIndex index =
        new GrowableLineIndex(utf16, MemoryBudget.of(1L << 30), null, null)) {
      index.addLineStart(8);
      index.advanceTo(12);
      LineIndex snapshot = index.publish();
      assertEquals(2, snapshot.lineCount());
      assertEquals(2, snapshot.lineStart(0));
      assertEquals(6, snapshot.lineEnd(0));
      assertEquals(0, snapshot.lineAt(0));
      assertEquals(1, snapshot.lineAt(9));
    }
  }

  private static long ceil(long value, long step) {
    return (value + step - 1) / step;
  }

  private static long directory(long entries) {
    long size = PackedStarts.DIRECTORY_START;
    while (size < entries) {
      size *= 2;
    }
    return size;
  }
}
