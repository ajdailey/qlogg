package io.github.ajdailey.qlogg.core.watch;

import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.FileIdentity;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.CRC32C;

/**
 * Classifies the file at a path against a document's {@link Baseline}, in this order: no regular
 * file is {@code MISSING}; another file key, or another creation time where the platform has no
 * file key, is {@code REPLACED}; fewer bytes than covered is {@code TRUNCATED}; another head digest
 * is {@code REPLACED}; more bytes is {@code APPENDED}; the same size with a newer modification time
 * and another tail digest is {@code MODIFIED}; anything else is {@code UNCHANGED}.
 *
 * <p>Creation time decides only where {@code fileKey} is null (Windows): JDK 21 on Linux reports
 * the modification time as the creation time, which changes on every append. The file is opened
 * only to read a digest, through positional reads, and closed at once.
 */
public final class ChangeDetector {
  /** Bytes covered by the tail digest. */
  public static final int TAIL_BYTES = 4096;

  /**
   * The state of the path at one check.
   *
   * @param change the classification
   * @param size the file's size, or -1 when missing
   * @param modifiedMillis its modification time, or -1 when missing
   */
  public record Observation(FileChange change, long size, long modifiedMillis) {}

  private ChangeDetector() {}

  /**
   * Classifies the file at a path. Blocking; never call it on the UI thread.
   *
   * @param baseline what was indexed
   * @param path the document's path
   * @return the observation
   * @throws IOException when the attributes or a digest cannot be read
   */
  public static Observation classify(Baseline baseline, Path path) throws IOException {
    BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(path, BasicFileAttributes.class);
    } catch (NoSuchFileException e) {
      return new Observation(FileChange.MISSING, -1, -1);
    }
    if (!attributes.isRegularFile()) {
      return new Observation(FileChange.MISSING, -1, -1);
    }
    long size = attributes.size();
    long modified = attributes.lastModifiedTime().toMillis();
    return new Observation(classify(baseline, path, attributes, size, modified), size, modified);
  }

  private static FileChange classify(
      Baseline baseline, Path path, BasicFileAttributes attributes, long size, long modified)
      throws IOException {
    FileIdentity identity = baseline.identity();
    Object key = attributes.fileKey();
    if (key != null && identity.fileKey() != null) {
      if (!key.equals(identity.fileKey())) {
        return FileChange.REPLACED;
      }
    } else if (attributes.creationTime().toMillis() != identity.creationMillis()) {
      return FileChange.REPLACED;
    }
    if (size < baseline.coveredBytes()) {
      return FileChange.TRUNCATED;
    }
    long head = Math.min(identity.size(), FileIdentity.HEAD_BYTES);
    if (digest(path, 0, (int) head) != identity.headDigest()) {
      return FileChange.REPLACED;
    }
    if (size > baseline.coveredBytes()) {
      return FileChange.APPENDED;
    }
    if (baseline.tailModifiedMillis() >= 0 && modified != baseline.tailModifiedMillis()) {
      long start = Math.max(0, size - TAIL_BYTES);
      if (digest(path, start, (int) (size - start)) != baseline.tailDigest()) {
        return FileChange.MODIFIED;
      }
    }
    return FileChange.UNCHANGED;
  }

  /**
   * Digests the last {@link #TAIL_BYTES} bytes before an end offset of an open file.
   *
   * @param source the file
   * @param end the end offset
   * @return CRC32C of the bytes read
   * @throws IOException on a read error
   */
  public static long tailDigest(ByteSource source, long end) throws IOException {
    long start = Math.max(0, end - TAIL_BYTES);
    ByteBuffer buffer = ByteBuffer.allocate((int) (end - start));
    while (buffer.hasRemaining()) {
      if (source.read(start + buffer.position(), buffer) < 0) {
        break;
      }
    }
    buffer.flip();
    CRC32C crc = new CRC32C();
    crc.update(buffer);
    return crc.getValue();
  }

  private static long digest(Path path, long start, int length) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(length);
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
      while (buffer.hasRemaining()) {
        if (channel.read(buffer, start + buffer.position()) < 0) {
          break;
        }
      }
    }
    buffer.flip();
    CRC32C crc = new CRC32C();
    crc.update(buffer);
    return crc.getValue();
  }
}
