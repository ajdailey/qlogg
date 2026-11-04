package io.github.ajdailey.qlogg.core.io;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * Positional read access to one open file, or to bytes in the heap. Thread-safe: there is no shared
 * position. A read after {@link #close} throws {@link java.nio.channels.ClosedChannelException}.
 */
public interface ByteSource extends Closeable {
  /**
   * Returns the validated path the source was opened from, absolute and normalized, as given.
   *
   * @return the path, or null for a source with no file
   */
  Path path();

  /**
   * Returns the size of the open file; after a rename, {@link #path} names a different file.
   *
   * @return bytes
   * @throws IOException when the size cannot be read
   */
  long size() throws IOException;

  /**
   * Reads up to {@code dst.remaining()} bytes at a position.
   *
   * @param position the file offset
   * @param dst receives the bytes
   * @return bytes read, or -1 at end of file
   * @throws IOException on a read error or after close
   */
  int read(long position, ByteBuffer dst) throws IOException;

  /**
   * Returns the identity captured at open.
   *
   * @return the identity
   */
  FileIdentity identity();
}
