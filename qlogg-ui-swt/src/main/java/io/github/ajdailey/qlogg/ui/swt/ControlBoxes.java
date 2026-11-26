package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.core.model.HighlightSpans;
import io.github.ajdailey.qlogg.core.model.LineBlock;
import io.github.ajdailey.qlogg.core.text.ControlChars;
import io.github.ajdailey.qlogg.ui.swt.platform.Contrast;
import java.util.Arrays;
import java.util.function.LongToIntFunction;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;

/**
 * Draws a row's control boxes ({@link LineBlock#controls}): a 1 px outline over the box's two
 * columns, inset 1 px from the row's top and 2 px from its bottom, around the char's {@link
 * ControlChars#label} drawn from the first column on the text baseline. A box cut by the row's
 * first or last shown column has no outline on that side. Outline and label take the color the text
 * of the box's first shown column would take (selection text, a span's text color, the line's text
 * color or the palette's) blended over that column's ground at {@link #ALPHA}, or that text color
 * itself where the blend is under {@link #MIN_CONTRAST} against the ground ({@link #ink}). The same
 * drawing on every platform, so the toolkit never draws a control char.
 *
 * <p>On a row drawn on the grid, the labels of the boxes in the row's own color are drawn as one
 * string: a {@code GC.drawString} call per box makes a screen of boxes paint 2.5 times as long on
 * GTK 3.
 */
final class ControlBoxes {
  /** Alpha of the box color over its ground: 4.6:1 on the light palette, 5.6:1 on the dark. */
  static final int ALPHA = 0xa6;

  /** Least contrast ratio of a blended box color against its ground. */
  static final double MIN_CONTRAST = 3.0;

  private ControlBoxes() {}

  /**
   * Draws the boxes of a row.
   *
   * @param gc the canvas's GC, font set, clipping unset
   * @param colors the palette's colors
   * @param grid the column grid
   * @param row the row
   * @param text the row's text in its block
   * @param controls the row's control cells
   * @param spans the row's highlight
   * @param under the row's ground as {@code 0xRRGGBB}
   * @param onGrid whether the row's text is drawn on the grid, one char per column
   * @param columnX the x of a display column on the row
   */
  static void paint(
      GC gc,
      PaletteColors colors,
      TextGrid grid,
      RowPainter.Row row,
      String text,
      String controls,
      HighlightSpans spans,
      int under,
      boolean onGrid,
      LongToIntFunction columnX) {
    LineBlock block = row.block();
    int height = grid.lineHeight();
    int top = row.y() + 1;
    int bottom = row.y() + height - 2;
    int base = ink(rowText(colors, spans), under);
    long first = Math.max(row.from(), block.firstColumn());
    long end = Math.min(row.to(), block.firstColumn() + text.length());
    char[] labels = null;
    int last = -1;
    long column = block.firstColumn();
    for (int i = 0; i < controls.length() && i < text.length(); i++) {
      if (Character.isLowSurrogate(text.charAt(i))) {
        continue;
      }
      char cell = controls.charAt(i);
      boolean opens = cell != ' ' && cell < LineBlock.SECOND_CELL;
      boolean cutLeft = cell >= LineBlock.SECOND_CELL && (i == 0 || column == row.from());
      if ((opens || cutLeft) && column >= row.from() && column < row.to()) {
        char c = opens ? cell : (char) (cell - LineBlock.SECOND_CELL);
        boolean whole =
            cutLeft
                || column + 1 < row.to()
                    && i + 1 < controls.length()
                    && controls.charAt(i + 1) == (char) (c + LineBlock.SECOND_CELL);
        int left = columnX.applyAsInt(column);
        int right = columnX.applyAsInt(column + (opens && whole ? 2 : 1)) - 1;
        int color = color(colors, row, spans, under, base, column);
        if (color != last) {
          gc.setForeground(colors.cached(color));
          last = color;
        }
        String label = ControlChars.label(c);
        if (onGrid && color == base) {
          if (labels == null) {
            labels = new char[(int) (end - first)];
            Arrays.fill(labels, ' ');
          }
          labels[(int) (column - first)] = label.charAt(opens ? 0 : 1);
          if (opens && whole) {
            labels[(int) (column + 1 - first)] = label.charAt(1);
          }
        } else {
          gc.setClipping(left, row.y(), right + 1 - left, height);
          gc.drawString(label, columnX.applyAsInt(opens ? column : column - 1), row.y(), true);
          gc.setClipping((Rectangle) null);
        }
        if (opens && whole) {
          gc.drawRectangle(left, top, right - left, bottom - top);
        } else {
          gc.drawLine(left, top, right, top);
          gc.drawLine(left, bottom, right, bottom);
          gc.drawLine(opens ? left : right, top, opens ? left : right, bottom);
        }
      }
      column++;
    }
    if (labels != null) {
      gc.setForeground(colors.cached(base));
      gc.setClipping(row.x(), row.y(), row.right() - row.x(), height);
      gc.drawString(new String(labels), columnX.applyAsInt(first), row.y(), true);
      gc.setClipping((Rectangle) null);
    }
  }

  private static int rowText(PaletteColors colors, HighlightSpans spans) {
    return spans.lineForeground() == 0
        ? PaletteColors.packed(colors.palette.text())
        : spans.lineForeground() & 0xffffff;
  }

  /** Returns the box color of a column as {@code 0xRRGGBB}; base outside spans and selection. */
  private static int color(
      PaletteColors colors,
      RowPainter.Row row,
      HighlightSpans spans,
      int under,
      int base,
      long column) {
    if (row.selectionFrom() >= 0 && column >= row.selectionFrom() && column < row.selectionTo()) {
      return ink(
          PaletteColors.packed(colors.palette.selectionText()),
          PaletteColors.packed(colors.palette.selection()));
    }
    int text = rowText(colors, spans);
    int ground = under;
    boolean spanned = false;
    for (int i = 0; i < spans.count(); i++) {
      if (spans.start(i) <= column && column < spans.end(i)) {
        spanned = true;
        if (spans.foreground(i) != 0) {
          text = spans.foreground(i) & 0xffffff;
        }
        if (spans.background(i) != 0) {
          ground = PaletteColors.blend(spans.background(i), under);
        }
      }
    }
    return spanned ? ink(text, ground) : base;
  }

  /**
   * Returns the box color: {@code text} over {@code ground} at {@link #ALPHA}, or {@code text}
   * itself where that blend is under {@link #MIN_CONTRAST} against the ground (a highlighter, color
   * label or scheme background).
   *
   * @param text the text color as {@code 0xRRGGBB}
   * @param ground the color under the box as {@code 0xRRGGBB}
   * @return {@code 0xRRGGBB}
   */
  static int ink(int text, int ground) {
    int blend = PaletteColors.blend(ALPHA << 24 | text, ground);
    return Contrast.ratio(rgb(blend), rgb(ground)) >= MIN_CONTRAST ? blend : text;
  }

  private static RGB rgb(int packed) {
    return new RGB(packed >> 16 & 0xff, packed >> 8 & 0xff, packed & 0xff);
  }
}
