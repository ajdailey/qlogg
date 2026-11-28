package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;

/** Builds a {@link FilteredView}'s marks snapshot ({@link FilteredView.Extras}) on a worker. */
public final class MarkSnapshots {
  private static final int BATCH = 4096;

  private MarkSnapshots() {}

  /**
   * Collects the marks in a domain that are not matches. Thread-safe; runs on a worker.
   *
   * @param matches the lines left out ({@link FilteredView#extrasBasis})
   * @param marks the marks
   * @param domain the lines kept marks must be in, or null for every line
   * @param budget reserved from for the snapshot, or null
   * @param token checked per batch of {@value #BATCH} marks
   * @return the snapshot
   * @throws java.util.concurrent.CancellationException once the token is canceled; nothing stays
   *     reserved
   */
  public static FilteredView.Extras build(
      LineSet matches,
      MutableLineSet marks,
      LineSet domain,
      MemoryBudget budget,
      CancellationToken token) {
    long version = marks.version();
    long basisSize = matches.size();
    long domainSize = domain == null ? -1 : domain.size();
    MutableLineSet lines = new MutableLineSet(budget);
    long[] batch = new long[BATCH];
    long[] kept = new long[BATCH];
    long from = 0;
    boolean complete = true;
    boolean built = false;
    try {
      while (true) {
        token.check();
        int n = marks.fill(from, batch);
        int k = 0;
        for (int i = 0; i < n; i++) {
          if (!matches.contains(batch[i]) && (domain == null || domain.contains(batch[i]))) {
            kept[k++] = batch[i];
          }
        }
        if (!lines.addSorted(kept, k)) {
          complete = false;
          break;
        }
        if (n < BATCH || batch[n - 1] == Long.MAX_VALUE) {
          break;
        }
        from = batch[n - 1] + 1;
      }
      built = true;
    } finally {
      if (!built) {
        lines.clear();
      }
    }
    return new FilteredView.Extras(lines, matches, basisSize, version, complete, domainSize);
  }
}
