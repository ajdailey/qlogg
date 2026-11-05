package io.github.ajdailey.qlogg.core.time;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import java.io.IOException;

/**
 * Finds the line for a time. Both methods return the first stamp equal to the target, else the last
 * stamp before it, else the first stamp; null when the region has no stamp.
 */
public final class TimeSearch {
  private TimeSearch() {}

  /**
   * Bisects byte offsets in {@code [0, end)}, about {@code log2(end)} {@link TimeReader#next}
   * probes. The result is exact when stamps do not decrease in file order; run {@link TimeSamples}
   * first and use {@link #locateLinear} when it reports descents.
   *
   * @param reader the document's reader
   * @param end exclusive end, normally the snapshot's {@code indexedBytes()}
   * @param target epoch nanoseconds
   * @param token checked before each probe
   * @return the stamp, or null
   * @throws IOException on a read error
   * @throws java.util.concurrent.CancellationException once the token is canceled
   */
  public static Stamp locate(TimeReader reader, long end, long target, CancellationToken token)
      throws IOException {
    // Stamps on lines starting before lo are under the target; the first stamp at or after hi is
    // at or over it, or there is none.
    long lo = 0;
    long hi = end;
    Stamp below = null;
    while (lo < hi) {
      token.check();
      long mid = lo + (hi - lo) / 2;
      Stamp probe = reader.next(mid, end, token);
      if (probe == null || probe.offset() >= hi || probe.nanos() >= target) {
        hi = mid;
      } else {
        below = probe;
        lo = probe.offset() + 1;
      }
    }
    // lo is one past below, so first is the stamp after below.
    Stamp first = reader.next(lo, end, token);
    if (first != null && first.nanos() == target) {
      return first;
    }
    return below != null ? below : first;
  }

  /**
   * Scans every line in file order, reading the source directly rather than through the cache, and
   * stops at the first stamp at or after the target: that stamp when it equals the target or is the
   * first, else the stamp before it. With no stamp at or after the target, the last stamp.
   *
   * @param reader the document's reader
   * @param end exclusive end
   * @param target epoch nanoseconds
   * @param token checked between lines
   * @param progress called every {@link TimeReader#PROGRESS_BYTES} and at the end
   * @return the stamp, or null
   * @throws IOException on a read error
   * @throws java.util.concurrent.CancellationException once the token is canceled
   */
  public static Stamp locateLinear(
      TimeReader reader, long end, long target, CancellationToken token, ScanProgress progress)
      throws IOException {
    return reader.linear(end, target, token, progress);
  }
}
