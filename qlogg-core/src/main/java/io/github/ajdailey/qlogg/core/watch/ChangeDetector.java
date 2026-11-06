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
 * file is {@code MISSING}; another file key, or another creation time or real path where the
 * platform has no file key, is {@code REPLACED}; fewer bytes than covered is {@code TRUNCATED};
 * another head digest is {@code REPLACED}; more bytes with another tail digest at the covered end
 * is {@code REPLACED}, otherwise {@code APPENDED}; the same size with a newer modification time and
 * another tail digest is {@code MODIFIED}; anything else is {@code UNCHANGED}. Both tail
 * comparisons need a recorded tail digest. The growth comparison catches a copy-truncate rotation
 * that regrew past the covered bytes between two checks: the file key stays the same, and the head
 * digest covers only the bytes recorded so far.
 *
 * <p>Creation time decides only where {@code fileKey} is null (Windows): JDK 21 on Linux reports
 * the modification time as the creation time, which changes on every append. The real path decides
 * only there too, and a path that does not resolve is {@code MISSING}: a retargeted symbolic link
 * then reads as {@code REPLACED} even when its new target has the old creation time and starts with
 * the old bytes. A plain file replaced that way stays undetected. The file is opened only to read a
 * digest, through positional reads, and closed at once.
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

  /** Resolves a path to its real path. */
  interface Resolver {
    /**
     * Resolves a path.
     *
     * @param path the path
     * @return its real path
     * @throws IOException when the path does not resolve
     */
    Path realPath(Path path) throws IOException;
  }

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
    return classify(baseline, path, Path::toRealPath);
  }

  /**
   * Classifies the file at a path, resolving its real path through a resolver.
   *
   * @param baseline what was indexed
   * @param path the document's path
   * @param resolver called only where either side has no file key and the creation times match
   * @return the observation
   * @throws IOException when the attributes or a digest cannot be read
   */
  static Observation classify(Baseline baseline, Path path, Resolver resolver) throws IOException {
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
    FileIdentity identity = baseline.identity();
    Object key = attributes.fileKey();
    if (key != null && identity.fileKey() != null) {
      if (!key.equals(identity.fileKey())) {
        return new Observation(FileChange.REPLACED, size, modified);
      }
    } else if (attributes.creationTime().toMillis() != identity.creationMillis()) {
      return new Observation(FileChange.REPLACED, size, modified);
    } else {
      Path real;
      try {
        real = resolver.realPath(path);
      } catch (IOException e) {
        return new Observation(FileChange.MISSING, -1, -1);
      }
      if (!real.equals(identity.realPath())) {
        return new Observation(FileChange.REPLACED, size, modified);
      }
    }
    return new Observation(compareContent(baseline, path, size, modified), size, modified);
  }

  private static FileChange compareContent(Baseline baseline, Path path, long size, long modified)
      throws IOException {
    if (size < baseline.coveredBytes()) {
      return FileChange.TRUNCATED;
    }
    if (digest(path, 0, baseline.headBytes()) != baseline.headDigest()) {
      return FileChange.REPLACED;
    }
    boolean tailRecorded = baseline.tailModifiedMillis() >= 0;
    if (size > baseline.coveredBytes()) {
      if (tailRecorded && tailDiffers(baseline, path)) {
        return FileChange.REPLACED;
      }
      return FileChange.APPENDED;
    }
    if (tailRecorded && modified != baseline.tailModifiedMillis() && tailDiffers(baseline, path)) {
      return FileChange.MODIFIED;
    }
    return FileChange.UNCHANGED;
  }

  private static boolean tailDiffers(Baseline baseline, Path path) throws IOException {
    long end = baseline.coveredBytes();
    long start = Math.max(0, end - TAIL_BYTES);
    return digest(path, start, (int) (end - start)) != baseline.tailDigest();
  }

  /**
   * Digests the first {@code length} bytes of an open file.
   *
   * @param source the file
   * @param length the bytes to digest, at most {@link FileIdentity#HEAD_BYTES}
   * @return CRC32C of the bytes read
   * @throws IOException on a read error
   */
  public static long headDigest(ByteSource source, int length) throws IOException {
    return digest(source, 0, length);
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
    return digest(source, start, (int) (end - start));
  }

  private static long digest(ByteSource source, long start, int length) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(length);
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
