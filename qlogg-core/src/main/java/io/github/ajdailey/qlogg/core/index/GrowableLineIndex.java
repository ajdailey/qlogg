package io.github.ajdailey.qlogg.core.index;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The line index of one document. One {@link Indexer} operation at a time writes it; any thread
 * reads the latest published {@link #snapshot}, which never changes after publication.
 *
 * <p>Storage starts in full mode (packed line lengths, about 1 byte per line for lines under 256
 * bytes). Every allocation is reserved from the {@code LINE_INDEX} share first. When a reservation
 * is refused the index converts in place to sparse mode (checkpoints, 16 bytes per 1,024 lines or
 * per MiB), whose lookups read the file through the block cache; it stays sparse until closed. When
 * even the sparse index is refused, indexing fails with {@link IndexCapacityException}.
 */
public final class GrowableLineIndex implements AutoCloseable {
  private final TextEncoding encoding;
  private final MemoryBudget budget;
  private final ByteSource source;
  private final BlockCache cache;
  private final LineScanner scanner;
  private final int unit;
  private final long firstStart;
  private final AtomicBoolean writing = new AtomicBoolean();
  private PackedStarts packed;
  private SparseStarts sparse;
  private long startCount;
  private long lastStart;
  private long indexedBytes;
  private long maxLineBytes;
  private long generation;
  private volatile IndexSnapshot published;
  private volatile long retained;
  private boolean closed;

  /**
   * Creates an empty index whose first line starts after the byte order mark.
   *
   * @param encoding the file's encoding; its BOM length is the first line's start
   * @param budget the index reserves from its {@code LINE_INDEX} share
   * @param source read by sparse-mode lookups; the caller owns and closes it
   * @param cache sparse-mode lookups read through it
   * @throws IndexCapacityException when the share cannot hold even an empty sparse index
   */
  public GrowableLineIndex(
      TextEncoding encoding, MemoryBudget budget, ByteSource source, BlockCache cache)
      throws IndexCapacityException {
    this.encoding = encoding;
    this.budget = budget;
    this.source = source;
    this.cache = cache;
    this.scanner = new LineScanner(encoding);
    this.unit = encoding.unitWidth();
    this.firstStart = encoding.bomLength();
    this.packed = PackedStarts.create(budget);
    if (packed == null || !packed.add(firstStart)) {
      if (packed != null) {
        packed.release();
        packed = null;
      }
      this.sparse = SparseStarts.create(budget, firstStart);
    }
    this.startCount = 1;
    this.lastStart = firstStart;
    this.indexedBytes = firstStart;
    publish();
  }

  /**
   * Returns the encoding the index was built for.
   *
   * @return the encoding
   */
  public TextEncoding encoding() {
    return encoding;
  }

  /**
   * Returns the latest published snapshot.
   *
   * @return an immutable snapshot
   */
  public LineIndex snapshot() {
    return published;
  }

  /**
   * Returns where the next indexing operation starts: the start of the last line, so a partial last
   * line is scanned again as the file grows.
   *
   * @return byte offset
   */
  public long resumeOffset() {
    return published.lastStart;
  }

  /**
   * Returns the bytes held by the index arrays, as of the latest snapshot. All of it is reserved
   * from the {@code LINE_INDEX} share.
   *
   * @return bytes
   */
  public long retainedBytes() {
    return retained;
  }

  /** Returns the reservation. Published snapshots stay readable. Idempotent. */
  @Override
  public void close() {
    if (!writing.compareAndSet(false, true)) {
      throw new IllegalStateException("Close the index after its indexing operation ends");
    }
    if (!closed) {
      closed = true;
      if (packed != null) {
        packed.release();
      }
      if (sparse != null) {
        sparse.release();
      }
    }
    writing.set(false);
  }

  /** Claims the writer role for one operation. */
  void beginWrite() {
    if (!writing.compareAndSet(false, true)) {
      throw new IllegalStateException("Another operation is writing this index");
    }
    if (closed || (packed == null && sparse == null)) {
      writing.set(false);
      throw new IllegalStateException("Index is closed or its storage was refused");
    }
  }

  /** Gives up the writer role. */
  void endWrite() {
    writing.set(false);
  }

  /**
   * Records that a new line starts at an offset; the line before it is complete.
   *
   * @throws IndexCapacityException when the sparse index cannot grow
   */
  void addLineStart(long start) throws IndexCapacityException {
    maxLineBytes = Math.max(maxLineBytes, start - unit - lastStart);
    if (packed != null && !packed.add(start)) {
      convertToSparse();
    }
    if (sparse != null) {
      sparse.add(startCount, start);
    }
    startCount++;
    lastStart = start;
    indexedBytes = Math.max(indexedBytes, start);
  }

  /** Extends the indexed region to an offset; bytes after the last start form a partial line. */
  void advanceTo(long end) {
    indexedBytes = Math.max(indexedBytes, end);
  }

  /**
   * Publishes the current state as a new snapshot. After a refused sparse conversion the storage is
   * gone and the last snapshot stays published.
   */
  LineIndex publish() {
    if (packed == null && sparse == null) {
      retained = 0;
      return published;
    }
    generation++;
    IndexSnapshot snapshot;
    if (packed != null) {
      snapshot =
          new FullSnapshot(
              packed.view(), firstStart, lastStart, indexedBytes, maxLineBytes, generation, unit);
      retained = packed.retainedBytes();
    } else {
      snapshot =
          new SparseSnapshot(
              sparse.view(),
              startCount,
              firstStart,
              lastStart,
              indexedBytes,
              maxLineBytes,
              generation,
              source,
              cache,
              scanner);
      retained = sparse.retainedBytes();
    }
    published = snapshot;
    return snapshot;
  }

  private void convertToSparse() throws IndexCapacityException {
    PackedStarts.View view = packed.view();
    packed.release();
    packed = null;
    final SparseStarts starts = SparseStarts.create(budget, firstStart);
    final IndexCapacityException[] refused = new IndexCapacityException[1];
    view.forEach(
        new PackedStarts.Sink() {
          @Override
          public boolean accept(long line, long start) {
            if (line == 0) {
              return true;
            }
            try {
              starts.add(line, start);
              return true;
            } catch (IndexCapacityException e) {
              refused[0] = e;
              return false;
            }
          }
        });
    if (refused[0] != null) {
      starts.release();
      throw refused[0];
    }
    sparse = starts;
  }
}
