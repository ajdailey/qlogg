package io.github.ajdailey.qlogg.core.index;

/**
 * Progress of an indexing operation.
 *
 * @param indexedBytes end of the published region
 * @param totalBytes end of the region this operation indexes
 * @param lineCount lines in the published snapshot
 */
public record IndexProgress(long indexedBytes, long totalBytes, long lineCount) {}
