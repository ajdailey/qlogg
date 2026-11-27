package io.github.ajdailey.qlogg.core.search;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;

/**
 * One chunk of a search: its lines, runs and bytes and, once matched, its matching lines. A
 * restricted chunk of more than one line has no runs until {@link ChunkReader#readRuns} sets them.
 */
final class Chunk {
  final long firstLine;
  final long lines;
  final ByteBuffer buffer;
  final boolean pooled;
  final long reservedBytes;
  final CountDownLatch done = new CountDownLatch(1);
  ChunkRuns runs;
  volatile Matched matched;

  Chunk(
      long firstLine,
      long stop,
      ChunkRuns runs,
      ByteBuffer buffer,
      boolean pooled,
      long reservedBytes) {
    this.firstLine = firstLine;
    this.lines = stop - firstLine;
    this.runs = runs;
    this.buffer = buffer;
    this.pooled = pooled;
    this.reservedBytes = reservedBytes;
  }

  /** Sets the chunk's result to a failure. */
  void fail(RuntimeException failure) {
    matched = new Matched(null, 0, null, 0, failure);
  }

  /**
   * A worker's result: the matching lines in ascending order and, for a range search, the lines
   * matching the end, or the exception it ended with.
   */
  record Matched(long[] hits, int count, long[] ends, int endCount, RuntimeException failure) {}
}
