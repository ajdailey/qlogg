package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import java.nio.charset.Charset;

/**
 * Services shared by every document; one instance per process.
 *
 * @param workers the thread owner
 * @param indexer builds line indexes
 * @param search searches documents
 * @param cache random reads for display and sparse index lookups
 * @param budget line indexes and result sets reserve from it
 * @param fallbackCharset used when encoding detection matches no rule
 * @param heldLock the instance lock file every log path check rejects, or null when the process
 *     holds none; a copy of the services must carry it
 */
public record DocumentServices(
    Workers workers,
    Indexer indexer,
    SearchEngine search,
    BlockCache cache,
    MemoryBudget budget,
    Charset fallbackCharset,
    SafePaths.HeldLock heldLock) {
  /**
   * Creates services for a process that holds no instance lock: tests and benchmarks. Main code
   * passes the lock, or copies existing services with a {@code with} method.
   *
   * @param workers the thread owner
   * @param indexer builds line indexes
   * @param search searches documents
   * @param cache random reads for display and sparse index lookups
   * @param budget line indexes and result sets reserve from it
   * @param fallbackCharset used when encoding detection matches no rule
   */
  public DocumentServices(
      Workers workers,
      Indexer indexer,
      SearchEngine search,
      BlockCache cache,
      MemoryBudget budget,
      Charset fallbackCharset) {
    this(workers, indexer, search, cache, budget, fallbackCharset, null);
  }

  /**
   * Returns these services with another fallback charset, the held lock kept.
   *
   * @param charset the new fallback
   * @return the copy
   */
  public DocumentServices withFallbackCharset(Charset charset) {
    return new DocumentServices(workers, indexer, search, cache, budget, charset, heldLock);
  }
}
