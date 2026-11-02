package io.github.ajdailey.qlogg.core.search;

/** Why a search stopped before the end of its range. */
public enum SearchLimit {
  /** No limit stopped it: it covered its range, or was canceled or failed. */
  NONE,
  /** The result count reached {@link SearchSettings#maxResults}. */
  MAX_RESULTS,
  /** The {@code RESULTS} or {@code IO_BUFFERS} share could not hold the next chunk. */
  MEMORY
}
