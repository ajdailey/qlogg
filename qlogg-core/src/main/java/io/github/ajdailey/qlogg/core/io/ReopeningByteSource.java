package io.github.ajdailey.qlogg.core.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.CRC32C;

/**
 * A {@link ByteSource} that holds no file between reads, so a writer on Windows can rename over the
 * path. Each read validates the real path captured at open, opens it, and verifies the file before
 * reading: its file key (without one, its creation time), its head digest and the tail digest given
 * to {@link #expect}. The verified channel stays open for the read, so a read returns that file's
 * bytes or fails. A file that fails the comparison throws {@link FileChangedException} and sets
 * {@link #changed}; a missing file throws {@link java.nio.file.NoSuchFileException}.
 */
public final class ReopeningByteSource implements ByteSource {
  /**
   * The bytes a reopened file must hold.
   *
   * @param headBytes the bytes from offset 0 the head digest covers
   * @param headDigest CRC32C of those bytes
   * @param tailEnd the offset the tail digest ends at, or 0 for none
   * @param tailBytes the bytes before {@code tailEnd} the tail digest covers
   * @param tailDigest CRC32C of those bytes
   */
  private record Expected(
      int headBytes, long headDigest, long tailEnd, int tailBytes, long tailDigest) {}

  private final Path path;
  private final Path real;
  private final FileIdentity identity;
  private final SafePaths.HeldLock held;
  private volatile Expected expected;
  private volatile boolean changed;
  private volatile boolean closed;

  private ReopeningByteSource(Path path, FileIdentity identity, SafePaths.HeldLock held) {
    this.path = path;
    this.real = identity.realPath();
    this.identity = identity;
    this.held = held;
    this.expected =
        new Expected(
            (int) Math.min(identity.size(), FileIdentity.HEAD_BYTES),
            identity.headDigest(),
            0,
            0,
            0);
  }

  /**
   * Creates a source on the file an open source verified. The caller reads what it needs from
   * {@code opened}, whose channel pins that file, and then closes it.
   *
   * @param opened the open source
   * @return the source; the caller closes it
   */
  public static ReopeningByteSource of(FileByteSource opened) {
    return new ReopeningByteSource(opened.path(), opened.identity(), opened.heldLock());
  }

  @Override
  public Path path() {
    return path;
  }

  /**
   * Returns the size of the file at the real path, which a replacement changes. A read verifies the
   * file; this does not.
   */
  @Override
  public long size() throws IOException {
    checkOpen();
    return Files.size(real);
  }

  @Override
  public int read(long position, ByteBuffer dst) throws IOException {
    checkOpen();
    try (FileChannel channel = verifiedChannel()) {
      return channel.read(dst, position);
    }
  }

  @Override
  public FileIdentity identity() {
    return identity;
  }

  /**
   * Sets the digests every later read compares, as the indexed bytes grow.
   *
   * @param headBytes the bytes from offset 0 the head digest covers
   * @param headDigest CRC32C of those bytes
   * @param tailEnd the offset the tail digest ends at
   * @param tailBytes the bytes before {@code tailEnd} the tail digest covers
   * @param tailDigest CRC32C of those bytes
   */
  public void expect(int headBytes, long headDigest, long tailEnd, int tailBytes, long tailDigest) {
    expected = new Expected(headBytes, headDigest, tailEnd, tailBytes, tailDigest);
  }

  /**
   * Reports whether a read found another file at the real path.
   *
   * @return true from the first {@link FileChangedException} on
   */
  public boolean changed() {
    return changed;
  }

  @Override
  public void close() {
    closed = true;
  }

  private void checkOpen() throws ClosedChannelException {
    if (closed) {
      throw new ClosedChannelException();
    }
  }

  private FileChannel verifiedChannel() throws IOException {
    if (!real.equals(SafePaths.logFile(real, held))) {
      throw changedFile();
    }
    FileChannel channel = FileChannel.open(real, StandardOpenOption.READ);
    boolean verified = false;
    try {
      if (!sameFile() || !sameBytes(channel)) {
        throw changedFile();
      }
      verified = true;
      return channel;
    } finally {
      if (!verified) {
        channel.close();
      }
    }
  }

  private boolean sameFile() throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(real, BasicFileAttributes.class);
    Object key = attributes.fileKey();
    if (key != null && identity.fileKey() != null) {
      return key.equals(identity.fileKey());
    }
    return attributes.creationTime().toMillis() == identity.creationMillis();
  }

  private boolean sameBytes(FileChannel channel) throws IOException {
    Expected wanted = expected;
    if (digest(channel, 0, wanted.headBytes()) != wanted.headDigest()) {
      return false;
    }
    return wanted.tailEnd() == 0
        || digest(channel, wanted.tailEnd() - wanted.tailBytes(), wanted.tailBytes())
            == wanted.tailDigest();
  }

  private static long digest(FileChannel channel, long start, int length) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(length);
    while (buffer.hasRemaining()) {
      if (channel.read(buffer, start + buffer.position()) < 0) {
        break;
      }
    }
    buffer.flip();
    CRC32C crc = new CRC32C();
    crc.update(buffer);
    return crc.getValue();
  }

  private FileChangedException changedFile() {
    changed = true;
    return new FileChangedException(path);
  }
}
