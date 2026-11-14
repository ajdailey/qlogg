package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.model.TextPosition;
import org.junit.jupiter.api.Test;

/**
 * {@link TextGrid} with no display, at a fractional advance (Menlo 12 pt on macOS) and an integer
 * one. Points are given in grid units, as in {@code TextGridCasesTest}, so each case holds at both.
 */
class TextGridTest {
  private static final int LINES = 100;
  private static final double[] ADVANCES = {7.224609375, 8.0};

  /** Line 0 takes {@code segments} wrapped rows, every other line one. */
  private static TextGrid.WrappedRows firstLineRows(final int segments) {
    return new TextGrid.WrappedRows() {
      @Override
      public int rows(long line) {
        return line == 0 ? segments : 1;
      }
    };
  }

  /** The x right of the gutter of a column plus a fraction of the advance. */
  private static int x(TextGrid grid, long column, double fraction) {
    return grid.columnOffset(column) + (int) (fraction * grid.advance());
  }

  private static int y(TextGrid grid, int row) {
    return row * grid.lineHeight() + 1;
  }

  @Test
  void columnsSitAtTheRoundedMultipleOfTheAdvance() {
    TextGrid grid = new TextGrid(15, 7.224609375);
    assertEquals(722, grid.columnOffset(100));
    assertEquals(1084, grid.columnOffset(150));
    assertEquals(-14, grid.columnOffset(-2));
    assertEquals(Integer.MAX_VALUE / 4, grid.columnOffset(Long.MAX_VALUE));
    assertEquals(-(Integer.MAX_VALUE / 4), grid.columnOffset(-Long.MAX_VALUE));
    assertEquals(0, grid.columnAt(-5));
    assertEquals(0, grid.columnAt(3));
    assertEquals(1, grid.columnAt(4));
    assertEquals(100, grid.columnAt(722));
    assertEquals(7, grid.cellWidth());
    assertEquals(98, grid.columns(712));
    assertEquals(1, grid.columns(0));
    assertEquals(26, grid.rows(400));
    assertEquals(1, grid.rows(3));
  }

  @Test
  void metricsAreAtLeastOnePixel() {
    TextGrid grid = new TextGrid(0, 0.25);
    assertEquals(1, grid.lineHeight());
    assertEquals(1.0, grid.advance());
    assertEquals(8, grid.bulletWidth());
  }

  @Test
  void hitsMapToTheNearestColumnBoundaryOfTheVisibleRow() {
    for (double advance : ADVANCES) {
      TextGrid grid = new TextGrid(15, advance);
      assertEquals(new TextPosition(0, 0), grid.hit(0, LINES, 0, x(grid, 0, 0.25), y(grid, 0)));
      assertEquals(new TextPosition(2, 5), grid.hit(0, LINES, 0, x(grid, 5, 0.25), y(grid, 2)));
      assertEquals(new TextPosition(2, 6), grid.hit(0, LINES, 0, x(grid, 5, 0.75), y(grid, 2)));
      assertEquals(new TextPosition(3, 0), grid.hit(0, LINES, 0, -2, y(grid, 3)));
      assertEquals(new TextPosition(0, 7), grid.hit(0, LINES, 0, x(grid, 7, 0.25), -5));
      assertEquals(
          new TextPosition(LINES - 1, 3),
          grid.hit(0, LINES, 0, x(grid, 3, 0.25), LINES * 4 * grid.lineHeight()));
      assertEquals(new TextPosition(2, 40), grid.hit(0, LINES, 0, x(grid, 40, 0.25), y(grid, 2)));
      // A scrolled view adds its top line and first column.
      assertEquals(new TextPosition(4, 10), grid.hit(4, LINES, 10, x(grid, 0, 0.25), y(grid, 0)));
      assertEquals(new TextPosition(8, 15), grid.hit(4, LINES, 10, x(grid, 5, 0.25), y(grid, 4)));
      // No lines yet maps to line 0.
      assertEquals(new TextPosition(0, 1), grid.hit(0, 0, 0, x(grid, 1, 0.25), y(grid, 3)));
    }
  }

  @Test
  void theGutterHoldsTheBulletAndAtLeastFourDigits() {
    for (double advance : ADVANCES) {
      TextGrid grid = new TextGrid(15, advance);
      assertEquals(11, grid.bulletWidth());
      assertEquals(11, grid.gutterWidth(false, 1_000_000));
      int four = 11 + (int) Math.ceil(4 * advance) + 2 * TextGrid.GUTTER_PAD;
      assertEquals(four, grid.gutterWidth(true, LINES));
      assertEquals(four, grid.gutterWidth(true, 9999));
      assertEquals(
          11 + (int) Math.ceil(6 * advance) + 2 * TextGrid.GUTTER_PAD,
          grid.gutterWidth(true, 123_456));
    }
    assertEquals(8, new TextGrid(10, 7).bulletWidth());
  }

  @Test
  void wrappedRowsSplitALineAtTheViewWidth() {
    assertEquals(1, TextGrid.wrappedRows(-1, 90));
    assertEquals(1, TextGrid.wrappedRows(0, 90));
    assertEquals(1, TextGrid.wrappedRows(90, 90));
    assertEquals(2, TextGrid.wrappedRows(91, 90));
    assertEquals(5, TextGrid.wrappedRows(400, 90));
    assertEquals(400, TextGrid.wrappedRows(400, 1));
  }

  @Test
  void wrapFitCountsTheLinesThatFitWhole() {
    TextGrid grid = new TextGrid(15, 8);
    int screen = 20;
    int height = screen * 15 + 7;
    assertEquals(screen - 5 + 1, grid.wrapFit(0, LINES, firstLineRows(5), height, screen));
    assertEquals(screen, grid.wrapFit(1, LINES, firstLineRows(5), height, screen));
    assertEquals(10, grid.wrapFit(1, LINES, firstLineRows(5), height, 10));
    // Rows below the last line count one line each.
    assertEquals(screen, grid.wrapFit(LINES - 3, LINES, firstLineRows(5), height, screen));
    assertEquals(screen - 4, grid.wrapFit(0, 3, firstLineRows(5), height, screen));
    // A line taller than the view still counts one.
    assertEquals(1, grid.wrapFit(0, LINES, firstLineRows(50), height, screen));
  }

  /**
   * Regression: counting the rows left below the last line from the top alternated between two
   * counts when the lines above the last ones wrap tall, and the canvas re-measured without end
   * (stack overflow). Pinning the top to {@code lineCount - fit} must settle on one count with the
   * last line whole at the bottom.
   */
  @Test
  void pinningToTheEndSettlesOnOneFit() {
    TextGrid grid = new TextGrid(15, 8);
    int screen = 10;
    int height = screen * 15;
    // Every line four rows but the last two, one row each.
    TextGrid.WrappedRows tallAbove =
        new TextGrid.WrappedRows() {
          @Override
          public int rows(long line) {
            return line >= LINES - 2 ? 1 : 4;
          }
        };
    // Alternating heights.
    TextGrid.WrappedRows mixed =
        new TextGrid.WrappedRows() {
          @Override
          public int rows(long line) {
            return line % 3 == 0 ? 3 : 1;
          }
        };
    for (TextGrid.WrappedRows rows : new TextGrid.WrappedRows[] {tallAbove, mixed}) {
      for (long start = LINES - screen; start < LINES; start++) {
        long top = start;
        int fit = grid.wrapFit(top, LINES, rows, height, screen);
        int steps = 0;
        while (top != LINES - fit) {
          top = LINES - fit;
          fit = grid.wrapFit(top, LINES, rows, height, screen);
          steps++;
          assertTrue(steps < LINES, "no settled fit from top " + start);
        }
        int used = 0;
        for (long line = top; line < LINES; line++) {
          used += rows.rows(line);
        }
        assertTrue(used <= screen, "the last line is whole");
        assertTrue(used + rows.rows(top - 1) > screen, "no further line fits above the top");
      }
    }
    // 1 + 1 + 4 + 4 rows fill the 10.
    assertEquals(4, grid.wrapFit(LINES - 2, LINES, tallAbove, height, screen));
    assertEquals(4, grid.wrapFit(LINES - 1, LINES, tallAbove, height, screen));
  }

  @Test
  void wrappedHitsMapToTheirSegment() {
    for (double advance : ADVANCES) {
      TextGrid grid = new TextGrid(15, advance);
      int columns = 90;
      int segments = 5;
      TextGrid.WrappedRows rows = firstLineRows(segments);
      assertEquals(
          new TextPosition(0, 2),
          grid.wrappedHit(0, LINES, rows, columns, x(grid, 2, 0.25), y(grid, 0)));
      assertEquals(
          new TextPosition(0, columns + 2L),
          grid.wrappedHit(0, LINES, rows, columns, x(grid, 2, 0.25), y(grid, 1)));
      assertEquals(
          new TextPosition(0, (segments - 1L) * columns + 1),
          grid.wrappedHit(0, LINES, rows, columns, x(grid, 1, 0.25), y(grid, segments - 1)));
      // A point past the row's right edge clamps to the segment's end column.
      assertEquals(
          new TextPosition(0, columns),
          grid.wrappedHit(0, LINES, rows, columns, x(grid, columns + 5L, 0.25), y(grid, 0)));
      assertEquals(
          new TextPosition(1, 4),
          grid.wrappedHit(0, LINES, rows, columns, x(grid, 4, 0.25), y(grid, segments)));
      assertEquals(
          new TextPosition(2, 0),
          grid.wrappedHit(0, LINES, rows, columns, -2, y(grid, segments + 1)));
      assertEquals(
          new TextPosition(LINES - 1, 0),
          grid.wrappedHit(0, LINES, rows, columns, 0, LINES * 10 * grid.lineHeight()));
      assertEquals(
          new TextPosition(3, 0), grid.wrappedHit(1, LINES, rows, columns, -2, y(grid, 2)));
    }
  }

  @Test
  void aDoubleClickPastASegmentEndStaysOnThatSegment() {
    for (double advance : ADVANCES) {
      TextGrid grid = new TextGrid(15, advance);
      int columns = 90;
      TextGrid.WrappedRows rows = firstLineRows(5);
      int past = x(grid, columns + 5L, 0.25);
      // Past the right edge of the first and of a middle segment: their last column.
      assertEquals(
          new TextPosition(0, columns - 1L),
          grid.wrappedWordHit(0, LINES, rows, columns, past, y(grid, 0)));
      assertEquals(
          new TextPosition(0, 3L * columns - 1),
          grid.wrappedWordHit(0, LINES, rows, columns, past, y(grid, 2)));
      // The last segment and a one-row line keep the boundary, as wrappedHit maps it.
      assertEquals(
          new TextPosition(0, 5L * columns),
          grid.wrappedWordHit(0, LINES, rows, columns, past, y(grid, 4)));
      assertEquals(
          new TextPosition(1, columns),
          grid.wrappedWordHit(0, LINES, rows, columns, past, y(grid, 5)));
      // Inside a segment the mapping is wrappedHit's.
      assertEquals(
          grid.wrappedHit(0, LINES, rows, columns, x(grid, 7, 0.25), y(grid, 1)),
          grid.wrappedWordHit(0, LINES, rows, columns, x(grid, 7, 0.25), y(grid, 1)));
    }
  }

  /** Line 2 wrapped in place over 4 rows, the view scrolled 7 columns right. */
  @Test
  void aLineWrappedInPlaceHitsBySegmentAndOtherLinesFromTheFirstColumn() {
    for (double advance : ADVANCES) {
      TextGrid grid = new TextGrid(15, advance);
      int columns = 90;
      final long wrapped = 2;
      TextGrid.WrappedRows rows =
          new TextGrid.WrappedRows() {
            @Override
            public int rows(long line) {
              return line == wrapped ? 4 : 1;
            }
          };
      int at3 = x(grid, 3, 0.25);
      assertEquals(
          new TextPosition(1, 10),
          grid.lineWrapHit(0, LINES, rows, wrapped, columns, 7, at3, y(grid, 1)));
      assertEquals(
          new TextPosition(2, 3),
          grid.lineWrapHit(0, LINES, rows, wrapped, columns, 7, at3, y(grid, 2)));
      assertEquals(
          new TextPosition(2, 2L * columns + 3),
          grid.lineWrapHit(0, LINES, rows, wrapped, columns, 7, at3, y(grid, 4)));
      assertEquals(
          new TextPosition(3, 10),
          grid.lineWrapHit(0, LINES, rows, wrapped, columns, 7, at3, y(grid, 6)));
      int past = x(grid, columns + 5L, 0.25);
      assertEquals(
          new TextPosition(2, columns - 1L),
          grid.lineWrapWordHit(0, LINES, rows, wrapped, columns, 7, past, y(grid, 2)));
      assertEquals(
          new TextPosition(3, columns + 12L),
          grid.lineWrapWordHit(0, LINES, rows, wrapped, columns, 7, past, y(grid, 6)));
      assertEquals(new TextGrid.LineRow(2, 3), grid.lineRow(0, LINES, rows, y(grid, 5)));
      assertEquals(
          new TextGrid.LineRow(LINES, 0), grid.lineRow(LINES - 2, LINES, rows, y(grid, 2)));
    }
  }

  @Test
  void charIndexCountsACodePointAsOneColumn() {
    String text = "a😀b日c";
    assertEquals(0, TextGrid.charIndex(text, 0));
    assertEquals(1, TextGrid.charIndex(text, 1));
    assertEquals(3, TextGrid.charIndex(text, 2));
    assertEquals(4, TextGrid.charIndex(text, 3));
    assertEquals(6, TextGrid.charIndex(text, 5));
    assertEquals(6, TextGrid.charIndex(text, 50));
    assertEquals(0, TextGrid.charIndex("", 3));
  }

  @Test
  void onlyPrintableAsciiSitsOnTheGrid() {
    assertTrue(TextGrid.printableAscii(""));
    assertTrue(TextGrid.printableAscii(" ~at a.B(x) 12"));
    assertFalse(TextGrid.printableAscii("tab\there"));
    assertFalse(TextGrid.printableAscii("cr\r"));
    assertFalse(TextGrid.printableAscii("del\u007f"));
    assertFalse(TextGrid.printableAscii("café"));
    assertFalse(TextGrid.printableAscii("    日本語"));
  }
}
