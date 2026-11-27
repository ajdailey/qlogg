package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.ReadBuffers;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CancellationException;

/**
 * Reads one search's chunks from its source by positional reads: a span of whole lines in one read,
 * a restricted chunk's runs end to end, or a long line slice by slice through a pooled buffer. A
 * read that ends before the requested bytes throws {@link IOException}.
 */
final class ChunkReader {
  private final ByteSource source;
  private final SearchedLines tested;
  private final ReadBuffers buffers;
  private final CancellationToken token;

  ChunkReader(
      ByteSource source, SearchedLines tested, ReadBuffers buffers, CancellationToken token) {
    this.source = source;
    this.tested = tested;
    this.buffers = buffers;
    this.token = token;
  }

  /**
   * Reads lines {@code firstLine} to {@code stop - 1}, from byte {@code start} to {@code finish},
   * in one read into the cleared buffer and flips it.
   *
   * @return the span as one run
   */
  ChunkRuns readSpan(ByteBuffer buffer, long firstLine, long stop, long start, long finish)
      throws IOException {
    buffer.clear();
    fill(buffer, start, (int) (finish - start));
    buffer.flip();
    ChunkRuns runs = new ChunkRuns();
    runs.add(firstLine, stop - 1, start, finish);
    return runs;
  }

  /**
   * Splits a restricted chunk into runs and reads them end to end into its buffer, checking the
   * token before each run, and sets the chunk's record marks. Runs on a {@code qlogg-search-}
   * virtual thread, so several chunks' reads are in flight at once.
   *
   * @return true with the chunk's runs set; false with its failure set: {@code
   *     UncheckedIOException} for a read error, {@code CancellationException} for a cancel
   */
  boolean readRuns(Chunk chunk) {
    try {
      long stop = chunk.firstLine + chunk.lines;
      ChunkRuns runs = tested.runs(chunk.firstLine, stop);
      chunk.marks = tested.marks(chunk.firstLine, stop);
      ByteBuffer buffer = chunk.buffer;
      buffer.clear();
      for (int run = 0; run < runs.count(); run++) {
        token.check();
        fill(buffer, runs.start(run), runs.length(run));
      }
      buffer.flip();
      chunk.runs = runs;
      return true;
    } catch (IOException e) {
      chunk.fail(new UncheckedIOException(e));
    } catch (CancellationException e) {
      chunk.fail(e);
    }
    return false;
  }

  /** Reads a long line into the heap buffer slice by slice and flips it. */
  void readLong(ByteBuffer heap, long position) throws IOException, InterruptedException {
    ByteBuffer slice = buffers.acquire(token);
    try {
      while (heap.hasRemaining()) {
        token.check();
        int length = Math.min(slice.capacity(), heap.remaining());
        slice.clear();
        fill(slice, position + heap.position(), length);
        heap.put(slice.flip());
      }
      heap.flip();
    } finally {
      buffers.release(slice);
    }
  }

  /** Reads {@code length} bytes at {@code position} into the buffer at its position. */
  private void fill(ByteBuffer buffer, long position, int length) throws IOException {
    int from = buffer.position();
    buffer.limit(from + length);
    while (buffer.hasRemaining()) {
      if (source.read(position + buffer.position() - from, buffer) < 0) {
        throw new IOException("File is shorter than its line index");
      }
    }
  }
}
