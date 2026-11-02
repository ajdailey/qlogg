package io.github.ajdailey.qlogg.core.index;

import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import java.io.IOException;

/**
 * Receives indexing events on the indexing thread. Implementations return quickly and never block
 * toward the UI thread.
 */
public interface IndexListener {
  /**
   * Called after a new snapshot is published, at most every 100 ms and once at the end.
   *
   * @param progress the published state
   */
  void progressed(IndexProgress progress);

  /**
   * Called once, last.
   *
   * @param outcome how the operation ended
   * @param failure the cause when {@code FAILED}, otherwise null
   */
  void finished(OperationOutcome outcome, IOException failure);
}
