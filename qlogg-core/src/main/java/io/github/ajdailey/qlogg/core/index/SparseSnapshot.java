package io.github.ajdailey.qlogg.core.index;

import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Snapshot over checkpoints. A lookup starts at the nearest checkpoint before the target and reads
 * forward through the block cache counting LF units: at most {@link SparseStarts#CHECKPOINT_LINES}
 * lines, and at most {@link SparseStarts#CHECKPOINT_BYTES} bytes before the target line starts.
 * {@link #startOf} starts instead from the last line start it found when that lies between the
 * checkpoint and the target, so consecutive lookups (a screen of lines, a search chunk's bounds)
 * read each line once rather than from the checkpoint again. {@link #lineContaining} reads no
 * further than the unit at the target offset, so an offset inside a long line costs its distance
 * from the checkpoint, not the rest of the line.
 */
final class SparseSnapshot extends IndexSnapshot {
  private static final int SCAN_BYTES = 8192;

  /** First read of a scan; a scan from a remembered line usually ends within one line. */
  private static final int FIRST_SCAN_BYTES = 256;

  private final SparseStarts.View view;
  private final ByteSource source;
  private final BlockCache cache;
  private final LineScanner scanner;

  /** The last {line, start} {@link #startOf} found; the array is never modified. */
  private final AtomicReference<long[]> recent = new AtomicReference<long[]>();

  SparseSnapshot(
      SparseStarts.View view,
      long startCount,
      long firstStart,
      long lastStart,
      long indexedBytes,
      long maxLineBytes,
      long generation,
      ByteSource source,
      BlockCache cache,
      LineScanner scanner) {
    super(
        firstStart, startCount, lastStart, indexedBytes, maxLineBytes, generation, scanner.width());
    this.view = view;
    this.source = source;
    this.cache = cache;
    this.scanner = scanner;
  }

  @Override
  long startOf(long line) {
    int checkpoint = view.byLine(line);
    long fromLine = view.line(checkpoint);
    long fromStart = view.offset(checkpoint);
    long[] last = recent.get();
    if (last != null && last[0] > fromLine && last[0] <= line) {
      fromLine = last[0];
      fromStart = last[1];
    }
    long[] found = advance(fromLine, fromStart, line, Long.MAX_VALUE);
    recent.set(found);
    return found[1];
  }

  @Override
  long lineContaining(long offset) {
    int checkpoint = view.byOffset(offset);
    return advance(view.line(checkpoint), view.offset(checkpoint), Long.MAX_VALUE, offset)[0];
  }

  @Override
  public boolean sparse() {
    return true;
  }

  /**
   * Reads forward from a known line start, stopping at the target line or at the last line that
   * starts at or before the target offset.
   *
   * @return the line reached and its start
   */
  private long[] advance(long line, long start, long targetLine, long targetOffset) {
    ByteBuffer buffer = ByteBuffer.allocate(FIRST_SCAN_BYTES);
    long position = start;
    long current = line;
    long currentStart = start;
    // An LF unit starting past the target offset ends a line that contains it, so the scan stops
    // after the unit that starts at or before the target.
    long end = lastStart;
    if (targetOffset < lastStart) {
      end = Math.min(lastStart, start + ((targetOffset - start) / unit + 1) * unit);
    }
    try {
      while (current < targetLine && position < end) {
        int want = (int) Math.min(buffer.capacity(), end - position);
        buffer.clear();
        int got = cache.read(source, position, want, buffer);
        if (got < want) {
          throw new EOFException("File is shorter than its index");
        }
        for (int at = 0; at < got; at += unit) {
          if (scanner.isLineFeed(buffer, at)) {
            long next = position + at + unit;
            if (next > targetOffset) {
              return new long[] {current, currentStart};
            }
            current++;
            currentStart = next;
            if (current == targetLine) {
              return new long[] {current, currentStart};
            }
          }
        }
        position += got;
        if (buffer.capacity() < SCAN_BYTES) {
          buffer = ByteBuffer.allocate(SCAN_BYTES);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new long[] {current, currentStart};
  }
}
