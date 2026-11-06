package io.github.ajdailey.qlogg.core.io;

import java.nio.file.Path;

/**
 * Identity of a file at one moment, for telling growth from truncation and rotation.
 *
 * @param fileKey {@code BasicFileAttributes.fileKey()}; null on Windows, where JDK 21 returns null
 * @param creationMillis creation time
 * @param size size in bytes
 * @param modifiedMillis last-modified time
 * @param headDigest CRC32C of the first {@link #HEAD_BYTES} bytes, or of the whole file when
 *     shorter
 * @param realPath the real path opened, or null for a source without a file
 */
public record FileIdentity(
    Object fileKey,
    long creationMillis,
    long size,
    long modifiedMillis,
    long headDigest,
    Path realPath) {
  /** Bytes covered by {@link #headDigest}. */
  public static final int HEAD_BYTES = 4096;
}
