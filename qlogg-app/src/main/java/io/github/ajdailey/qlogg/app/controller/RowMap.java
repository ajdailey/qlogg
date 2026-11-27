package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.FoldRules;
import io.github.ajdailey.qlogg.core.model.RowCursor;
import io.github.ajdailey.qlogg.core.search.LineSet;

/**
 * Maps a main view's rows to document lines: {@link FoldRows} for a file tab, where row n is line n
 * unless entries are folded, and {@link DerivedRows} for a filter tab, where the rows are the lines
 * of a set. {@link DocumentController} picks one at construction. Confined to the UI thread, except
 * {@link #cursor}'s walk.
 *
 * <p>A search restricted to {@link #members} reads them below {@link #settledLines} only, and holds
 * {@link #readerStarted} until it ends; the set does not change below that line while a reader
 * holds it.
 */
interface RowMap {
  /**
   * Counts the rows.
   *
   * @param lineCount lines indexed
   * @return the rows the view lists
   */
  long rowCount(long lineCount);

  /**
   * Returns the line after the last line the rows cover.
   *
   * @param rowCount rows the view lists
   * @return the line count those rows stand for
   */
  long lineSpan(long rowCount);

  /**
   * Maps a row to its line.
   *
   * @param row a 0-based row below the row count
   * @return the 0-based line
   */
  long lineAt(long row);

  /**
   * Returns the row of a line, or of the nearest listed line after it.
   *
   * @param line a 0-based line
   * @return the row
   */
  long rowOf(long line);

  /**
   * Returns the row of a line for navigation to it, expanding a fold that hides it.
   *
   * @param line a 0-based line
   * @return the row
   */
  long revealRow(long line);

  /**
   * Returns a thread-safe walk over the listed lines.
   *
   * @param lineCount lines indexed
   * @return the cursor
   */
  RowCursor cursor(long lineCount);

  /**
   * Returns the line after the last line a row stands for.
   *
   * @param line the row's 0-based line
   * @return the line after it, or after its collapsed entry
   */
  long endOf(long line);

  /**
   * Reports whether a line starts a collapsed entry.
   *
   * @param line a 0-based line
   * @return true when lines are hidden under it
   */
  boolean folded(long line);

  /**
   * Collapses or expands the entry at a line.
   *
   * @param line a 0-based line
   */
  void toggleFold(long line);

  /** Collapses every multi-line entry as it is shown. */
  void collapseAll();

  /** Expands every entry. */
  void expandAll();

  /**
   * Sets the rules that find entries.
   *
   * @param rules the rules
   */
  void setFoldRules(FoldRules rules);

  /** The visible rows changed; entries there may need classifying. */
  void visibleChanged();

  /**
   * Returns the lines a search of the view tests.
   *
   * @return the set, or null for every line
   */
  LineSet members();

  /**
   * Returns the line below which {@link #members} is decided and stays unchanged while a reader
   * holds it.
   *
   * @return the line, or {@link Long#MAX_VALUE} for every indexed line
   */
  long settledLines();

  /**
   * Returns the indexed bytes {@link #members} reflects; a growth of it means rows may follow.
   *
   * @param index the document's current index
   * @return the bytes
   */
  long settledBytes(LineIndex index);

  /** A search restricted to {@link #members} starts. */
  void readerStarted();

  /** A search counted by {@link #readerStarted} ended. */
  void readerEnded();

  /** Stops background work and returns the map's charge. */
  void detach();
}
