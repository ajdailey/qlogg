package io.github.ajdailey.qlogg.core.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.CRC32C;

/**
 * A {@link ByteSource} over a {@link FileChannel} with positional reads; nothing is memory mapped.
 * On Windows the JDK opens the file sharing read, write and delete, so the writer can still append,
 * truncate, rename and delete it.
 */
public final class FileByteSource implements ByteSource {
  private final Path path;
  private final Path realPath;
  private final FileChannel channel;
  private final FileIdentity identity;

  private FileByteSource(Path path, Path realPath, FileChannel channel, FileIdentity identity) {
    this.path = path;
    this.realPath = realPath;
    this.channel = channel;
    this.identity = identity;
  }

  /**
   * Validates the path through {@link SafePaths#logPath(Path)} and opens its real path for reading.
   * {@link #path} is the path as given, so a symbolic link stays a link.
   *
   * @param path the file
   * @return the open source; the caller closes it
   * @throws IOException when the path is rejected, missing or unreadable
   */
  public static FileByteSource open(Path path) throws IOException {
    SafePaths.LogPath checked = SafePaths.logPath(path);
    Path real = checked.real();
    FileChannel channel = FileChannel.open(real, StandardOpenOption.READ);
    try {
      return new FileByteSource(checked.given(), real, channel, identify(real, channel));
    } catch (IOException e) {
      channel.close();
      throw e;
    }
  }

  private static FileIdentity identify(Path path, FileChannel channel) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
    long size = channel.size();
    ByteBuffer head = ByteBuffer.allocate((int) Math.min(size, FileIdentity.HEAD_BYTES));
    while (head.hasRemaining()) {
      if (channel.read(head, head.position()) < 0) {
        break;
      }
    }
    head.flip();
    CRC32C crc = new CRC32C();
    crc.update(head);
    return new FileIdentity(
        attributes.fileKey(),
        attributes.creationTime().toMillis(),
        size,
        attributes.lastModifiedTime().toMillis(),
        crc.getValue());
  }

  @Override
  public Path path() {
    return path;
  }

  /**
   * Returns the real path validated and opened.
   *
   * @return the real path at open
   */
  public Path realPath() {
    return realPath;
  }

  @Override
  public long size() throws IOException {
    return channel.size();
  }

  @Override
  public int read(long position, ByteBuffer dst) throws IOException {
    return channel.read(dst, position);
  }

  @Override
  public FileIdentity identity() {
    return identity;
  }

  /**
   * Reports whether the channel is open.
   *
   * @return false after {@link #close}
   */
  public boolean isOpen() {
    return channel.isOpen();
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
