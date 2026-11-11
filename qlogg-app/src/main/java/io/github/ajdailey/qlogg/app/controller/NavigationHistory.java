package io.github.ajdailey.qlogg.app.controller;

import java.util.Arrays;

/**
 * Go Back and Go Forward over one document's jumps: the 0-based lines the main view's caret left,
 * at most {@value #LIMIT}, oldest dropped first. A jump records the line it leaves unless that line
 * is the newest entry. A jump after Go Back drops the entries ahead of the current one.
 *
 * <p>The filtered view moves the main view on every caret move; those moves are not jumps. The
 * first one keeps the main view's line as a departure, and a click or Enter in the filtered view
 * records it ({@link #commit}). A main-view move of its own drops the departure.
 *
 * <p>Entries are lines, so appends leave them in place; {@link #clamp} pulls entries past a shorter
 * file's last line onto it. Confined to the UI thread.
 */
final class NavigationHistory {
  /** Entries kept. */
  static final int LIMIT = 100;

  private final long[] lines = new long[LIMIT];
  private int size;

  /** The entry the last move went to; {@code size} when at none. */
  private int at;

  private long departure = -1;

  /**
   * Records the line a jump leaves.
   *
   * @param line a 0-based line
   */
  void record(long line) {
    departure = -1;
    if (line < 0) {
      return;
    }
    size = at;
    if (size > 0 && lines[size - 1] == line) {
      at = size;
      return;
    }
    if (size == LIMIT) {
      System.arraycopy(lines, 1, lines, 0, LIMIT - 1);
      size--;
    }
    lines[size++] = line;
    at = size;
  }

  /**
   * Keeps the main view's line before the filtered view first moves it; later calls keep the first.
   *
   * @param line the main view's 0-based caret line
   */
  void depart(long line) {
    if (departure < 0) {
      departure = line;
    }
  }

  /** Records the kept departure, if any. */
  void commit() {
    if (departure >= 0) {
      record(departure);
    }
  }

  /** Drops the kept departure. */
  void stay() {
    departure = -1;
  }

  /**
   * Moves to the previous entry. From the newest end, records the current line first, so Go Forward
   * returns to it.
   *
   * @param current the main view's 0-based caret line
   * @return the line to go to, or -1 when there is none
   */
  long back(long current) {
    departure = -1;
    if (at == size) {
      record(current);
      at = size - 1;
    }
    if (at <= 0) {
      return -1;
    }
    at--;
    return lines[at];
  }

  /**
   * Moves to the next entry.
   *
   * @return the line to go to, or -1 when there is none
   */
  long forward() {
    departure = -1;
    if (at >= size - 1) {
      return -1;
    }
    at++;
    return lines[at];
  }

  /**
   * Reports whether {@link #back} has an entry.
   *
   * @return true when an entry precedes the current one
   */
  boolean canBack() {
    return at > 0;
  }

  /**
   * Reports whether {@link #forward} has an entry.
   *
   * @return true after Go Back with no jump since
   */
  boolean canForward() {
    return at < size - 1;
  }

  /**
   * Pulls entries past the last line onto it and merges neighbors that became equal.
   *
   * @param lineCount the document's lines once indexing ended; 0 leaves the entries
   */
  void clamp(long lineCount) {
    if (lineCount <= 0) {
      return;
    }
    long last = lineCount - 1;
    int kept = 0;
    int newAt = -1;
    for (int i = 0; i < size; i++) {
      long line = Math.min(lines[i], last);
      if (kept > 0 && lines[kept - 1] == line) {
        if (i == at) {
          newAt = kept - 1;
        }
        continue;
      }
      if (i == at) {
        newAt = kept;
      }
      lines[kept++] = line;
    }
    size = kept;
    at = newAt < 0 ? size : newAt;
  }

  /**
   * Copies another history's entries and position, for a reloaded document.
   *
   * @param other the old document's history
   */
  void takeOver(NavigationHistory other) {
    System.arraycopy(other.lines, 0, lines, 0, other.size);
    size = other.size;
    at = other.at;
    departure = -1;
  }

  /**
   * Returns the entries, oldest first, for tests.
   *
   * @return a copy
   */
  long[] entries() {
    return Arrays.copyOf(lines, size);
  }

  /**
   * Returns the current entry's index, for tests.
   *
   * @return the index; the entry count when at none
   */
  int position() {
    return at;
  }
}
