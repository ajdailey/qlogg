package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.io.IOException;

/**
 * Receives document events on worker threads. Implementations return quickly and never block toward
 * the UI thread.
 */
public interface DocumentListener {
  /**
   * Called after a new index snapshot is published.
   *
   * @param progress the published state
   */
  void indexProgressed(IndexProgress progress);

  /**
   * Called once per indexing operation, after its last progress call.
   *
   * @param outcome how it ended
   * @param failure the cause when {@code FAILED}, otherwise null
   */
  void indexFinished(OperationOutcome outcome, IOException failure);

  /**
   * Called on the watch's thread when a check found a change: {@code UNCHANGED} when only the size
   * or modification time moved, {@code MISSING} once per disappearance, and {@code TRUNCATED},
   * {@code REPLACED} or {@code MODIFIED} once, after which the document reports nothing more.
   *
   * @param change the classification
   */
  void fileChanged(FileChange change);

  /** Called once, after the document's file is closed. */
  void closed();
}
