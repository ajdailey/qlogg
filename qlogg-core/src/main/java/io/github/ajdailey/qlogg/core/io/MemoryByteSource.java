package io.github.ajdailey.qlogg.core.io;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Path;
import java.util.zip.CRC32C;

/**
 * A {@link ByteSource} over bytes held in the heap, such as pasted text. It has no path and never
 * changes. Thread-safe; a read after {@link #close} throws {@link ClosedChannelException}.
 */
public final class MemoryByteSource implements ByteSource {
  private final byte[] bytes;
  private final FileIdentity identity;
  private volatile boolean closed;

  /**
   * Wraps bytes without copying them; the caller does not change them afterwards.
   *
   * @param bytes the content
   * @param createdMillis reported as the creation and modification time
   */
  public MemoryByteSource(byte[] bytes, long createdMillis) {
    this.bytes = bytes;
    CRC32C crc = new CRC32C();
    crc.update(bytes, 0, Math.min(bytes.length, FileIdentity.HEAD_BYTES));
    this.identity =
        new FileIdentity(null, createdMillis, bytes.length, createdMillis, crc.getValue(), null);
  }

  /**
   * Returns null: the content has no file.
   *
   * @return null
   */
  @Override
  public Path path() {
    return null;
  }

  @Override
  public long size() throws ClosedChannelException {
    if (closed) {
      throw new ClosedChannelException();
    }
    return bytes.length;
  }

  @Override
  public int read(long position, ByteBuffer dst) throws ClosedChannelException {
    if (closed) {
      throw new ClosedChannelException();
    }
    if (position >= bytes.length) {
      return -1;
    }
    int count = (int) Math.min(dst.remaining(), bytes.length - position);
    dst.put(bytes, (int) position, count);
    return count;
  }

  @Override
  public FileIdentity identity() {
    return identity;
  }

  @Override
  public void close() {
    closed = true;
  }
}
