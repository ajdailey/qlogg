package io.github.ajdailey.qlogg.core.io;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.util.Checks;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pool of direct read buffers for sequential scans, shared by every operation. Buffers are
 * allocated on demand and kept: a direct buffer is freed only by garbage collection, so reuse
 * avoids native memory churn. Each buffer is reserved from {@link MemoryShare#IO_BUFFERS}; the
 * first {@link #MIN_BUFFERS} are charged without the limit check so a scan can always run, and past
 * the share callers wait for a buffer instead. Buffers are little-endian. Thread-safe.
 */
public final class ReadBuffers {
  /** Buffers allocated regardless of the share. */
  public static final int MIN_BUFFERS = 2;

  private static final long WAIT_SLICE_MILLIS = 50;

  private final int bufferBytes;
  private final int maxBuffers;
  private final MemoryBudget budget;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition returned = lock.newCondition();
  private final ArrayDeque<ByteBuffer> free = new ArrayDeque<ByteBuffer>();
  private int allocated;

  /**
   * Creates an empty pool.
   *
   * @param bufferBytes bytes per buffer
   * @param maxBuffers cap on buffers, at least {@link #MIN_BUFFERS}
   * @param budget the budget the buffers are reserved from
   */
  public ReadBuffers(int bufferBytes, int maxBuffers, MemoryBudget budget) {
    Checks.atLeast(bufferBytes, 1, "bufferBytes");
    this.bufferBytes = bufferBytes;
    this.maxBuffers = Math.max(MIN_BUFFERS, maxBuffers);
    this.budget = budget;
  }

  /**
   * Returns the buffer size.
   *
   * @return bytes
   */
  public int bufferBytes() {
    return bufferBytes;
  }

  /**
   * Takes a free buffer, allocating one if the cap and the share allow, without waiting.
   *
   * @return a cleared buffer, or null when none is available
   */
  public ByteBuffer tryAcquire() {
    lock.lock();
    try {
      return takeLocked();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Takes a buffer, waiting for one to be returned when none is available.
   *
   * @param token checked while waiting
   * @return a cleared buffer
   * @throws InterruptedException when interrupted while waiting
   * @throws java.util.concurrent.CancellationException when the token is canceled while waiting
   */
  public ByteBuffer acquire(CancellationToken token) throws InterruptedException {
    lock.lock();
    try {
      while (true) {
        token.check();
        ByteBuffer buffer = takeLocked();
        if (buffer != null) {
          return buffer;
        }
        if (!returned.await(WAIT_SLICE_MILLIS, TimeUnit.MILLISECONDS)) {
          continue;
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns a buffer taken from this pool.
   *
   * @param buffer the buffer
   */
  public void release(ByteBuffer buffer) {
    lock.lock();
    try {
      free.push(buffer);
      returned.signal();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Counts buffers allocated so far.
   *
   * @return buffers
   */
  public int allocated() {
    lock.lock();
    try {
      return allocated;
    } finally {
      lock.unlock();
    }
  }

  private ByteBuffer takeLocked() {
    ByteBuffer buffer = free.poll();
    if (buffer == null && allocated < maxBuffers) {
      if (allocated < MIN_BUFFERS) {
        budget.charge(MemoryShare.IO_BUFFERS, bufferBytes);
      } else if (!budget.reserve(MemoryShare.IO_BUFFERS, bufferBytes)) {
        return null;
      }
      allocated++;
      buffer = ByteBuffer.allocateDirect(bufferBytes).order(ByteOrder.LITTLE_ENDIAN);
    }
    if (buffer != null) {
      buffer.clear();
    }
    return buffer;
  }
}
