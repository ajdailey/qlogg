package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.core.model.ColumnLayout;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.model.Viewport;

/**
 * The current cell of a column view: the cell holding the caret's column. {@code Left} and {@code
 * Right} ({@link CommandId#SCROLL_LEFT}, {@link CommandId#SCROLL_RIGHT}) move the caret to the
 * previous and next cell, {@code Home} and {@code End} ({@link CommandId#LINE_START}, {@link
 * CommandId#LINE_END}) to the first and last, and the view scrolls so a scrolling cell shows whole
 * when it fits. The caret stays on its row; a selection keeps its anchor. Confined to the UI
 * thread.
 */
final class CellCaret {
  private CellCaret() {}

  /**
   * Runs a cell move in column view.
   *
   * @param view the view
   * @param command the command
   * @return false in text view and for any other command, which the view runs as usual
   */
  static boolean execute(LineViewController view, CommandId command) {
    ColumnController columns = view.owner().columns();
    ColumnLayout layout = columns.shown() ? columns.layout() : null;
    if (layout == null) {
      return false;
    }
    Selection selection = view.selection();
    int cell = layout.cellAt(selection.caret().column());
    int target;
    switch (command) {
      case SCROLL_LEFT -> target = Math.max(0, cell - 1);
      case SCROLL_RIGHT -> target = Math.min(layout.count() - 1, cell + 1);
      case LINE_START -> target = 0;
      case LINE_END -> target = layout.count() - 1;
      default -> {
        return false;
      }
    }
    TextPosition caret = new TextPosition(selection.caret().line(), layout.start(target));
    view.select(
        selection.isEmpty() ? Selection.caretAt(caret) : new Selection(selection.anchor(), caret));
    reveal(view, layout, target);
    return true;
  }

  /** Scrolls so a scrolling cell shows, its start first when the whole cell does not fit. */
  private static void reveal(LineViewController view, ColumnLayout layout, int cell) {
    if (cell < layout.frozen()) {
      return;
    }
    Viewport viewport = view.viewport();
    long frozen = layout.frozenWidth();
    long start = layout.start(cell) - frozen;
    long end = layout.start(cell + 1) - frozen;
    long first = viewport.firstColumn();
    if (start < first || end - start > viewport.columns()) {
      view.setFirstColumn(start);
    } else if (end > first + viewport.columns()) {
      view.setFirstColumn(end - viewport.columns());
    }
  }
}
