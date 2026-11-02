package io.github.ajdailey.qlogg.core.io;

/**
 * Identity of a file at one moment, for telling growth from truncation and rotation.
 *
 * @param fileKey {@code BasicFileAttributes.fileKey()}; null on Windows, where JDK 21 returns null
 * @param creationMillis creation time
 * @param size size in bytes
 * @param modifiedMillis last-modified time
 * @param headDigest CRC32C of the first {@link #HEAD_BYTES} bytes, or of the whole file when
 *     shorter
 */
public record FileIdentity(
    Object fileKey, long creationMillis, long size, long modifiedMillis, long headDigest) {
  /** Bytes covered by {@link #headDigest}. */
  public static final int HEAD_BYTES = 4096;
}
