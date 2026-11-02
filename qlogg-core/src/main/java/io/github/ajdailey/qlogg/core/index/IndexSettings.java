package io.github.ajdailey.qlogg.core.index;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.util.Checks;

/**
 * Indexer configuration.
 *
 * @param blockBytes bytes per read, a positive multiple of 8 so no UTF-16 or UTF-32 unit straddles
 *     two blocks
 * @param maxBlocksInFlight blocks read but not yet appended, at least 1
 * @param budget read buffers reserve from its {@code IO_BUFFERS} share
 */
public record IndexSettings(int blockBytes, int maxBlocksInFlight, MemoryBudget budget) {
  /** Default read size: 4 MiB. */
  public static final int DEFAULT_BLOCK_BYTES = 4 * 1024 * 1024;

  /** Checks the components. */
  public IndexSettings {
    if (blockBytes < 8 || blockBytes % 8 != 0) {
      throw new IllegalArgumentException("blockBytes must be a positive multiple of 8");
    }
    Checks.atLeast(maxBlocksInFlight, 1, "maxBlocksInFlight");
  }

  /**
   * Returns the defaults: 4 MiB blocks, two blocks in flight per CPU thread.
   *
   * @param cpuThreads the CPU pool size
   * @param budget the memory budget
   * @return the settings
   */
  public static IndexSettings defaults(int cpuThreads, MemoryBudget budget) {
    return new IndexSettings(DEFAULT_BLOCK_BYTES, 2 * Math.max(1, cpuThreads), budget);
  }
}
