package io.github.ajdailey.qlogg.core.index;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.ReadBuffers;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds line indexes in the background. Each operation runs a coordinator on a {@code
 * qlogg-index-} virtual thread that reads blocks with positional reads into pooled direct buffers
 * and submits each block to the CPU pool, which marks the LF units in it. The coordinator appends
 * the results in file order and publishes a snapshot at most every {@link #PUBLISH_INTERVAL_MILLIS}
 * ms and once at the end. At most {@code maxBlocksInFlight} blocks are read but not yet appended,
 * fewer when the {@code IO_BUFFERS} share is short.
 *
 * <p>An operation trims the buffer pool when it ends. When a scan outlives the operation's bounded
 * wait at its end, the scan returns its buffer to the pool when it finishes.
 *
 * <p>Cancellation is checked before each block is read and while waiting for a scan, so an
 * operation stops within one block. A read on a closed source also ends it as {@code CANCELED}. The
 * listener's {@code finished} call comes before the handle reports done.
 */
public final class Indexer {
  /** Least interval between published snapshots. */
  public static final long PUBLISH_INTERVAL_MILLIS = 100;

  private static final long WAIT_SLICE_MILLIS = 50;
  private static final long DRAIN_BOUND_MILLIS = 5000;

  private final Workers workers;
  private final IndexSettings settings;
  private final ReadBuffers buffers;

  /**
   * Creates an indexer with its own buffer pool.
   *
   * @param workers runs coordinators and scans
   * @param settings block size, in-flight bound and budget
   */
  public Indexer(Workers workers, IndexSettings settings) {
    this.workers = workers;
    this.settings = settings;
    this.buffers =
        new ReadBuffers(settings.blockBytes(), settings.maxBlocksInFlight(), settings.budget());
  }

  /**
   * Starts indexing a source from the target's resume offset to the source's current size. A
   * trailing partial UTF-16 or UTF-32 unit is left for a later operation.
   *
   * @param source the file; the caller owns and closes it
   * @param target the index to extend; one operation at a time
   * @param fromByte must equal {@code target.resumeOffset()}
   * @param listener receives progress and the outcome on the indexing thread
   * @return the handle
   * @throws IllegalArgumentException when fromByte is not the resume offset
   * @throws IllegalStateException when another operation is writing the target
   * @throws RejectedExecutionException after the workers close
   */
  public OperationHandle index(
      ByteSource source, GrowableLineIndex target, long fromByte, IndexListener listener) {
    if (fromByte != target.resumeOffset()) {
      throw new IllegalArgumentException("fromByte must be the target's resume offset");
    }
    target.beginWrite();
    Operation operation = new Operation(source, target, fromByte, listener);
    try {
      workers.start(Workers.Kind.INDEX, operation);
    } catch (RejectedExecutionException e) {
      target.endWrite();
      throw e;
    }
    return operation;
  }

  /** One read block and the LF bits its scan produces. */
  private static final class Slot {
    final ByteBuffer buffer;
    final long[] bits;
    final long start;
    final int length;
    final CountDownLatch done = new CountDownLatch(1);

    /** Set by whichever of the scan's end and the coordinator's abandonment comes first. */
    final AtomicBoolean handedOff = new AtomicBoolean();

    volatile boolean scanned;

    Slot(ByteBuffer buffer, long[] bits, long start, int length) {
      this.buffer = buffer;
      this.bits = bits;
      this.start = start;
      this.length = length;
    }
  }

  private final class Operation implements OperationHandle, Runnable {
    private final ByteSource source;
    private final GrowableLineIndex target;
    private final long fromByte;
    private final IndexListener listener;
    private final LineScanner scanner;
    private final CancellationToken token = new CancellationToken();
    private final CountDownLatch done = new CountDownLatch(1);
    private final ArrayDeque<Slot> inFlight = new ArrayDeque<Slot>();
    private final ArrayDeque<long[]> freeBits = new ArrayDeque<long[]>();
    private long totalBytes;

    Operation(ByteSource source, GrowableLineIndex target, long fromByte, IndexListener listener) {
      this.source = source;
      this.target = target;
      this.fromByte = fromByte;
      this.listener = listener;
      this.scanner = new LineScanner(target.encoding());
    }

    @Override
    public void cancel() {
      token.cancel();
    }

    @Override
    public boolean isDone() {
      return done.getCount() == 0;
    }

    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
      return done.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void run() {
      OperationOutcome outcome = OperationOutcome.FAILED;
      IOException failure = null;
      try {
        scanAll();
        outcome = OperationOutcome.COMPLETED;
      } catch (CancellationException | RejectedExecutionException | ClosedChannelException e) {
        outcome = OperationOutcome.CANCELED;
      } catch (InterruptedException e) {
        outcome = OperationOutcome.CANCELED;
        Thread.currentThread().interrupt();
      } catch (IOException e) {
        failure = e;
      } finally {
        drain();
        LineIndex snapshot = target.publish();
        target.endWrite();
        try {
          listener.progressed(
              new IndexProgress(snapshot.indexedBytes(), totalBytes, snapshot.lineCount()));
          listener.finished(outcome, failure);
        } finally {
          done.countDown();
        }
      }
    }

    private void scanAll() throws IOException, InterruptedException {
      int width = scanner.width();
      long size = source.size();
      long limit = size <= fromByte ? fromByte : fromByte + (size - fromByte) / width * width;
      totalBytes = limit;
      long position = fromByte;
      long lastPublish = System.nanoTime();
      while (position < limit || !inFlight.isEmpty()) {
        token.check();
        if (position < limit && inFlight.size() < settings.maxBlocksInFlight()) {
          ByteBuffer buffer = inFlight.isEmpty() ? buffers.acquire(token) : buffers.tryAcquire();
          if (buffer != null) {
            int length = fill(buffer, position, limit);
            if (length == 0) {
              buffers.release(buffer);
              limit = position;
              totalBytes = limit;
              continue;
            }
            submit(new Slot(buffer, bits(), position, length));
            position += length;
            continue;
          }
        }
        Slot head = inFlight.peek();
        awaitScan(head);
        inFlight.poll();
        append(head);
        if (System.nanoTime() - lastPublish >= PUBLISH_INTERVAL_MILLIS * 1_000_000L) {
          LineIndex snapshot = target.publish();
          listener.progressed(
              new IndexProgress(snapshot.indexedBytes(), totalBytes, snapshot.lineCount()));
          lastPublish = System.nanoTime();
        }
      }
    }

    /** Reads up to one block of whole units; returns 0 when the file ended early. */
    private int fill(ByteBuffer buffer, long position, long limit) throws IOException {
      int want = (int) Math.min(buffer.capacity(), limit - position);
      buffer.clear().limit(want);
      while (buffer.hasRemaining()) {
        if (source.read(position + buffer.position(), buffer) < 0) {
          break;
        }
      }
      int length = buffer.position();
      return length - length % scanner.width();
    }

    private long[] bits() {
      long[] free = freeBits.poll();
      return free != null ? free : new long[settings.blockBytes() / 64 + 1];
    }

    private void submit(final Slot slot) {
      inFlight.add(slot);
      try {
        workers.executeCpu(
            new Runnable() {
              @Override
              public void run() {
                try {
                  scanner.scan(slot.buffer, slot.length, slot.bits);
                  slot.scanned = true;
                } finally {
                  slot.done.countDown();
                  if (!slot.handedOff.compareAndSet(false, true)) {
                    // The coordinator abandoned this slot, so the buffer is returned here.
                    buffers.release(slot.buffer);
                  }
                }
              }
            });
      } catch (RejectedExecutionException e) {
        slot.done.countDown();
        throw e;
      }
    }

    private void awaitScan(Slot slot) throws InterruptedException {
      while (!slot.done.await(WAIT_SLICE_MILLIS, TimeUnit.MILLISECONDS)) {
        token.check();
        if (workers.isClosed()) {
          throw new CancellationException("Workers closed");
        }
      }
    }

    private void append(Slot slot) throws IOException {
      try {
        if (!slot.scanned) {
          throw new IOException("Line scan failed");
        }
        long[] bits = slot.bits;
        int words = (slot.length >>> 6) + 1;
        long base = slot.start + scanner.width();
        for (int i = 0; i < words; i++) {
          long word = bits[i];
          while (word != 0) {
            int offset = (i << 6) + Long.numberOfTrailingZeros(word);
            target.addLineStart(base + offset);
            word &= word - 1;
          }
        }
        target.advanceTo(slot.start + slot.length);
      } finally {
        recycle(slot);
      }
    }

    private void recycle(Slot slot) {
      buffers.release(slot.buffer);
      freeBits.push(slot.bits);
    }

    /**
     * Waits for scans still running, bounded, and returns their buffers. A slot still scanning at
     * the bound is abandoned: its scan returns the buffer when it ends. Then trims the pool.
     */
    private void drain() {
      long deadline = System.nanoTime() + DRAIN_BOUND_MILLIS * 1_000_000L;
      boolean interrupted = false;
      while (!inFlight.isEmpty()) {
        Slot slot = inFlight.poll();
        boolean ended = false;
        try {
          long left = Math.max(0, deadline - System.nanoTime());
          ended = slot.done.await(left, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
          interrupted = true;
        }
        if (ended || !slot.handedOff.compareAndSet(false, true)) {
          recycle(slot);
        }
      }
      buffers.trim();
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
