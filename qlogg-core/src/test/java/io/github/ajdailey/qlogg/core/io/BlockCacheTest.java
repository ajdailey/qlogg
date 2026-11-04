package io.github.ajdailey.qlogg.core.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BlockCacheTest {
  /** In-memory source that counts reads and can grow. */
  private static final class MemorySource implements ByteSource {
    final AtomicInteger reads = new AtomicInteger();
    volatile byte[] content;

    MemorySource(byte[] content) {
      this.content = content;
    }

    @Override
    public Path path() {
      return Path.of("memory");
    }

    @Override
    public long size() {
      return content.length;
    }

    @Override
    public int read(long position, ByteBuffer dst) {
      reads.incrementAndGet();
      byte[] bytes = content;
      if (position >= bytes.length) {
        return -1;
      }
      int count = (int) Math.min(dst.remaining(), bytes.length - position);
      dst.put(bytes, (int) position, count);
      return count;
    }

    @Override
    public FileIdentity identity() {
      return new FileIdentity(null, 0, content.length, 0, 0);
    }

    @Override
    public void close() {}
  }

  private static byte[] bytes(int length) {
    byte[] bytes = new byte[length];
    for (int i = 0; i < length; i++) {
      bytes[i] = (byte) i;
    }
    return bytes;
  }

  @Test
  void readsAcrossBlocksAndHitsTheCache() throws IOException {
    byte[] content = bytes(1000);
    MemorySource source = new MemorySource(content);
    BlockCache cache = new BlockCache(64, 64 * 100);
    ByteBuffer dst = ByteBuffer.allocate(300);
    assertEquals(300, cache.read(source, 50, 300, dst));
    assertArrayEquals(Arrays.copyOfRange(content, 50, 350), dst.array());
    int reads = source.reads.get();
    dst.clear();
    assertEquals(300, cache.read(source, 50, 300, dst));
    assertEquals(reads, source.reads.get());
  }

  @Test
  void endOfFileCopiesWhatExists() throws IOException {
    MemorySource source = new MemorySource(bytes(100));
    BlockCache cache = new BlockCache(64, 1 << 20);
    ByteBuffer dst = ByteBuffer.allocate(50);
    assertEquals(20, cache.read(source, 80, 50, dst));
    assertEquals(0, cache.read(source, 500, 0, ByteBuffer.allocate(0)));
  }

  @Test
  void shortBlockIsReadAgainAfterGrowth() throws IOException {
    MemorySource source = new MemorySource(bytes(100));
    BlockCache cache = new BlockCache(64, 1 << 20);
    assertEquals(36, cache.read(source, 64, 40, ByteBuffer.allocate(40)));
    source.content = bytes(200);
    ByteBuffer dst = ByteBuffer.allocate(40);
    assertEquals(40, cache.read(source, 64, 40, dst));
    assertArrayEquals(Arrays.copyOfRange(bytes(200), 64, 104), dst.array());
  }

  @Test
  void evictsLeastRecentlyUsedAndInvalidates() throws IOException {
    MemorySource source = new MemorySource(bytes(1000));
    BlockCache cache = new BlockCache(64, 64 * 3);
    assertEquals(3, cache.maxBlocks());
    for (int block = 0; block < 5; block++) {
      cache.read(source, block * 64L, 1, ByteBuffer.allocate(1));
    }
    assertEquals(3, cache.size());
    int reads = source.reads.get();
    cache.read(source, 4 * 64L, 1, ByteBuffer.allocate(1));
    assertEquals(reads, source.reads.get());
    cache.read(source, 0, 1, ByteBuffer.allocate(1));
    assertEquals(reads + 1, source.reads.get());
    cache.invalidateFrom(64 * 3 + 5);
    assertEquals(1, cache.size());
  }

  @Test
  void invalidateDropsOnlyThatSourcesBlocks() throws IOException {
    MemorySource closing = new MemorySource(bytes(200));
    MemorySource open = new MemorySource(bytes(200));
    BlockCache cache = new BlockCache(64, 64 * 8);
    for (int block = 0; block < 3; block++) {
      cache.read(closing, block * 64L, 1, ByteBuffer.allocate(1));
      cache.read(open, block * 64L, 1, ByteBuffer.allocate(1));
    }
    assertEquals(6, cache.size());
    cache.invalidate(closing);
    assertEquals(3, cache.size());
    int reads = open.reads.get();
    cache.read(open, 0, 1, ByteBuffer.allocate(1));
    assertEquals(reads, open.reads.get());
  }
}
