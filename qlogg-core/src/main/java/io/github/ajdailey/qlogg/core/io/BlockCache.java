package io.github.ajdailey.qlogg.core.io;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.util.Checks;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded LRU of fixed-size blocks for random reads (viewport, filtered view, sparse index
 * lookups). Sequential indexing and search bypass it. Blocks are keyed by source identity and block
 * number, so one cache can serve several sources. A block read short at end of file is re-read when
 * a later request reaches past its end, so growth is visible without invalidation.
 *
 * <p>An evicted block's array is reused for the next load, so misses allocate nothing once the
 * cache is full; a new array per miss is promoted before its eviction and grows the old generation
 * with garbage. Bytes are copied out under the lock, because an array is refilled as soon as its
 * block leaves the map. Thread-safe; the lock is never held during I/O.
 */
public final class BlockCache {
  /** Bytes per block of the cache {@link #fromBudget} creates. */
  public static final int BLOCK_BYTES = 64 * 1024;

  /**
   * Cap on the cache {@link #fromBudget} creates: 1,024 blocks, 20 screens of 50 rows that each
   * fall in a different block.
   */
  public static final long MAX_BYTES = 64L * 1024 * 1024;

  /** Evicted arrays kept for reuse. */
  private static final int MAX_SPARES = 4;

  private record Key(ByteSource source, long block) {}

  private record Block(byte[] bytes, int length) {}

  private final int blockSize;
  private final int maxBlocks;
  private final ReentrantLock lock = new ReentrantLock();
  private final LinkedHashMap<Key, Block> blocks;
  private final ArrayDeque<byte[]> spares = new ArrayDeque<byte[]>();

  /**
   * Creates the cache.
   *
   * @param blockSize bytes per block, at least 1
   * @param maxBytes cap on cached bytes; at least one block is kept
   */
  public BlockCache(int blockSize, long maxBytes) {
    Checks.atLeast(blockSize, 1, "blockSize");
    this.blockSize = blockSize;
    this.maxBlocks = (int) Math.max(1, Math.min(Integer.MAX_VALUE, maxBytes / blockSize));
    this.blocks = new LinkedHashMap<Key, Block>(16, 0.75f, true);
  }

  /**
   * Creates the cache every document of a process shares: {@link #BLOCK_BYTES} blocks, capped at
   * the smaller of the {@link MemoryShare#BLOCK_CACHE} share and {@link #MAX_BYTES}, with the cap
   * charged to the share for the life of the process.
   *
   * @param budget the budget charged
   * @return the cache
   */
  public static BlockCache fromBudget(MemoryBudget budget) {
    long bytes = Math.min(budget.limit(MemoryShare.BLOCK_CACHE), MAX_BYTES);
    budget.charge(MemoryShare.BLOCK_CACHE, bytes);
    return new BlockCache(BLOCK_BYTES, bytes);
  }

  /**
   * Returns the block size.
   *
   * @return bytes
   */
  public int blockSize() {
    return blockSize;
  }

  /**
   * Returns the cap.
   *
   * @return blocks
   */
  public int maxBlocks() {
    return maxBlocks;
  }

  /**
   * Copies bytes at a position into {@code dst}, reading through the cache.
   *
   * @param source the file
   * @param position the file offset
   * @param length bytes wanted, at most {@code dst.remaining()}
   * @param dst receives the bytes
   * @return bytes copied; fewer than {@code length} only at end of file
   * @throws IOException on a read error
   */
  public int read(ByteSource source, long position, int length, ByteBuffer dst) throws IOException {
    if (length > dst.remaining()) {
      throw new IllegalArgumentException("length exceeds dst.remaining()");
    }
    int copied = 0;
    while (copied < length) {
      long at = position + copied;
      long number = at / blockSize;
      int offset = (int) (at - number * blockSize);
      int want = Math.min(length - copied, blockSize - offset);
      int count = copy(source, number, offset, want, dst);
      copied += count;
      if (count < want) {
        break;
      }
    }
    return copied;
  }

  /**
   * Drops every cached block that contains or follows a position, in every source.
   *
   * @param position the file offset
   */
  public void invalidateFrom(long position) {
    long first = position / blockSize;
    lock.lock();
    try {
      Iterator<Map.Entry<Key, Block>> entries = blocks.entrySet().iterator();
      while (entries.hasNext()) {
        Map.Entry<Key, Block> entry = entries.next();
        if (entry.getKey().block() >= first) {
          entries.remove();
          spare(entry.getValue());
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Drops every cached block of a source, as when the source closes.
   *
   * @param source the file
   */
  public void invalidate(ByteSource source) {
    lock.lock();
    try {
      Iterator<Map.Entry<Key, Block>> entries = blocks.entrySet().iterator();
      while (entries.hasNext()) {
        Map.Entry<Key, Block> entry = entries.next();
        if (entry.getKey().source() == source) {
          entries.remove();
          spare(entry.getValue());
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Counts cached blocks.
   *
   * @return blocks held
   */
  public int size() {
    lock.lock();
    try {
      return blocks.size();
    } finally {
      lock.unlock();
    }
  }

  /** Copies up to {@code want} bytes of a block from {@code offset}; fewer only at end of file. */
  private int copy(ByteSource source, long number, int offset, int want, ByteBuffer dst)
      throws IOException {
    Key key = new Key(source, number);
    int count = copyCached(key, offset, want, dst);
    if (count >= 0) {
      return count;
    }
    byte[] array = takeSpare();
    if (array == null) {
      array = new byte[blockSize];
    }
    return putAndCopy(key, load(source, number, array), offset, want, dst);
  }

  /** Copies from a cached block that covers the request; -1 when there is none. */
  private int copyCached(Key key, int offset, int want, ByteBuffer dst) {
    lock.lock();
    try {
      Block cached = blocks.get(key);
      if (cached != null && (cached.length() >= offset + want || cached.length() == blockSize)) {
        return copyLocked(cached, offset, want, dst);
      }
      return -1;
    } finally {
      lock.unlock();
    }
  }

  private byte[] takeSpare() {
    lock.lock();
    try {
      return spares.poll();
    } finally {
      lock.unlock();
    }
  }

  private int putAndCopy(Key key, Block loaded, int offset, int want, ByteBuffer dst) {
    lock.lock();
    try {
      spare(blocks.put(key, loaded));
      Iterator<Block> eldest = blocks.values().iterator();
      while (blocks.size() > maxBlocks && eldest.hasNext()) {
        Block evicted = eldest.next();
        eldest.remove();
        spare(evicted);
      }
      return copyLocked(loaded, offset, want, dst);
    } finally {
      lock.unlock();
    }
  }

  private int copyLocked(Block block, int offset, int want, ByteBuffer dst) {
    int count = Math.max(0, Math.min(want, block.length() - offset));
    dst.put(block.bytes(), offset, count);
    return count;
  }

  /** Keeps a dropped block's array for the next load, up to {@link #MAX_SPARES}. Lock held. */
  private void spare(Block dropped) {
    if (dropped != null && spares.size() < MAX_SPARES) {
      spares.push(dropped.bytes());
    }
  }

  private Block load(ByteSource source, long number, byte[] array) throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(array);
    long base = number * blockSize;
    while (buffer.hasRemaining()) {
      if (source.read(base + buffer.position(), buffer) < 0) {
        break;
      }
    }
    return new Block(array, buffer.position());
  }
}
