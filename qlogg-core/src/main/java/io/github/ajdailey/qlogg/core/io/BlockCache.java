package io.github.ajdailey.qlogg.core.io;

import io.github.ajdailey.qlogg.core.util.Checks;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded LRU of fixed-size blocks for random reads (viewport, filtered view, sparse index
 * lookups). Sequential indexing and search bypass it. Blocks are keyed by source identity and block
 * number, so one cache can serve several sources. A block read short at end of file is re-read when
 * a later request reaches past its end, so growth is visible without invalidation. Thread-safe; the
 * lock is never held during I/O.
 */
public final class BlockCache {
  private record Key(ByteSource source, long block) {}

  private record Block(byte[] bytes, int length) {}

  private final int blockSize;
  private final int maxBlocks;
  private final ReentrantLock lock = new ReentrantLock();
  private final LinkedHashMap<Key, Block> blocks;

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
      Block block = block(source, number, offset + want);
      int available = block.length() - offset;
      if (available <= 0) {
        break;
      }
      int count = Math.min(want, available);
      dst.put(block.bytes(), offset, count);
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
      Iterator<Key> keys = blocks.keySet().iterator();
      while (keys.hasNext()) {
        if (keys.next().block() >= first) {
          keys.remove();
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
      Iterator<Key> keys = blocks.keySet().iterator();
      while (keys.hasNext()) {
        if (keys.next().source() == source) {
          keys.remove();
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

  private Block block(ByteSource source, long number, int needed) throws IOException {
    Key key = new Key(source, number);
    lock.lock();
    try {
      Block cached = blocks.get(key);
      if (cached != null && (cached.length() >= needed || cached.length() == blockSize)) {
        return cached;
      }
    } finally {
      lock.unlock();
    }
    Block loaded = load(source, number);
    lock.lock();
    try {
      blocks.put(key, loaded);
      Iterator<Map.Entry<Key, Block>> eldest = blocks.entrySet().iterator();
      while (blocks.size() > maxBlocks && eldest.hasNext()) {
        eldest.next();
        eldest.remove();
      }
    } finally {
      lock.unlock();
    }
    return loaded;
  }

  private Block load(ByteSource source, long number) throws IOException {
    byte[] bytes = new byte[blockSize];
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    long base = number * blockSize;
    while (buffer.hasRemaining()) {
      if (source.read(base + buffer.position(), buffer) < 0) {
        break;
      }
    }
    return new Block(bytes, buffer.position());
  }
}
