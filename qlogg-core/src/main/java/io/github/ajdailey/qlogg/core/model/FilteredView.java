package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;

/**
 * The filtered view's rows: the matching lines, the marked lines or both, in line order. Row n maps
 * to a document line through rank and select on the sets, so nothing is copied per row.
 *
 * <p>With context lines set ({@link #setContext}) the matches are listed through {@link
 * ContextRows}: each match with the lines around it. Marks mode lists no context. {@link #matches}
 * stays the match set; {@link #rows} is what the view lists for it. The installed {@link
 * ContextRows} covers the matches as its build saw them: {@link #contextStale} reports a newer
 * match set, {@link ContextRows#extend} builds the next one on a worker and {@link #installContext}
 * puts it in place. The view releases the checkpoints' reservation when it replaces or drops them.
 *
 * <p>With {@link Visibility#MARKS_AND_MATCHES} the rows are the matches plus an {@link Extras}
 * snapshot: the marks that were not matches when it was built, held in a {@link MutableLineSet}
 * that reserves its storage from {@link MemoryShare#RESULTS}. {@link #buildExtras} walks the marks
 * on a worker; {@link #installExtras} puts the result in place on the view's thread and {@link
 * #extrasStale} reports when the marks or the matches changed since. Until a newer snapshot is
 * installed the rows keep the old one, so marks toggled meanwhile appear one build later, and a
 * mark the matches gained meanwhile counts twice in {@link #size}. A snapshot the share cannot hold
 * is partial ({@link Extras#complete} false). {@link #close} releases it.
 *
 * <p>Confined to one thread (the UI thread); the sets it reads are thread-safe. {@link #cursor}
 * hands a worker a thread-safe walk over the same rows.
 */
public final class FilteredView {
  /**
   * The marks outside the matches, as one build saw them.
   *
   * @param lines the marks that were not matches, charged to {@code RESULTS}
   * @param basis the matching lines the build compared against
   * @param basisSize their count then
   * @param marksVersion the marks' {@link MutableLineSet#version} when the build started
   * @param complete false when the share refused part of the lines
   */
  public record Extras(
      MutableLineSet lines, LineSet basis, long basisSize, long marksVersion, boolean complete) {}

  private static final int BATCH = 4096;

  private final MutableLineSet marks;
  private final MemoryBudget budget;
  private LineSet matches;
  private Visibility visibility = Visibility.MARKS_AND_MATCHES;
  private Extras extras;
  private ContextRows context;
  private ContextRows.LineCount lineCount;

  /**
   * Creates the view; it lists no marks with the matches until a snapshot is installed.
   *
   * @param matches the matching lines
   * @param marks the document's marks
   * @param budget charged for the marks outside the matches, or null
   */
  public FilteredView(LineSet matches, MutableLineSet marks, MemoryBudget budget) {
    this.matches = matches;
    this.marks = marks;
    this.budget = budget;
    this.extras = new Extras(new MutableLineSet(budget), null, -1, -1, true);
  }

  /**
   * Returns the document's marks.
   *
   * @return the set
   */
  public MutableLineSet marks() {
    return marks;
  }

  /**
   * Returns the budget snapshots reserve from.
   *
   * @return the budget, or null
   */
  public MemoryBudget budget() {
    return budget;
  }

  /**
   * Returns the matching lines the view lists.
   *
   * @return the set
   */
  public LineSet matches() {
    return matches;
  }

  /**
   * Replaces the matching lines, as a new search or another kept result set does.
   *
   * @param replacement the set
   */
  public void setMatches(LineSet replacement) {
    this.matches = replacement;
    if (context != null && context.matches() != replacement) {
      setContext(context.before(), context.after(), lineCount);
    }
  }

  // Context lines.

  /**
   * Lists each match with lines around it, or alone for 0 and 0. The rows cover no match until a
   * built {@link ContextRows} is installed.
   *
   * @param before lines above each match
   * @param after lines below each match
   * @param lines the document's line count
   */
  public void setContext(int before, int after, ContextRows.LineCount lines) {
    if (context != null
        && context.matches() == matches
        && context.before() == before
        && context.after() == after) {
      return;
    }
    releaseContext();
    lineCount = lines;
    context = before == 0 && after == 0 ? null : ContextRows.empty(matches, before, after, lines);
  }

  /**
   * Returns the lines listed above each match.
   *
   * @return the count, 0 without context
   */
  public int contextBefore() {
    return context == null ? 0 : context.before();
  }

  /**
   * Returns the lines listed below each match.
   *
   * @return the count, 0 without context
   */
  public int contextAfter() {
    return context == null ? 0 : context.after();
  }

  /**
   * Returns the installed context rows.
   *
   * @return the rows, or null without context
   */
  public ContextRows context() {
    return context;
  }

  /**
   * Returns the lines listed for the matches: the matches, or the matches with their context.
   *
   * @return the set
   */
  public LineSet rows() {
    return context == null ? matches : context;
  }

  /**
   * Reports whether the matches changed since the installed context rows were built.
   *
   * @return true when {@link ContextRows#extend} should run
   */
  public boolean contextStale() {
    return context != null && context.stale();
  }

  /**
   * Installs context rows built from the installed ones. A build from rows that are no longer
   * installed is dropped and its own reservation released.
   *
   * @param built the result of {@link ContextRows#extend}
   * @param from the rows it was built from
   * @return false when it was dropped
   */
  public boolean installContext(ContextRows built, ContextRows from) {
    if (context != from) {
      release(built.reservedBeyond(from));
      return false;
    }
    if (!built.continues(from)) {
      release(from.reservedBytes());
    }
    context = built;
    return true;
  }

  /**
   * Reports whether a line is listed as context of a match: in the context rows, not a match.
   *
   * @param line a 0-based line
   * @return true for a context line
   */
  public boolean isContext(long line) {
    return context != null && visibility != Visibility.MARKS && context.context(line);
  }

  /**
   * Reports whether a listed line starts a group: context is on and the row above it is not the
   * line before it.
   *
   * @param line a listed 0-based line
   * @return true when a gap lies above the line's row
   */
  public boolean startsGroup(long line) {
    if (context == null || visibility == Visibility.MARKS) {
      return false;
    }
    long previous = cursor().previous(line);
    return previous >= 0 && previous != line - 1;
  }

  private void releaseContext() {
    if (context != null) {
      release(context.reservedBytes());
      context = null;
    }
  }

  private void release(long bytes) {
    if (budget != null && bytes > 0) {
      budget.release(MemoryShare.RESULTS, bytes);
    }
  }

  /**
   * Returns the mode.
   *
   * @return the mode
   */
  public Visibility visibility() {
    return visibility;
  }

  /**
   * Sets the mode.
   *
   * @param mode the mode
   */
  public void setVisibility(Visibility mode) {
    this.visibility = mode;
  }

  /**
   * Counts the rows.
   *
   * @return rows
   */
  public long size() {
    return switch (visibility) {
      case MATCHES -> rows().size();
      case MARKS -> marks.size();
      case MARKS_AND_MATCHES -> rows().size() + extras.lines().size();
    };
  }

  /**
   * Maps a row to its document line.
   *
   * @param row a 0-based row
   * @return the 0-based line, or -1 when the row is outside the view
   */
  public long sourceLine(long row) {
    if (row < 0) {
      return -1;
    }
    switch (visibility) {
      case MATCHES:
        return selectOrNone(rows(), row);
      case MARKS:
        return selectOrNone(marks, row);
      default:
        break;
    }
    MutableLineSet extra = extras.lines();
    LineSet listed = rows();
    long matchCount = listed.size();
    long extraCount = extra.size();
    if (row >= matchCount + extraCount) {
      return -1;
    }
    long hi =
        Math.max(lastOf(listed, matchCount), extraCount == 0 ? -1 : extra.select(extraCount - 1));
    long lo = 0;
    while (lo < hi) {
      long mid = lo + (hi - lo) / 2;
      if (unionRank(mid + 1, extra) > row) {
        hi = mid;
      } else {
        lo = mid + 1;
      }
    }
    return lo;
  }

  /**
   * Returns the row of a line, or of the nearest listed line after it.
   *
   * @param line a 0-based line
   * @return the count of listed lines before the line; {@link #size} when none follows
   */
  public long rowOf(long line) {
    return switch (visibility) {
      case MATCHES -> rows().rank(line);
      case MARKS -> marks.rank(line);
      case MARKS_AND_MATCHES -> unionRank(line, extras.lines());
    };
  }

  /**
   * Reports whether the view lists a line.
   *
   * @param line a 0-based line
   * @return true when a row maps to it
   */
  public boolean lists(long line) {
    return switch (visibility) {
      case MATCHES -> rows().contains(line);
      case MARKS -> marks.contains(line);
      case MARKS_AND_MATCHES -> rows().contains(line) || extras.lines().contains(line);
    };
  }

  /**
   * Maps consecutive rows to their lines.
   *
   * @param firstRow the first row
   * @param count rows wanted, at most {@code out.length}
   * @param out receives the lines
   * @return rows filled; fewer at the end of the view
   */
  public int sourceLines(long firstRow, int count, long[] out) {
    long line = sourceLine(firstRow);
    RowCursor walk = cursor();
    int n = 0;
    while (n < count && line >= 0) {
      out[n++] = line;
      line = walk.next(line);
    }
    return n;
  }

  /**
   * Returns a thread-safe walk over the rows in the current mode. It reads the sets live, so lines
   * a running search adds later appear in it.
   *
   * @return the cursor
   */
  public RowCursor cursor() {
    return switch (visibility) {
      case MATCHES -> RowCursor.of(rows(), null);
      case MARKS -> RowCursor.of(marks, null);
      case MARKS_AND_MATCHES -> RowCursor.of(rows(), extras.lines());
    };
  }

  /**
   * Returns the heap the view holds itself: the installed snapshot's storage.
   *
   * @return bytes
   */
  public long retainedBytes() {
    return extras.lines().retainedBytes() + (context == null ? 0 : context.reservedBytes());
  }

  /**
   * Reports whether the marks or the matches changed since the installed snapshot was built, in the
   * mode that uses it.
   *
   * @return true when {@link #buildExtras} should run again
   */
  public boolean extrasStale() {
    return visibility == Visibility.MARKS_AND_MATCHES
        && (extras.basis() != rows()
            || extras.basisSize() != rows().size()
            || extras.marksVersion() != marks.version());
  }

  /**
   * Installs a snapshot and releases the one before. A snapshot built for another matching set is
   * released and dropped.
   *
   * @param next from {@link #buildExtras}
   * @return false when it was dropped
   */
  public boolean installExtras(Extras next) {
    if (next.basis() != rows()) {
      next.lines().clear();
      return false;
    }
    extras.lines().clear();
    extras = next;
    return true;
  }

  /**
   * Collects the marks that are not matches. Thread-safe; runs on a worker.
   *
   * @param matches the matching lines
   * @param marks the marks
   * @param budget reserved from for the snapshot, or null
   * @param token checked per batch of {@value #BATCH} marks
   * @return the snapshot
   * @throws java.util.concurrent.CancellationException once the token is canceled; nothing stays
   *     reserved
   */
  public static Extras buildExtras(
      LineSet matches, MutableLineSet marks, MemoryBudget budget, CancellationToken token) {
    long version = marks.version();
    long basisSize = matches.size();
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
          if (!matches.contains(batch[i])) {
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
    return new Extras(lines, matches, basisSize, version, complete);
  }

  /**
   * Reports whether the installed snapshot holds every mark outside the matches it saw.
   *
   * @return false after the share refused part of it
   */
  public boolean extrasComplete() {
    return extras.complete();
  }

  /**
   * Releases the snapshot and the context rows; the view lists no marks with the matches until the
   * next snapshot, and no context until {@link #setContext}.
   */
  public void close() {
    releaseContext();
    extras.lines().clear();
    extras = new Extras(new MutableLineSet(budget), null, -1, -1, true);
  }

  private long unionRank(long line, LineSet extra) {
    return rows().rank(line) + extra.rank(line);
  }

  private static long selectOrNone(LineSet set, long row) {
    return row < set.size() ? set.select(row) : -1;
  }

  private static long lastOf(LineSet set, long size) {
    return size == 0 ? -1 : set.select(size - 1);
  }
}
