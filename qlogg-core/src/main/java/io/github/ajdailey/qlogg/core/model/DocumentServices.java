package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
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
 */
public record DocumentServices(
    Workers workers,
    Indexer indexer,
    SearchEngine search,
    BlockCache cache,
    MemoryBudget budget,
    Charset fallbackCharset) {}
