package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import java.util.Arrays;

/**
 * The lines of a match set widened by context: each match plus {@code before} lines above it and
 * {@code after} lines below it, overlapping windows merged, cut to the document's line count. The
 * lines are derived from the matches, not stored.
 *
 * <p>Match {@code j} adds {@code min(before + after + 1, m(j) - m(j - 1))} new lines, the tail of
 * its window, so the count of lines covered by the first {@code k} matches is a prefix sum over the
 * gaps. A checkpoint holds that sum every {@value #STEP} matches, 8 bytes each, charged to {@link
 * MemoryShare#RESULTS}; {@link #rank} and {@link #select} start at the nearest checkpoint and walk
 * at most {@value #STEP} matches. Each instance covers the first {@link #covered} matches as one
 * build saw them; {@link #extend} builds the next instance on a worker from where this one stopped,
 * sharing the checkpoint array, so matches a running search adds appear one build later. A build
 * continues from the previous one when the set only grew above the last covered match, as a
 * search's results do, and starts over otherwise.
 *
 * <p>Immutable once built; thread-safe over a thread-safe match set. The line count is read live,
 * so the last window extends as the document grows.
 */
public final class ContextRows implements LineSet {
  /** Matches between checkpoints. */
  public static final int STEP = 64;

  /** Bytes charged per checkpoint. */
  public static final long CHECKPOINT_BYTES = 8;

  /** The document's current line count. */
  public interface LineCount {
    /**
     * Returns the line count.
     *
     * @return lines
     */
    long get();
  }

  private static final int BATCH = 4096;

  private final LineSet matches;
  private final int before;
  private final int after;
  private final LineCount lines;
  private final long[] checkpoints;
  private final long covered;
  private final long coveredLines;
  private final long lastMatch;
  private final long reserved;
  private final boolean complete;
  private final Object chain;

  private ContextRows(
      LineSet matches,
      int before,
      int after,
      LineCount lines,
      long[] checkpoints,
      long covered,
      long coveredLines,
      long lastMatch,
      long reserved,
      boolean complete,
      Object chain) {
    this.matches = matches;
    this.before = before;
    this.after = after;
    this.lines = lines;
    this.checkpoints = checkpoints;
    this.covered = covered;
    this.coveredLines = coveredLines;
    this.lastMatch = lastMatch;
    this.reserved = reserved;
    this.complete = complete;
    this.chain = chain;
  }

  /**
   * Returns rows that cover no match yet; {@link #extend} builds the first real instance.
   *
   * @param matches the match set
   * @param before lines above each match, at least 0
   * @param after lines below each match, at least 0
   * @param lines the document's line count
   * @return the empty instance
   */
  public static ContextRows empty(LineSet matches, int before, int after, LineCount lines) {
    if (before < 0 || after < 0) {
      throw new IllegalArgumentException("context counts must not be negative");
    }
    return new ContextRows(
        matches, before, after, lines, new long[1], 0, 0, -1, 0, true, new Object());
  }

  /**
   * Returns the match set.
   *
   * @return the set
   */
  public LineSet matches() {
    return matches;
  }

  /**
   * Returns the lines shown above each match.
   *
   * @return the count
   */
  public int before() {
    return before;
  }

  /**
   * Returns the lines shown below each match.
   *
   * @return the count
   */
  public int after() {
    return after;
  }

  /**
   * Returns how many matches this instance covers.
   *
   * @return the count
   */
  public long covered() {
    return covered;
  }

  /**
   * Reports whether the budget held every checkpoint the build needed.
   *
   * @return false when the build stopped at a refusal, covering fewer matches than the set held
   */
  public boolean complete() {
    return complete;
  }

  /**
   * Reports whether the match set changed since this instance was built.
   *
   * @return true when {@link #extend} should run
   */
  public boolean stale() {
    return complete
        && (matches.size() != covered || covered > 0 && select(matches, covered - 1) != lastMatch);
  }

  /**
   * Returns the bytes charged for the checkpoints this instance holds.
   *
   * @return bytes
   */
  public long reservedBytes() {
    return reserved;
  }

  /**
   * Returns the bytes this instance charged beyond an older instance it may have been extended
   * from. Extending shares and grows the older instance's checkpoints; a build that started over
   * charged all of its own.
   *
   * @param older the instance {@link #extend} ran on
   * @return bytes to release when this instance is dropped while the older one stays
   */
  public long reservedBeyond(ContextRows older) {
    return chain == older.chain ? reserved - older.reserved : reserved;
  }

  /**
   * Reports whether this instance was extended from an older one, sharing its checkpoints and its
   * reservation.
   *
   * @param older an older instance
   * @return true when releasing this instance's {@link #reservedBytes} also covers the older one
   */
  public boolean continues(ContextRows older) {
    return chain == older.chain;
  }

  /**
   * Builds the instance covering every match the set holds now. When the set still starts with the
   * matches this instance covers, the build continues from them and shares the checkpoints;
   * otherwise it starts over. Runs on a worker. On a refusal the result covers the matches up to
   * the last checkpoint the budget held and is not {@link #complete}.
   *
   * @param budget charged for new checkpoints, or null
   * @param token checked per batch of matches
   * @return the new instance; this one stays valid for readers until its owner releases it
   * @throws java.util.concurrent.CancellationException once the token is canceled; nothing new
   *     stays reserved
   */
  public ContextRows extend(MemoryBudget budget, CancellationToken token) {
    long target = matches.size();
    boolean prefix =
        covered <= target && (covered == 0 || select(matches, covered - 1) == lastMatch);
    long[] points = prefix ? checkpoints : new long[1];
    Object start = prefix ? chain : new Object();
    long count = prefix ? covered : 0;
    long sum = prefix ? coveredLines : 0;
    long previous = prefix ? lastMatch : -1;
    long charged = prefix ? reserved : 0;
    long fresh = 0;
    boolean finished = false;
    boolean held = true;
    long[] batch = new long[BATCH];
    try {
      long from = previous + 1;
      while (count < target) {
        token.check();
        int n = fill(matches, from, batch);
        if (n == 0) {
          break;
        }
        for (int i = 0; i < n && count < target; i++) {
          if (count % STEP == 0) {
            int slot = (int) (count / STEP);
            if (slot >= points.length) {
              int grown = Math.max(slot + 1, points.length + points.length / 2);
              long cost = (grown - points.length) * CHECKPOINT_BYTES;
              if (budget != null && !budget.reserve(MemoryShare.RESULTS, cost)) {
                held = false;
                break;
              }
              fresh += cost;
              points = Arrays.copyOf(points, grown);
            }
            points[slot] = sum;
          }
          sum += added(previous, batch[i]);
          previous = batch[i];
          count++;
        }
        if (!held) {
          break;
        }
        from = batch[n - 1] + 1;
      }
      finished = true;
    } finally {
      if (!finished && budget != null && fresh > 0) {
        budget.release(MemoryShare.RESULTS, fresh);
      }
    }
    return new ContextRows(
        matches, before, after, lines, points, count, sum, previous, charged + fresh, held, start);
  }

  /**
   * Reports whether a line shows as context: listed here but not a match.
   *
   * @param line a 0-based line
   * @return true for a context line
   */
  public boolean context(long line) {
    return contains(line) && !matches.contains(line);
  }

  @Override
  public long size() {
    return rank(lines.get());
  }

  @Override
  public boolean contains(long line) {
    if (line < 0 || line >= lines.get() || covered == 0) {
      return false;
    }
    long m = matches.ceiling(Math.max(0, line - after));
    return m >= 0 && m <= lastMatch && m <= line + before;
  }

  @Override
  public long rank(long line) {
    long x = Math.min(line, lines.get());
    if (x <= 0 || covered == 0) {
      return 0;
    }
    long k = Math.min(matches.rank(saturatedAdd(x, before)), covered);
    if (k == 0) {
      return 0;
    }
    long[] walk = prefix(k);
    return walk[0] - Math.max(0, walk[1] + after + 1 - x);
  }

  @Override
  public long select(long index) {
    long count = lines.get();
    if (index < 0 || covered == 0) {
      return -1;
    }
    int lo = 0;
    int hi = (int) ((covered - 1) / STEP);
    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1;
      if (checkpoints[mid] <= index) {
        lo = mid;
      } else {
        hi = mid - 1;
      }
    }
    long j = (long) lo * STEP;
    long sum = checkpoints[lo];
    long previous = j == 0 ? -1 : select(matches, j - 1);
    long m = select(matches, j);
    while (j < covered && m >= 0) {
      long add = added(previous, m);
      if (sum + add > index) {
        long line = m + after - add + 1 + (index - sum);
        return line < count ? line : -1;
      }
      sum += add;
      previous = m;
      j++;
      m = j < covered ? matches.ceiling(m + 1) : -1;
    }
    return -1;
  }

  @Override
  public long ceiling(long line) {
    long count = lines.get();
    long x = Math.max(0, line);
    if (x >= count || covered == 0) {
      return -1;
    }
    long m = matches.ceiling(Math.max(0, x - after));
    if (m < 0 || m > lastMatch) {
      return -1;
    }
    long found = Math.max(x, m - before);
    return found < count ? found : -1;
  }

  @Override
  public long floor(long line) {
    long x = Math.min(line, lines.get() - 1);
    if (x < 0 || covered == 0) {
      return -1;
    }
    long m = matches.floor(Math.min(saturatedAdd(x, before), lastMatch));
    if (m < 0) {
      return -1;
    }
    return Math.min(x, m + after);
  }

  /** Lines covered by the first k matches, and the k-th match, as {sum, line}. */
  private long[] prefix(long k) {
    long j = (k - 1) / STEP * STEP;
    long sum = checkpoints[(int) (j / STEP)];
    long previous = j == 0 ? -1 : select(matches, j - 1);
    long m = select(matches, j);
    while (m >= 0) {
      sum += added(previous, m);
      previous = m;
      j++;
      if (j >= k) {
        break;
      }
      m = matches.ceiling(m + 1);
    }
    return new long[] {sum, previous};
  }

  /** New lines a match adds after the previous one, or after none for -1. */
  private long added(long previous, long match) {
    long width = (long) before + after + 1;
    if (previous < 0) {
      return after + 1 + Math.min(match, before);
    }
    return Math.min(width, match - previous);
  }

  /** The index-th line, or -1 when the set holds fewer lines. */
  private static long select(LineSet set, long index) {
    return index >= 0 && index < set.size() ? set.select(index) : -1;
  }

  /** Lines at or after a line, ascending. */
  private static int fill(LineSet set, long from, long[] out) {
    if (set instanceof MutableLineSet mutable) {
      return mutable.fill(from, out);
    }
    int n = 0;
    long line = set.ceiling(from);
    while (line >= 0 && n < out.length) {
      out[n++] = line;
      line = set.ceiling(line + 1);
    }
    return n;
  }

  private static long saturatedAdd(long a, long b) {
    long sum = a + b;
    return sum < a ? Long.MAX_VALUE : sum;
  }
}
