package io.github.ajdailey.qlogg.core.folder;

import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.search.SearchLimit;

/**
 * Receives a folder search's events on its {@code qlogg-search-} thread. Implementations return
 * quickly and never block toward the UI thread.
 */
public interface FolderListener {
  /**
   * Called at most every 100 ms while the search runs, and once at the end before {@link
   * #finished}. The entries of {@link FolderResults} are current when it is called.
   *
   * @param progress the progress
   */
  void progressed(FolderProgress progress);

  /**
   * Called once, last.
   *
   * @param outcome {@code COMPLETED} also when the results share stopped it
   * @param limit {@code MEMORY} when the results share stopped it, else {@code NONE}
   * @param failure the cause when {@code FAILED}: an {@link java.io.IOException} for a folder that
   *     does not validate or cannot be walked, a {@link java.util.regex.PatternSyntaxException} for
   *     a regex that does not compile; otherwise null
   */
  void finished(OperationOutcome outcome, SearchLimit limit, Exception failure);

  /**
   * Called when a {@code RESULTS} reservation of the search is refused, before the search stops at
   * the share. The search retries the reservation after a true return.
   *
   * @return true after releasing {@code RESULTS} bytes held elsewhere, such as an older run's
   *     results; false to stop the search at the share
   */
  default boolean resultsRefused() {
    return false;
  }
}
