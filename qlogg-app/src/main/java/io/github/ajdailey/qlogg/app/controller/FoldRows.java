package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.FoldMap;
import io.github.ajdailey.qlogg.core.model.FoldRules;
import io.github.ajdailey.qlogg.core.model.RowCursor;
import io.github.ajdailey.qlogg.core.model.Viewport;
import io.github.ajdailey.qlogg.core.search.LineSet;

/**
 * A file tab's rows: every line, a collapsed entry as one row ({@link FoldMap}), folded by a {@link
 * FoldController}. Searches test every line, and the rows settle as the index grows.
 */
final class FoldRows implements RowMap {
  private final DocumentController owner;
  private final FoldMap folds;
  private final FoldController folding;

  FoldRows(DocumentController owner, FoldMap folds) {
    this.owner = owner;
    this.folds = folds;
    this.folding = new FoldController(owner, folds);
  }

  @Override
  public long rowCount(long lineCount) {
    return lineCount - folds.hiddenLines();
  }

  @Override
  public long lineSpan(long rowCount) {
    return rowCount + folds.hiddenLines();
  }

  @Override
  public long lineAt(long row) {
    return folds.lineAt(row);
  }

  @Override
  public long rowOf(long line) {
    return folds.rowOf(line);
  }

  @Override
  public long revealRow(long line) {
    if (folds.collapsedEntry(line) != null && !folds.isCollapsed(line)) {
      final long hidden = line;
      owner.changeRows(
          new Runnable() {
            @Override
            public void run() {
              folds.expandContaining(hidden);
            }
          });
    }
    return folds.rowOf(line);
  }

  @Override
  public RowCursor cursor(long lineCount) {
    return folds.cursor(lineCount);
  }

  @Override
  public long endOf(long line) {
    long[] entry = folds.collapsedEntry(line);
    return entry == null ? line + 1 : entry[1];
  }

  @Override
  public boolean folded(long line) {
    return folds.isCollapsed(line);
  }

  @Override
  public void toggleFold(long line) {
    folding.toggle(line);
  }

  @Override
  public void collapseAll() {
    folding.collapseAll();
  }

  @Override
  public void expandAll() {
    folding.expandAll();
  }

  @Override
  public void setFoldRules(FoldRules rules) {
    folding.setRules(rules);
  }

  /** With collapse-all on, asks the fold controller to classify the visible region. */
  @Override
  public void visibleChanged() {
    if (!folding.collapsingAll()) {
      return;
    }
    Viewport viewport = owner.viewport();
    long count = viewport.lineCount();
    if (count == 0) {
      return;
    }
    long top = viewport.topLine();
    long bottom = Math.min(count - 1, top + viewport.rows());
    folding.visible(owner.sourceLine(top), owner.sourceLine(bottom), viewport.rows());
  }

  @Override
  public LineSet members() {
    return null;
  }

  @Override
  public long settledLines() {
    return Long.MAX_VALUE;
  }

  @Override
  public long settledBytes(LineIndex index) {
    return index.indexedBytes();
  }

  @Override
  public void readerStarted() {
    // Every line is a member; there is no set to hold.
  }

  @Override
  public void readerEnded() {
    // No reader count.
  }

  @Override
  public void detach() {
    folding.detach();
  }
}
