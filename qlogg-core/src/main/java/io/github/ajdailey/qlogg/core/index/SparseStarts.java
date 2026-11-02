package io.github.ajdailey.qlogg.core.index;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import java.util.Arrays;

/**
 * Sparse-mode line starts: a checkpoint (line number and start offset) at the first line, then at
 * the first line start that is {@link #CHECKPOINT_LINES} lines or {@link #CHECKPOINT_BYTES} bytes
 * past the previous checkpoint, whichever comes first. Checkpoints are stored in chunks of {@link
 * #CHUNK} pairs, each reserved from {@link MemoryShare#LINE_INDEX}; the chunk directories double
 * from {@link PackedStarts#DIRECTORY_START}. Single writer; a {@link View} stays valid while the
 * writer appends.
 */
final class SparseStarts {
  static final int CHECKPOINT_LINES = 1024;
  static final long CHECKPOINT_BYTES = 1L << 20;
  static final int CHUNK = 1024;

  private final MemoryBudget budget;
  private long[][] lines = new long[PackedStarts.DIRECTORY_START][];
  private long[][] offsets = new long[PackedStarts.DIRECTORY_START][];
  private int chunks;
  private int count;
  private long lastLine;
  private long lastOffset;
  private long retained;

  private SparseStarts(MemoryBudget budget, long retained) {
    this.budget = budget;
    this.retained = retained;
  }

  /**
   * Creates storage holding the checkpoint for line 0.
   *
   * @throws IndexCapacityException when the share refuses the first chunk
   */
  static SparseStarts create(MemoryBudget budget, long firstStart) throws IndexCapacityException {
    long fixed = 2L * PackedStarts.DIRECTORY_START * 8;
    if (!budget.reserve(MemoryShare.LINE_INDEX, fixed)) {
      throw new IndexCapacityException(fixed, budget.available(MemoryShare.LINE_INDEX));
    }
    SparseStarts starts = new SparseStarts(budget, fixed);
    try {
      starts.append(0, firstStart);
    } catch (IndexCapacityException e) {
      starts.release();
      throw e;
    }
    return starts;
  }

  /** Bytes held by every array, all reserved. */
  long retainedBytes() {
    return retained;
  }

  /** Returns the reservation; the storage is not used afterwards. */
  void release() {
    budget.release(MemoryShare.LINE_INDEX, retained);
    retained = 0;
  }

  /**
   * Records a line start, keeping it when it is due as a checkpoint.
   *
   * @throws IndexCapacityException when a new chunk is refused
   */
  void add(long line, long start) throws IndexCapacityException {
    if (line - lastLine >= CHECKPOINT_LINES || start - lastOffset >= CHECKPOINT_BYTES) {
      append(line, start);
    }
  }

  private void append(long line, long start) throws IndexCapacityException {
    if (count == chunks * CHUNK) {
      long cost = 2L * CHUNK * 8;
      if (chunks == lines.length) {
        cost += 2L * lines.length * 8;
      }
      if (!budget.reserve(MemoryShare.LINE_INDEX, cost)) {
        throw new IndexCapacityException(cost, budget.available(MemoryShare.LINE_INDEX));
      }
      retained += cost;
      if (chunks == lines.length) {
        lines = Arrays.copyOf(lines, chunks * 2);
        offsets = Arrays.copyOf(offsets, chunks * 2);
      }
      lines[chunks] = new long[CHUNK];
      offsets[chunks] = new long[CHUNK];
      chunks++;
    }
    lines[count / CHUNK][count % CHUNK] = line;
    offsets[count / CHUNK][count % CHUNK] = start;
    count++;
    lastLine = line;
    lastOffset = start;
  }

  /** Captures the current checkpoints; the writer must not run concurrently. */
  View view() {
    return new View(lines, offsets, count);
  }

  /**
   * Immutable view of the checkpoints.
   *
   * @param lines chunk directory of checkpoint line numbers
   * @param offsets chunk directory of checkpoint start offsets
   * @param count checkpoints
   */
  record View(long[][] lines, long[][] offsets, int count) {
    /** Returns the index of the last checkpoint at or before a line. */
    int byLine(long line) {
      return search(lines, line);
    }

    /** Returns the index of the last checkpoint at or before an offset. */
    int byOffset(long offset) {
      return search(offsets, offset);
    }

    long line(int checkpoint) {
      return lines[checkpoint / CHUNK][checkpoint % CHUNK];
    }

    long offset(int checkpoint) {
      return offsets[checkpoint / CHUNK][checkpoint % CHUNK];
    }

    private int search(long[][] keys, long key) {
      int low = 0;
      int high = count - 1;
      while (low < high) {
        int mid = (low + high + 1) >>> 1;
        if (keys[mid / CHUNK][mid % CHUNK] <= key) {
          low = mid;
        } else {
          high = mid - 1;
        }
      }
      return low;
    }
  }
}
