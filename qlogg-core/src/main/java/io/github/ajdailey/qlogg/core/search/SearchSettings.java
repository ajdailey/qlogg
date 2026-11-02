package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.util.Checks;

/**
 * Search engine configuration.
 *
 * @param chunkLines most lines in a chunk, at least 1
 * @param chunkBytes most bytes in a chunk and the size of a pooled read buffer, at least 1; a
 *     longer line is a chunk of its own in a buffer sized to it
 * @param maxChunksInFlight chunks read but not yet merged, at least 1
 * @param maxResults the result count that stops a search, or 0 for no limit
 * @param budget read buffers reserve from its {@code IO_BUFFERS} share
 */
public record SearchSettings(
    int chunkLines, int chunkBytes, int maxChunksInFlight, long maxResults, MemoryBudget budget) {
  /** Default lines per chunk. */
  public static final int DEFAULT_CHUNK_LINES = 10_000;

  /** Default bytes per chunk: 4 MiB. */
  public static final int DEFAULT_CHUNK_BYTES = 4 * 1024 * 1024;

  /** Checks the components. */
  public SearchSettings {
    Checks.atLeast(chunkLines, 1, "chunkLines");
    Checks.atLeast(chunkBytes, 1, "chunkBytes");
    Checks.atLeast(maxChunksInFlight, 1, "maxChunksInFlight");
    Checks.notNegative(maxResults, "maxResults");
    if (budget == null) {
      throw new IllegalArgumentException("budget is required");
    }
  }

  /**
   * Returns the defaults: 10,000 lines or 4 MiB per chunk, three chunks in flight per CPU thread,
   * no result limit.
   *
   * @param cpuThreads the CPU pool size
   * @param budget the memory budget
   * @return the settings
   */
  public static SearchSettings defaults(int cpuThreads, MemoryBudget budget) {
    return new SearchSettings(
        DEFAULT_CHUNK_LINES, DEFAULT_CHUNK_BYTES, 3 * Math.max(1, cpuThreads), 0, budget);
  }

  /**
   * Returns a copy with another result limit.
   *
   * @param limit the result count that stops a search, or 0
   * @return the copy
   */
  public SearchSettings withMaxResults(long limit) {
    return new SearchSettings(chunkLines, chunkBytes, maxChunksInFlight, limit, budget);
  }
}
