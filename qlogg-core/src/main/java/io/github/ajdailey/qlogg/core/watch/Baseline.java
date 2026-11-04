package io.github.ajdailey.qlogg.core.watch;

import io.github.ajdailey.qlogg.core.io.FileIdentity;

/**
 * What a document indexed, for {@link ChangeDetector#classify}.
 *
 * @param identity the identity captured when the file was opened
 * @param coveredBytes the indexed bytes, or the end of the region an indexing operation in progress
 *     covers
 * @param headBytes the bytes from offset 0 that {@code headDigest} covers, at most {@link
 *     FileIdentity#HEAD_BYTES} and at most {@code coveredBytes}
 * @param headDigest CRC32C of the first {@code headBytes} bytes
 * @param tailModifiedMillis the modification time when {@code tailDigest} was taken, or -1 when no
 *     digest was taken yet
 * @param tailDigest CRC32C of the last {@link ChangeDetector#TAIL_BYTES} indexed bytes
 */
public record Baseline(
    FileIdentity identity,
    long coveredBytes,
    int headBytes,
    long headDigest,
    long tailModifiedMillis,
    long tailDigest) {}
