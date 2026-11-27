package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.FoldRules;
import io.github.ajdailey.qlogg.core.model.RowCursor;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;

/**
 * A filter tab's rows: the root lines of a {@link MutableLineSet} charged to {@code RESULTS}. Row n
 * is {@code select(n)}; a line's row is {@code rank(line)}, the nearest following row for a line
 * that is not a member. Folding is off. Confined to the UI thread, except {@link #cursor}'s walk.
 *
 * <p>The set holds every matching line below {@link #searchedEnd}. A growth ({@link
 * #growthStarted}) removes the lines from its first line on and adds matches at or after it, so it
 * waits until no reader holds the set, and while it runs a new reader reads below its first line
 * only.
 */
final class DerivedRows implements RowMap {
  private final MutableLineSet lines;
  private final Runnable idle;
  private long searchedEnd;
  private long searchedBytes;
  private boolean growing;
  private long growthFrom;
  private int readers;

  /**
   * Creates the rows.
   *
   * @param lines the rows' lines; these rows clear it on {@link #detach}
   * @param searchedEnd the line below which {@code lines} holds every match
   * @param searchedBytes the indexed bytes {@code lines} reflects
   * @param idle runs when the last reader ends
   */
  DerivedRows(MutableLineSet lines, long searchedEnd, long searchedBytes, Runnable idle) {
    this.lines = lines;
    this.searchedEnd = searchedEnd;
    this.searchedBytes = searchedBytes;
    this.idle = idle;
  }

  /** Returns the set a growth writes. */
  MutableLineSet lines() {
    return lines;
  }

  /** Returns the line below which the set holds every match. */
  long searchedEnd() {
    return searchedEnd;
  }

  /** Returns the indexed bytes the set reflects. */
  long searchedBytes() {
    return searchedBytes;
  }

  /** Reports whether a growth must wait: one runs, or a reader holds the set. */
  boolean busy() {
    return growing || readers > 0;
  }

  /**
   * Starts a growth: drops the lines from {@code from} on, which the growth tests again.
   *
   * @param from the growth's first line
   */
  void growthStarted(long from) {
    growing = true;
    growthFrom = from;
    lines.removeFrom(from);
  }

  /**
   * Ends a growth. One that did not complete left the lines from its first line undecided, so the
   * next growth runs whatever the parent's bytes.
   *
   * @param end the line below which the set now holds every match, or -1 when it did not complete
   * @param bytes the indexed bytes the growth searched
   */
  void growthEnded(long end, long bytes) {
    growing = false;
    if (end >= 0) {
      searchedEnd = end;
      searchedBytes = bytes;
    } else {
      searchedBytes = -1;
    }
  }

  @Override
  public long rowCount(long lineCount) {
    return lines.size();
  }

  @Override
  public long lineSpan(long rowCount) {
    long last = lineAt(rowCount - 1);
    return last < 0 ? 0 : last + 1;
  }

  /**
   * Returns a row's line; a growth's start can drop the last rows before the view's next flush.
   *
   * @param row a 0-based row
   * @return the line, or -1 past the set's last row
   */
  @Override
  public long lineAt(long row) {
    return row >= 0 && row < lines.size() ? lines.select(row) : -1;
  }

  @Override
  public long rowOf(long line) {
    return lines.rank(line);
  }

  @Override
  public long revealRow(long line) {
    return lines.rank(line);
  }

  @Override
  public RowCursor cursor(long lineCount) {
    return RowCursor.of(lines, null);
  }

  @Override
  public long endOf(long line) {
    return line + 1;
  }

  @Override
  public boolean folded(long line) {
    return false;
  }

  @Override
  public void toggleFold(long line) {
    // Folding is off: an entry's lines are not consecutive rows.
  }

  @Override
  public void collapseAll() {
    // Folding is off.
  }

  @Override
  public void expandAll() {
    // Folding is off.
  }

  @Override
  public void setFoldRules(FoldRules rules) {
    // Folding is off.
  }

  @Override
  public void visibleChanged() {
    // Folding is off.
  }

  @Override
  public LineSet members() {
    return lines;
  }

  @Override
  public long settledLines() {
    return growing ? growthFrom : searchedEnd;
  }

  @Override
  public long settledBytes(LineIndex index) {
    return searchedBytes;
  }

  @Override
  public void readerStarted() {
    readers++;
  }

  @Override
  public void readerEnded() {
    readers--;
    if (readers == 0) {
      idle.run();
    }
  }

  @Override
  public void detach() {
    lines.clear();
  }
}
