package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.FoldRules;
import io.github.ajdailey.qlogg.core.model.RecordRows;
import io.github.ajdailey.qlogg.core.model.RowCursor;
import io.github.ajdailey.qlogg.core.search.LineSet;

/**
 * A file tab's rows: {@link FoldRows}, or in column view on a multi-line format one row per record
 * ({@link ColumnController#rows}), shown at its first line. Folding is off while records show; the
 * fold rules and collapsed entries stay for text view. Searches test every line either way.
 */
final class ColumnRows implements RowMap {
  private final DocumentController owner;
  private final FoldRows folds;

  ColumnRows(DocumentController owner, FoldRows folds) {
    this.owner = owner;
    this.folds = folds;
  }

  private RecordRows records() {
    return owner.columns().rows();
  }

  private long lineCount() {
    return owner.document().index().lineCount();
  }

  @Override
  public long rowCount(long lineCount) {
    RecordRows records = records();
    return records == null ? folds.rowCount(lineCount) : records.rowCount(lineCount);
  }

  @Override
  public long lineSpan(long rowCount) {
    RecordRows records = records();
    if (records == null) {
      return folds.lineSpan(rowCount);
    }
    return rowCount <= 0 ? 0 : records.recordEnd(records.lineAt(rowCount - 1), lineCount());
  }

  @Override
  public long lineAt(long row) {
    RecordRows records = records();
    return records == null ? folds.lineAt(row) : records.lineAt(row);
  }

  @Override
  public long rowOf(long line) {
    RecordRows records = records();
    return records == null ? folds.rowOf(line) : records.rowOf(line);
  }

  @Override
  public long revealRow(long line) {
    RecordRows records = records();
    return records == null ? folds.revealRow(line) : records.rowOf(line);
  }

  @Override
  public RowCursor cursor(long lineCount) {
    RecordRows records = records();
    return records == null ? folds.cursor(lineCount) : records.cursor(lineCount);
  }

  @Override
  public long endOf(long line) {
    RecordRows records = records();
    return records == null ? folds.endOf(line) : records.recordEnd(line, lineCount());
  }

  @Override
  public boolean folded(long line) {
    return records() == null && folds.folded(line);
  }

  @Override
  public void toggleFold(long line) {
    if (records() == null) {
      folds.toggleFold(line);
    }
  }

  @Override
  public void collapseAll() {
    if (records() == null) {
      folds.collapseAll();
    }
  }

  @Override
  public void expandAll() {
    if (records() == null) {
      folds.expandAll();
    }
  }

  @Override
  public void setFoldRules(FoldRules rules) {
    folds.setFoldRules(rules);
  }

  @Override
  public void visibleChanged() {
    if (records() == null) {
      folds.visibleChanged();
    }
  }

  @Override
  public LineSet members() {
    return folds.members();
  }

  @Override
  public long settledLines() {
    return folds.settledLines();
  }

  @Override
  public long settledBytes(LineIndex index) {
    return folds.settledBytes(index);
  }

  @Override
  public void readerStarted() {
    folds.readerStarted();
  }

  @Override
  public void readerEnded() {
    folds.readerEnded();
  }

  @Override
  public void detach() {
    folds.detach();
  }
}
