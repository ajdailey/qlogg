package io.github.ajdailey.qlogg.core.rotate;

import io.github.ajdailey.qlogg.core.io.FileIdentity;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32C;

/**
 * What identifies a rotated member across a rename. With a file key (Linux, macOS) the key alone
 * decides. Without one (Windows), a file matches when it holds at least the indexed bytes and its
 * head and tail digests over the indexed region are equal; creation time only separates several
 * such files, since NTFS tunneling gives a file created under a just-renamed name the old creation
 * time.
 *
 * @param fileKey {@code BasicFileAttributes.fileKey()}, or null
 * @param creationMillis creation time
 * @param indexedBytes the member's indexed region, from offset 0
 * @param headDigest CRC32C of the indexed region's first {@link FileIdentity#HEAD_BYTES} bytes, or
 *     all of it when shorter
 * @param tailDigest CRC32C of the indexed region's last {@link FileIdentity#HEAD_BYTES} bytes, or
 *     all of it when shorter
 */
public record MemberIdentity(
    Object fileKey, long creationMillis, long indexedBytes, long headDigest, long tailDigest) {

  /**
   * The result of {@link #find}.
   *
   * @param outcome whether one file matched
   * @param path the file when {@code FOUND}, otherwise null
   */
  public record Match(Outcome outcome, Path path) {}

  /** Whether a search found the member. */
  public enum Outcome {
    /** Exactly one file matched. */
    FOUND,
    /** No file matched. */
    NONE,
    /** Several files matched and creation time did not pick one. */
    AMBIGUOUS
  }

  /**
   * Records a member's identity. Blocks.
   *
   * @param file the member
   * @param indexedBytes the indexed region the digests cover, at most the file's size
   * @return the identity
   * @throws IOException when the file cannot be read or holds fewer bytes
   */
  public static MemberIdentity read(Path file, long indexedBytes) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      if (channel.size() < indexedBytes) {
        throw new IOException(file + " holds fewer than " + indexedBytes + " bytes");
      }
      return new MemberIdentity(
          attributes.fileKey(),
          attributes.creationTime().toMillis(),
          indexedBytes,
          digest(channel, 0, Math.min(indexedBytes, FileIdentity.HEAD_BYTES)),
          digest(
              channel,
              Math.max(0, indexedBytes - FileIdentity.HEAD_BYTES),
              Math.min(indexedBytes, FileIdentity.HEAD_BYTES)));
    }
  }

  /**
   * Returns this identity without its file key, so {@link #find} compares content: a copy of the
   * member matches, and so does the member itself only while it still holds the indexed bytes.
   *
   * @return the identity with a null file key
   */
  public MemberIdentity byContent() {
    return new MemberIdentity(null, creationMillis, indexedBytes, headDigest, tailDigest);
  }

  /**
   * Finds the member among files. A file that cannot be read does not match. Blocks; reads at most
   * two digests' worth per file.
   *
   * @param candidates the files to compare, in any order
   * @return the match
   */
  public Match find(List<Path> candidates) {
    List<Path> matched = new ArrayList<Path>();
    List<Long> created = new ArrayList<Long>();
    for (Path candidate : candidates) {
      try {
        BasicFileAttributes attributes = Files.readAttributes(candidate, BasicFileAttributes.class);
        boolean same =
            fileKey != null
                ? Objects.equals(fileKey, attributes.fileKey())
                : sameContent(candidate);
        if (same) {
          matched.add(candidate);
          created.add(attributes.creationTime().toMillis());
        }
      } catch (IOException e) {
        // Gone or unreadable: not this member.
      }
    }
    if (matched.size() == 1) {
      return new Match(Outcome.FOUND, matched.get(0));
    }
    if (matched.isEmpty()) {
      return new Match(Outcome.NONE, null);
    }
    Path byCreation = null;
    for (int i = 0; i < matched.size(); i++) {
      if (created.get(i) == creationMillis) {
        if (byCreation != null) {
          return new Match(Outcome.AMBIGUOUS, null);
        }
        byCreation = matched.get(i);
      }
    }
    return byCreation == null
        ? new Match(Outcome.AMBIGUOUS, null)
        : new Match(Outcome.FOUND, byCreation);
  }

  private boolean sameContent(Path candidate) throws IOException {
    try (FileChannel channel = FileChannel.open(candidate, StandardOpenOption.READ)) {
      if (channel.size() < indexedBytes) {
        return false;
      }
      long length = Math.min(indexedBytes, FileIdentity.HEAD_BYTES);
      return digest(channel, 0, length) == headDigest
          && digest(channel, indexedBytes - length, length) == tailDigest;
    }
  }

  private static long digest(FileChannel channel, long from, long length) throws IOException {
    ByteBuffer bytes = ByteBuffer.allocate((int) length);
    while (bytes.hasRemaining()) {
      if (channel.read(bytes, from + bytes.position()) < 0) {
        throw new IOException("File shrank while it was read");
      }
    }
    bytes.flip();
    CRC32C crc = new CRC32C();
    crc.update(bytes);
    return crc.getValue();
  }
}
