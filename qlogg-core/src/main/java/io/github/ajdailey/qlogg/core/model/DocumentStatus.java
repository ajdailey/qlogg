package io.github.ajdailey.qlogg.core.model;

/**
 * State of a document at one moment.
 *
 * @param lineCount lines in the published index
 * @param sizeBytes end of the region the current or last indexing operation covers
 * @param indexPercent indexed share of that region, 0-100
 * @param indexing true while an indexing operation runs
 * @param sparseIndex true when the index uses sparse storage
 * @param closed true after {@link LogDocument#close}
 * @param fileBytes the file's size at the last check
 * @param modifiedMillis the file's modification time at the last check
 * @param missing true while no file is at the path
 * @param loadingStopped true after {@link LogDocument#stopIndexing}
 * @param reopenWaiting true while a failed reopen waits for the next check ({@link
 *     LogDocument#reopenFailed})
 */
public record DocumentStatus(
    long lineCount,
    long sizeBytes,
    int indexPercent,
    boolean indexing,
    boolean sparseIndex,
    boolean closed,
    long fileBytes,
    long modifiedMillis,
    boolean missing,
    boolean loadingStopped,
    boolean reopenWaiting) {}
