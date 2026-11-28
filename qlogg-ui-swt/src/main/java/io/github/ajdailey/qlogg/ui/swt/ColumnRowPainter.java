package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.theme.CellRule;
import io.github.ajdailey.qlogg.core.model.LineBlock;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;

/**
 * Paints a column-view row ({@link LineBlock#cellView}): the row's ground across the text area,
 * then each cell through the {@link RowPainter} with the cell's columns at its {@link CellGrid} x,
 * clipped at the cell's rule, then a one-pixel rule in {@link #ruleColor} after each cell. A glyph
 * wider than its grid columns, such as a CJK ideograph laid out in the cell's {@code TextLayout},
 * is cut at the rule and the next cell starts at its own grid x. A printable-ASCII row, whose
 * glyphs each take one grid column, paints its frozen cells and its scrolled cells in one pass
 * each, which costs what a text-view row does. A row that did not parse ({@link
 * LineBlock#unparsed}) paints its frozen part and its scrolled part as two cells and no rule. A
 * header row ({@link LineBlock#header}) takes the gutter color as its ground. The caret row
 * outlines the current cell. Selection is whole rows. UI thread only.
 */
final class ColumnRowPainter {
  private final RowPainter painter;

  /**
   * Creates the painter.
   *
   * @param painter paints each cell; its {@link RowPainter#endPaint} stays the caller's
   */
  ColumnRowPainter(RowPainter painter) {
    this.painter = painter;
  }

  /**
   * Returns the color of the rule between cells and between header cells.
   *
   * @param colors the palette's colors
   * @return the palette's {@link CellRule}
   */
  static Color ruleColor(PaletteColors colors) {
    return colors.cellRule;
  }

  /**
   * One screen row of a column view.
   *
   * @param block the snapshot holding the line
   * @param line the row
   * @param y the row's top
   * @param right the right edge of the text area
   * @param current whether the row holds the caret
   * @param caretColumn the caret's composed column on the caret row
   * @param selected whether the row is selected
   * @param context whether the row shows as context of a search result
   */
  record Row(
      LineBlock block,
      long line,
      int y,
      int right,
      boolean current,
      long caretColumn,
      boolean selected,
      boolean context) {}

  /**
   * Paints a row. The caller sets the font and anti-aliasing on {@code gc}, and leaves the clipping
   * unset.
   *
   * @param gc the canvas's GC
   * @param colors the palette's colors
   * @param cells the cell geometry
   * @param row the row
   */
  void paint(GC gc, PaletteColors colors, CellGrid cells, Row row) {
    LineBlock block = row.block();
    long line = row.line();
    int height = cells.grid().lineHeight();
    Color ground = null;
    if (row.selected()) {
      ground = colors.selection;
    } else if (block.header(line)) {
      ground = colors.gutter;
    } else if (row.current()) {
      ground = colors.cached(PaletteColors.packed(colors.palette.currentLine()));
    } else if (row.context()) {
      ground = colors.cached(PaletteColors.packed(colors.palette.contextRow()));
    }
    if (ground != null) {
      gc.setBackground(ground);
      gc.fillRectangle(cells.x(), row.y(), row.right() - cells.x(), height);
    }
    int frozen = cells.frozen();
    // A read for another scroll position holds no columns left of its window.
    long textColumn = block.firstColumn();
    long scrollFrom = frozen + Math.max(cells.firstColumn(), textColumn);
    if (block.unparsed(line)) {
      if (frozen > 0) {
        cell(gc, colors, cells, row, 0, frozen, 0);
      }
      cell(gc, colors, cells, row, scrollFrom, Long.MAX_VALUE, textColumn);
      return;
    }
    int count = block.cellCount(line);
    // A printable-ASCII glyph takes one grid column, so no cell overruns: two paints do.
    boolean byCell = !TextGrid.printableAscii(block.text(line));
    if (!byCell) {
      if (frozen > 0) {
        cell(gc, colors, cells, row, 0, frozen, 0);
      }
      if (block.width(line) > scrollFrom) {
        cell(gc, colors, cells, row, scrollFrom, block.width(line), textColumn);
      }
    }
    Color rule = ruleColor(colors);
    for (int i = 0; i < count; i++) {
      long start = block.cellStart(line, i);
      long end = i + 1 < count ? block.cellStart(line, i + 1) : block.width(line);
      if (byCell && start < frozen) {
        cell(gc, colors, cells, row, start, end, 0);
      } else if (byCell && end > scrollFrom) {
        cell(gc, colors, cells, row, Math.max(start, scrollFrom), end, textColumn);
      }
      if (cells.ruleShows(end, row.right())) {
        gc.setForeground(rule);
        int x = cells.ruleX(end);
        gc.drawLine(x, row.y(), x, row.y() + height - 1);
      }
      if (row.current()
          && row.caretColumn() >= start
          && (row.caretColumn() < end || i == count - 1)) {
        outline(gc, colors, cells, row, start, end, scrollFrom);
      }
    }
  }

  /** Paints the columns {@code [from, to)} of one cell through the row painter. */
  private void cell(
      GC gc, PaletteColors colors, CellGrid cells, Row row, long from, long to, long textColumn) {
    int left = cells.columnX(from);
    int edge = row.right();
    if (from < cells.frozen()) {
      edge = Math.min(edge, cells.x() + cells.grid().columnOffset(to));
    } else if (to != Long.MAX_VALUE) {
      edge = Math.min(edge, cells.columnX(to));
    }
    if (to != Long.MAX_VALUE && !row.block().unparsed(row.line())) {
      // Nothing of the cell reaches past its rule.
      edge = Math.min(edge, cells.ruleX(to));
    }
    if (edge <= left || left >= row.right()) {
      return;
    }
    painter.paint(
        gc,
        colors,
        cells.grid(),
        new RowPainter.Row(
            row.block(),
            row.line(),
            from,
            to,
            false,
            left,
            row.y(),
            edge,
            row.current() && !row.selected(),
            row.selected() ? 0 : -1,
            Long.MAX_VALUE,
            row.context(),
            textColumn));
  }

  /** Outlines the current cell, its visible part. */
  private static void outline(
      GC gc, PaletteColors colors, CellGrid cells, Row row, long start, long end, long scrollFrom) {
    long from = start < cells.frozen() ? start : Math.max(start, scrollFrom);
    if (from >= end) {
      return;
    }
    int left = cells.columnX(from);
    int right = Math.min(row.right(), cells.ruleX(end));
    if (right <= left) {
      return;
    }
    gc.setForeground(colors.lineNumbers);
    gc.drawRectangle(left, row.y(), right - left - 1, cells.grid().lineHeight() - 1);
  }

  /**
   * Draws the cut marker of a truncated record ({@link LineBlock#cut}) in the marker strip: three
   * dots across the strip's middle.
   *
   * @param gc the canvas's GC
   * @param colors the palette's colors
   * @param x the strip's left edge
   * @param width the strip's width
   * @param y the row's top
   * @param height the row height
   */
  static void paintCut(GC gc, PaletteColors colors, int x, int width, int y, int height) {
    gc.setBackground(colors.lineNumbers);
    int dot = Math.max(2, height / 8);
    int gap = Math.max(1, (width - 3 * dot) / 4);
    int top = y + (height - dot) / 2;
    for (int i = 0; i < 3; i++) {
      gc.fillRectangle(x + gap + i * (dot + gap), top, dot, dot);
    }
  }
}
