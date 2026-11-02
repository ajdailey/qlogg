package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import java.util.Arrays;

/**
 * The collapsed entries of one document and the mapping between document lines and the main view's
 * rows. A collapsed entry {@code [start, end)} shows its first line as one row and hides the rest.
 * Entries do not overlap. Each entry is charged {@link #ENTRY_BYTES} to {@link MemoryShare#FOLDS};
 * {@link #collapse} refuses an entry the share cannot hold.
 *
 * <p>The entries are sorted {@code long} arrays with a prefix sum of hidden lines, so {@link
 * #rowOf} and {@link #lineAt} are binary searches. A change replaces the arrays, so a {@link
 * #cursor} keeps the entries it was created with. Confined to one thread, except the cursors, which
 * are immutable.
 */
public final class FoldMap {
  /**
   * Heap charged per collapsed entry: start, end and hidden-lines prefix, one {@code long} each.
   */
  public static final int ENTRY_BYTES = 24;

  private static final long[] NONE = new long[0];

  private final MemoryBudget budget;
  private long[] starts = NONE;
  private long[] ends = NONE;
  private long[] hiddenBefore = NONE;
  private long hidden;

  /**
   * Creates an empty map.
   *
   * @param budget charged per collapsed entry
   */
  public FoldMap(MemoryBudget budget) {
    this.budget = budget;
  }

  /**
   * Counts the collapsed entries.
   *
   * @return entries
   */
  public int size() {
    return starts.length;
  }

  /**
   * Counts the lines collapsed entries hide.
   *
   * @return lines
   */
  public long hiddenLines() {
    return hidden;
  }

  /**
   * Returns the heap the entries hold.
   *
   * @return {@code size() * ENTRY_BYTES}
   */
  public long retainedBytes() {
    return (long) starts.length * ENTRY_BYTES;
  }

  /**
   * Collapses an entry. An entry of one line, or one that overlaps a collapsed entry, is refused.
   *
   * @param start the entry's first line
   * @param end the line after the entry
   * @return false when refused, also when {@link MemoryShare#FOLDS} cannot hold it
   */
  public boolean collapse(long start, long end) {
    if (start < 0 || end - start < 2) {
      return false;
    }
    int at = insertionPoint(start);
    if (at > 0 && ends[at - 1] > start || at < starts.length && starts[at] < end) {
      return false;
    }
    if (!budget.reserve(MemoryShare.FOLDS, ENTRY_BYTES)) {
      return false;
    }
    int n = starts.length;
    long[] s = new long[n + 1];
    long[] e = new long[n + 1];
    System.arraycopy(starts, 0, s, 0, at);
    System.arraycopy(ends, 0, e, 0, at);
    s[at] = start;
    e[at] = end;
    System.arraycopy(starts, at, s, at + 1, n - at);
    System.arraycopy(ends, at, e, at + 1, n - at);
    install(s, e);
    return true;
  }

  /**
   * Expands the collapsed entry starting at a line.
   *
   * @param start the entry's first line
   * @return false when no collapsed entry starts there
   */
  public boolean expand(long start) {
    int i = Arrays.binarySearch(starts, start);
    if (i < 0) {
      return false;
    }
    remove(i);
    return true;
  }

  /**
   * Expands the collapsed entry that hides a line.
   *
   * @param line a 0-based line
   * @return true when the line was hidden and its entry expanded
   */
  public boolean expandContaining(long line) {
    int i = entryOf(line);
    if (i < 0 || line == starts[i]) {
      return false;
    }
    remove(i);
    return true;
  }

  /** Expands every entry and returns their charge. */
  public void clear() {
    budget.release(MemoryShare.FOLDS, retainedBytes());
    install(NONE, NONE);
  }

  /**
   * Returns the collapsed entry holding a line.
   *
   * @param line a 0-based line
   * @return {@code [start, end)}, or null when the line is in no collapsed entry
   */
  public long[] collapsedEntry(long line) {
    int i = entryOf(line);
    return i < 0 ? null : new long[] {starts[i], ends[i]};
  }

  /**
   * Reports whether a line starts a collapsed entry.
   *
   * @param line a 0-based line
   * @return true when its row stands for hidden lines
   */
  public boolean isCollapsed(long line) {
    return Arrays.binarySearch(starts, line) >= 0;
  }

  /**
   * Returns the row showing a line; a hidden line maps to its entry's row.
   *
   * @param line a 0-based line
   * @return the 0-based row
   */
  public long rowOf(long line) {
    int i = insertionPoint(line + 1) - 1;
    if (i < 0) {
      return line;
    }
    if (line < ends[i]) {
      return starts[i] - hiddenBefore[i];
    }
    return line - hiddenBefore[i] - (ends[i] - starts[i] - 1);
  }

  /**
   * Returns the line a row shows.
   *
   * @param row a 0-based row
   * @return the 0-based line; for a collapsed entry, its first line
   */
  public long lineAt(long row) {
    int lo = 0;
    int hi = starts.length - 1;
    int found = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (starts[mid] - hiddenBefore[mid] <= row) {
        found = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    if (found < 0) {
      return row;
    }
    long entryRow = starts[found] - hiddenBefore[found];
    if (row == entryRow) {
      return starts[found];
    }
    return row + hiddenBefore[found] + ends[found] - starts[found] - 1;
  }

  /**
   * Returns a walk over the lines that have rows, below a line count. It keeps the entries of this
   * moment and is thread-safe.
   *
   * @param lineCount lines in the document
   * @return the cursor; {@link RowCursor#contiguous} only while nothing is collapsed
   */
  public RowCursor cursor(final long lineCount) {
    if (starts.length == 0) {
      return RowCursor.all(lineCount);
    }
    final long[] s = starts;
    final long[] e = ends;
    return new RowCursor() {
      @Override
      public long next(long line) {
        long next = Math.max(0, line + 1);
        int i = floor(s, next);
        if (i >= 0 && next > s[i] && next < e[i]) {
          next = e[i];
        }
        return next < lineCount ? next : -1;
      }

      @Override
      public long previous(long line) {
        long previous = Math.min(line, lineCount) - 1;
        if (previous < 0) {
          return -1;
        }
        int i = floor(s, previous);
        return i >= 0 && previous < e[i] ? s[i] : previous;
      }

      @Override
      public boolean contiguous() {
        return false;
      }
    };
  }

  /** Index of the entry holding a line, or -1. */
  private int entryOf(long line) {
    int i = floor(starts, line);
    return i >= 0 && line < ends[i] ? i : -1;
  }

  /** Index of the last start at or before a line, or -1. */
  private static int floor(long[] s, long line) {
    int i = Arrays.binarySearch(s, line);
    return i >= 0 ? i : -i - 2;
  }

  /** Index of the first start at or after a line. */
  private int insertionPoint(long line) {
    int i = Arrays.binarySearch(starts, line);
    return i >= 0 ? i : -i - 1;
  }

  private void remove(int i) {
    int n = starts.length;
    long[] s = new long[n - 1];
    long[] e = new long[n - 1];
    System.arraycopy(starts, 0, s, 0, i);
    System.arraycopy(ends, 0, e, 0, i);
    System.arraycopy(starts, i + 1, s, i, n - i - 1);
    System.arraycopy(ends, i + 1, e, i, n - i - 1);
    install(s, e);
    budget.release(MemoryShare.FOLDS, ENTRY_BYTES);
  }

  private void install(long[] s, long[] e) {
    long[] prefix = new long[s.length];
    long sum = 0;
    for (int i = 0; i < s.length; i++) {
      prefix[i] = sum;
      sum += e[i] - s[i] - 1;
    }
    starts = s;
    ends = e;
    hiddenBefore = prefix;
    hidden = sum;
  }
}
