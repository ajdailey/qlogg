package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.core.model.HighlightSpans;
import io.github.ajdailey.qlogg.core.model.LineBlock;
import io.github.ajdailey.qlogg.core.model.LineTerminator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontMetrics;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.TextLayout;

/**
 * Paints one screen row of a log view: the plain paint passes each line once, the wrapped paint
 * each segment of a line. A row paints its background (base or context shade, current line, then
 * the whole-line highlight), the span backgrounds and the selection, the text in the line's color,
 * then the text again clipped to each span with a text color and to the selection, then the
 * whitespace markers. A translucent color is blended over the color under it.
 *
 * <p>Column {@code c} of a printable-ASCII row sits on the {@link TextGrid}, and the row is drawn
 * with {@code GC.drawString}. Any other row is laid out in a {@link TextLayout}, which also draws
 * it; a column table taken from that layout places the spans, the selection and the markers on its
 * glyphs. A table entry is read from the layout when its column is first placed: each {@code
 * getLocation} call on GTK walks the line. A laid-out row is kept, keyed by its text, while each
 * paint draws it, so a repaint of the same rows lays out and measures nothing. A prefix measured
 * alone has the wrong width: GTK draws spaces that lead CJK text in the CJK font of the run they
 * join, narrower than the same spaces alone. The layout is drawn with its baseline on the GC
 * font's: its line ascent and leading come from the tallest font in the line, and {@code
 * TextLayout.setAscent} does not lower them on GTK 3. {@code GC.setTextAntialias} does not reach a
 * {@code TextLayout} on GTK, so forced anti-aliasing applies to printable-ASCII rows only.
 *
 * <p>Owns one {@code TextLayout} per laid-out row of the last paint and at most as many spares,
 * disposed by {@link #dispose}. The caller must call {@link #endPaint} after each paint. UI thread
 * only.
 */
final class RowPainter {
  /**
   * One screen row: the columns {@code [from, to)} of a line, with column {@code from} at {@code
   * x}.
   *
   * @param block the snapshot holding the line, or not holding it for a blank row
   * @param line the line
   * @param from the first display column shown
   * @param to the column after the last one shown; {@code Long.MAX_VALUE} for no limit
   * @param last whether the row is the line's last, which may show the line end
   * @param x the left edge of the text area
   * @param y the row's top
   * @param right the right edge of the text area
   * @param current whether the line holds the caret
   * @param selectionFrom the first selected column, negative for none
   * @param selectionTo the column after the selection; {@code Long.MAX_VALUE} past the line end
   * @param context whether the line shows as context of a search result
   */
  record Row(
      LineBlock block,
      long line,
      long from,
      long to,
      boolean last,
      int x,
      int y,
      int right,
      boolean current,
      long selectionFrom,
      long selectionTo,
      boolean context) {
    /**
     * Creates a row that is not a context line.
     *
     * @param block see the record
     * @param line see the record
     * @param from see the record
     * @param to see the record
     * @param last see the record
     * @param x see the record
     * @param y see the record
     * @param right see the record
     * @param current see the record
     * @param selectionFrom see the record
     * @param selectionTo see the record
     */
    Row(
        LineBlock block,
        long line,
        long from,
        long to,
        boolean last,
        int x,
        int y,
        int right,
        boolean current,
        long selectionFrom,
        long selectionTo) {
      this(block, line, from, to, last, x, y, right, current, selectionFrom, selectionTo, false);
    }
  }

  /** Chars a {@code TextLayout} breaks a line at; laid out as spaces so the row stays one line. */
  private static final String BREAKS = "\n\r\u000b\u000c\u001c\u001d\u001e\u0085  ";

  /** Marks a column table entry not yet read from the layout. */
  private static final int UNREAD = Integer.MIN_VALUE;

  /** A drawn part laid out, with its column table. */
  private static final class LaidOut {
    final TextLayout layout;

    /** The x of each column from the part's left edge, or {@link #UNREAD}; one past the last. */
    final int[] table;

    /** The char index of each column, or null when the part has no surrogate pair. */
    final int[] chars;

    /** The y offset that puts the layout's baseline on the GC font's. */
    final int top;

    /** Whether the current paint drew it. */
    boolean used;

    LaidOut(TextLayout layout, int[] table, int[] chars, int top) {
      this.layout = layout;
      this.table = table;
      this.chars = chars;
      this.top = top;
    }
  }

  private final Device device;
  private Font font;

  /** The laid-out rows of the last and the current paint, by drawn part. */
  private final Map<String, LaidOut> laidOut = new HashMap<String, LaidOut>();

  /** Layouts of rows the last paint did not draw, for reuse. */
  private final List<TextLayout> spare = new ArrayList<TextLayout>();

  // State of the row being painted.
  private GC gc;
  private TextGrid grid;
  private Row row;
  private String part;
  private long start;
  private int partX;
  private int partY;
  private LaidOut laid;
  private int partColumns;

  /**
   * Creates the painter.
   *
   * @param device the display
   * @param font the font rows are drawn in
   */
  RowPainter(Device device, Font font) {
    this.device = device;
    this.font = font;
  }

  /**
   * Sets the font rows are drawn in.
   *
   * @param font the font; the caller keeps ownership
   */
  void setFont(Font font) {
    this.font = font;
    for (LaidOut row : laidOut.values()) {
      spare.add(row.layout);
    }
    laidOut.clear();
  }

  /** Releases the layouts of rows the paint just ended did not draw. */
  void endPaint() {
    Iterator<LaidOut> rows = laidOut.values().iterator();
    while (rows.hasNext()) {
      LaidOut row = rows.next();
      if (row.used) {
        row.used = false;
      } else {
        rows.remove();
        spare.add(row.layout);
      }
    }
    while (spare.size() > laidOut.size()) {
      spare.remove(spare.size() - 1).dispose();
    }
  }

  /** Disposes the layouts. */
  void dispose() {
    for (LaidOut row : laidOut.values()) {
      row.layout.dispose();
    }
    laidOut.clear();
    for (TextLayout layout : spare) {
      layout.dispose();
    }
    spare.clear();
  }

  /**
   * Paints a row. The caller sets the font and anti-aliasing on {@code gc}, and leaves the clipping
   * unset.
   *
   * @param gc the canvas's GC
   * @param colors the palette's colors
   * @param grid the column grid
   * @param row the row
   */
  void paint(GC gc, PaletteColors colors, TextGrid grid, Row row) {
    this.gc = gc;
    this.grid = grid;
    this.row = row;
    LineBlock block = row.block();
    String text = block.contains(row.line()) ? block.text(row.line()) : null;
    HighlightSpans spans = text == null ? HighlightSpans.NONE : block.spans(row.line());
    prepare(text, text == null ? 0 : block.firstColumn());
    int base = PaletteColors.packed(colors.palette.background());
    int under = base;
    if (row.current()) {
      under = PaletteColors.packed(colors.palette.currentLine());
    } else if (row.context()) {
      under = PaletteColors.packed(colors.palette.contextRow());
    }
    if (spans.lineBackground() != 0) {
      under = PaletteColors.blend(spans.lineBackground(), under);
    }
    int height = grid.lineHeight();
    if (under != base) {
      gc.setBackground(colors.cached(under));
      gc.fillRectangle(row.x(), row.y(), row.right() - row.x(), height);
    }
    Rectangle[] spanRects = new Rectangle[spans.count()];
    for (int i = 0; i < spans.count(); i++) {
      spanRects[i] = rect(spans.start(i), spans.end(i));
      if (spanRects[i] != null && spans.background(i) != 0) {
        gc.setBackground(colors.cached(PaletteColors.blend(spans.background(i), under)));
        gc.fillRectangle(spanRects[i]);
      }
    }
    Rectangle selected =
        row.selectionFrom() < 0 ? null : rect(row.selectionFrom(), row.selectionTo());
    if (selected != null) {
      gc.setBackground(colors.selection);
      gc.fillRectangle(selected);
    }
    if (!part.isEmpty()) {
      Rectangle rowClip = new Rectangle(row.x(), row.y(), row.right() - row.x(), height);
      gc.setClipping(rowClip);
      Color rowText =
          spans.lineForeground() == 0
              ? colors.text
              : colors.cached(spans.lineForeground() & 0xffffff);
      gc.setForeground(rowText);
      drawPart();
      for (int i = 0; i < spans.count(); i++) {
        if (spanRects[i] != null && spans.foreground(i) != 0) {
          gc.setClipping(spanRects[i].intersection(rowClip));
          gc.setForeground(colors.cached(spans.foreground(i) & 0xffffff));
          drawPart();
        }
      }
      if (selected != null) {
        gc.setClipping(selected.intersection(rowClip));
        gc.setForeground(colors.selectionText);
        drawPart();
      }
      gc.setClipping((Rectangle) null);
    }
    if (text != null) {
      paintMarkers(colors, text);
    }
    this.gc = null;
    this.row = null;
    laid = null;
  }

  /**
   * Cuts the shown columns out of the row's text and, for a row outside printable ASCII, finds or
   * makes its layout.
   */
  private void prepare(String text, long textColumn) {
    start = Math.max(row.from(), textColumn);
    partX = row.x() + grid.columnOffset(start - row.from());
    laid = null;
    partColumns = 0;
    if (text == null) {
      part = "";
      return;
    }
    int first = TextGrid.charIndex(text, start - textColumn);
    int end =
        row.to() == Long.MAX_VALUE
            ? text.length()
            : TextGrid.charIndex(text, Math.max(0, row.to() - textColumn));
    part = text.substring(first, Math.max(first, end));
    if (part.isEmpty() || TextGrid.printableAscii(part)) {
      return;
    }
    laid = laidOut.get(part);
    if (laid == null) {
      laid = layOut(part);
      laidOut.put(part, laid);
    }
    laid.used = true;
    partY = row.y() + laid.top;
    partColumns = laid.table.length - 1;
  }

  private LaidOut layOut(String part) {
    TextLayout layout = spare.isEmpty() ? new TextLayout(device) : spare.remove(spare.size() - 1);
    layout.setFont(font);
    layout.setText(singleLine(part));
    FontMetrics metrics = gc.getFontMetrics();
    FontMetrics line = layout.getLineMetrics(0);
    int top = metrics.getAscent() + metrics.getLeading() - line.getAscent() - line.getLeading();
    int columns = 0;
    for (int i = 0; i < part.length(); i++) {
      if (!Character.isLowSurrogate(part.charAt(i))) {
        columns++;
      }
    }
    int[] table = new int[columns + 1];
    Arrays.fill(table, UNREAD);
    int[] chars = null;
    if (columns < part.length()) {
      chars = new int[columns + 1];
      int column = 0;
      for (int i = 0; i < part.length(); i++) {
        if (!Character.isLowSurrogate(part.charAt(i))) {
          chars[column++] = i;
        }
      }
      chars[columns] = part.length();
    }
    return new LaidOut(layout, table, chars, top);
  }

  /** Returns the x of column {@code k} of the drawn part from its left edge. */
  private int tableX(int k) {
    int x = laid.table[k];
    if (x == UNREAD) {
      x = laid.layout.getLocation(laid.chars == null ? k : laid.chars[k], false).x;
      laid.table[k] = x;
    }
    return x;
  }

  private static String singleLine(String text) {
    char[] chars = null;
    for (int i = 0; i < text.length(); i++) {
      if (BREAKS.indexOf(text.charAt(i)) >= 0) {
        if (chars == null) {
          chars = text.toCharArray();
        }
        chars[i] = ' ';
      }
    }
    return chars == null ? text : new String(chars);
  }

  private void drawPart() {
    if (laid != null) {
      laid.layout.draw(gc, partX, partY);
    } else {
      gc.drawString(part, partX, row.y(), true);
    }
  }

  /** Returns the x of a display column on this row. */
  private int columnX(long column) {
    if (laid == null || column <= start) {
      return row.x() + grid.columnOffset(column - row.from());
    }
    long k = column - start;
    if (k <= partColumns) {
      return partX + tableX((int) k);
    }
    return partX + tableX(partColumns) + grid.columnOffset(k - partColumns);
  }

  /**
   * Returns the pixels of the columns {@code [a, b)} on this row, or null when none show. A range
   * continuing past the line end reaches the right edge; an empty range shows as half a cell.
   */
  private Rectangle rect(long a, long b) {
    long left = Math.max(a, row.from());
    long right = Math.min(b, row.to());
    if (right < left || right == left && a < b && b != Long.MAX_VALUE) {
      return null;
    }
    int leftX = columnX(left);
    int rightX = b == Long.MAX_VALUE ? row.right() : columnX(right);
    if (b == Long.MAX_VALUE || rightX <= leftX) {
      rightX = Math.max(rightX, leftX + grid.cellWidth() / 2);
    }
    if (rightX <= row.x() || leftX >= row.right()) {
      return null;
    }
    return new Rectangle(leftX, row.y(), rightX - leftX, grid.lineHeight());
  }

  /** Draws the whitespace markers inside their cells and the line end after the last column. */
  private void paintMarkers(PaletteColors colors, String text) {
    LineBlock block = row.block();
    String marks = block.whitespace(row.line());
    if (marks == null) {
      return;
    }
    gc.setForeground(colors.lineNumbers);
    int height = grid.lineHeight();
    int middle = row.y() + height / 2;
    int cell = grid.cellWidth();
    long column = block.firstColumn();
    for (int i = 0; i < marks.length() && i < text.length(); i++) {
      if (Character.isLowSurrogate(text.charAt(i))) {
        continue;
      }
      char mark = marks.charAt(i);
      if (mark != ' ' && column >= row.from() && column < row.to()) {
        int x = columnX(column);
        switch (mark) {
          case LineBlock.TAB_MARK -> arrow(x, middle, true);
          case LineBlock.SPACE_MARK -> gc.drawPoint(x + cell / 2, middle);
          case LineBlock.CR_MARK -> arrow(x, middle, false);
          default -> {
            // Not whitespace.
          }
        }
      }
      column++;
    }
    long end = block.width(row.line());
    long inWindow = end - block.firstColumn();
    if (inWindow < 0
        || inWindow > block.columns()
        || end < row.from()
        || end > row.to()
        || end == row.to() && !row.last()) {
      return;
    }
    int x = columnX(end);
    LineTerminator terminator = block.terminator(row.line());
    if (terminator == LineTerminator.CRLF) {
      arrow(x, middle, false);
      x += cell;
    }
    if (terminator != LineTerminator.NONE) {
      int top = row.y() + height / 4;
      int bottom = row.y() + height - height / 4;
      gc.drawLine(x + cell - 2, top, x + cell - 2, bottom);
      gc.drawLine(x + 2, bottom, x + cell - 2, bottom);
    }
  }

  /** A small arrow inside one cell, right or left. */
  private void arrow(int x, int middle, boolean right) {
    int cell = grid.cellWidth();
    int left = x + 2;
    int far = x + cell - 2;
    int head = Math.max(2, cell / 3);
    gc.drawLine(left, middle, far, middle);
    if (right) {
      gc.drawLine(far, middle, far - head, middle - head);
      gc.drawLine(far, middle, far - head, middle + head);
    } else {
      gc.drawLine(left, middle, left + head, middle - head);
      gc.drawLine(left, middle, left + head, middle + head);
    }
  }
}
