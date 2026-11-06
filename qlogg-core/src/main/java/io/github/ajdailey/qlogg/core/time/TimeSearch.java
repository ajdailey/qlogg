package io.github.ajdailey.qlogg.core.time;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import java.io.IOException;

/**
 * Finds the line for a time. Both searches return the first stamp equal to the target, else the
 * last stamp before it, else the first stamp; null when the region has no stamp.
 */
public final class TimeSearch {
  /** Largest step back between two stamps that {@link #locate} accepts as local jitter. */
  public static final long JITTER_NANOS = 1_000_000_000L;

  /** Bytes before the end where {@link #locate} reads the upper stamp of its first bracket. */
  private static final long TAIL_BYTES = TimeReader.LOOKUP_BYTES;

  private static final Bisection DISORDERED = new Bisection(null, false);

  /**
   * The outcome of a bisection.
   *
   * @param stamp the stamp; null with no stamp, or when {@code consistent} is false
   * @param consistent false when a probe contradicted its bracket: use {@link #locateLinear}
   */
  public record Bisection(Stamp stamp, boolean consistent) {}

  private TimeSearch() {}

  /**
   * Bisects byte offsets in {@code [0, end)}: about {@code log2(end)} {@link TimeReader#next}
   * probes, plus two for the first bracket. The result is exact when stamps do not decrease in file
   * order. The bracket starts as the region's first stamp and a stamp in its last {@link
   * TimeReader#LOOKUP_BYTES}, and narrows to the nearest stamps read under and at or over the
   * target. A probe more than {@link #JITTER_NANOS} outside it ends the search as inconsistent. So
   * does a probe stopped by the {@link TimeReader#next} bound once any stamp has been read; with
   * none read, it counts as at or after the target, so a region without stamps costs no scan.
   * Disorder that no probe touches goes unseen: run {@link TimeSamples} first and use {@link
   * #locateLinear} when it reports descents.
   *
   * @param reader the document's reader
   * @param end exclusive end, normally the snapshot's {@code indexedBytes()}
   * @param target epoch nanoseconds
   * @param token checked before each probe
   * @return the outcome
   * @throws IOException on a read error
   * @throws java.util.concurrent.CancellationException once the token is canceled
   */
  public static Bisection locate(TimeReader reader, long end, long target, CancellationToken token)
      throws IOException {
    token.check();
    Stamp floor = reader.next(0, end, token);
    Stamp ceiling = reader.next(Math.max(0, end - TAIL_BYTES), end, token);
    // Stamps on lines starting before lo are under the target; the first stamp at or after hi is
    // at or over it, or there is none.
    long lo = 0;
    long hi = end;
    Stamp below = null;
    boolean hidden = false;
    while (lo < hi) {
      token.check();
      long mid = lo + (hi - lo) / 2;
      Stamp probe = reader.next(mid, end, token);
      hidden |= probe == null && reader.stoppedAtBound();
      if (outside(probe, floor, ceiling)
          || hidden && (probe != null || floor != null || ceiling != null)) {
        return DISORDERED;
      }
      if (probe == null || probe.offset() >= hi || probe.nanos() >= target) {
        hi = mid;
        if (probe != null && probe.nanos() >= target && startsBefore(probe, ceiling)) {
          ceiling = probe;
        }
      } else {
        below = probe;
        floor = probe;
        lo = probe.offset() + 1;
      }
    }
    // lo is one past below, so first is the stamp after below.
    Stamp first = reader.next(lo, end, token);
    if (outside(first, floor, ceiling) || first != null && steppedBack(first.nanos(), target)) {
      return DISORDERED;
    }
    boolean exact = first != null && first.nanos() == target;
    return new Bisection(exact || below == null ? first : below, true);
  }

  /** True when a stamp lies under the floor, or over a ceiling later in the file. */
  private static boolean outside(Stamp probe, Stamp floor, Stamp ceiling) {
    if (probe == null) {
      return false;
    }
    if (floor != null && steppedBack(probe.nanos(), floor.nanos())) {
      return true;
    }
    return ceiling != null
        && startsBefore(probe, ceiling)
        && steppedBack(ceiling.nanos(), probe.nanos());
  }

  /** True when there is no ceiling, or a stamp starts before it. */
  private static boolean startsBefore(Stamp probe, Stamp ceiling) {
    return ceiling == null || probe.offset() < ceiling.offset();
  }

  /** True when {@code later} is more than {@link #JITTER_NANOS} before {@code earlier}. */
  private static boolean steppedBack(long later, long earlier) {
    return later < earlier && Long.compareUnsigned(earlier - later, JITTER_NANOS) > 0;
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
