package io.github.ajdailey.qlogg.core.search;

/** Matches lines against one query. Not thread-safe: one instance per worker thread. */
public interface LineMatcher {
  /**
   * Matches one line.
   *
   * @param line the line, terminator excluded
   * @return true when the line is a result
   * @throws java.util.concurrent.CancellationException when the search is canceled during a regex
   *     match
   */
  boolean matches(LineText line);
}
