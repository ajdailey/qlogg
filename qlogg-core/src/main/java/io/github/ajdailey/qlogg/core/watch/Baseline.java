package io.github.ajdailey.qlogg.core.watch;

import io.github.ajdailey.qlogg.core.io.FileIdentity;

/**
 * What a document indexed, for {@link ChangeDetector#classify}.
 *
 * @param identity the identity captured when the file was opened
 * @param coveredBytes the indexed bytes, or the end of the region an indexing operation in progress
 *     covers
 * @param tailModifiedMillis the modification time when {@code tailDigest} was taken, or -1 when no
 *     digest was taken yet
 * @param tailDigest CRC32C of the last {@link ChangeDetector#TAIL_BYTES} indexed bytes
 */
public record Baseline(
    FileIdentity identity, long coveredBytes, long tailModifiedMillis, long tailDigest) {}
