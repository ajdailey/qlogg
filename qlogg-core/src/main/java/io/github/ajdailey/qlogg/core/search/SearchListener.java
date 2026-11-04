package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;

/**
 * Receives search events on the search thread. Implementations return quickly and never block
 * toward the UI thread.
 */
public interface SearchListener {
  /**
   * Called after results are merged, at most every 100 ms, and once at the end.
   *
   * @param searchedLines lines of the range searched and merged so far
   * @param totalLines lines in the range
   * @param matchCount lines added to the results
   */
  void progressed(long searchedLines, long totalLines, long matchCount);

  /**
   * Called once, last.
   *
   * @param outcome how the search ended; {@code COMPLETED} also when a limit stopped it
   * @param limit the limit that stopped a completed search, else {@code NONE}
   * @param failure the cause when {@code FAILED}, otherwise null; a {@link
   *     java.util.regex.PatternSyntaxException} for a regex that does not compile, a {@link
   *     PatternTooComplexException} for one that overflows the stack on a line
   */
  void finished(OperationOutcome outcome, SearchLimit limit, Exception failure);
}
