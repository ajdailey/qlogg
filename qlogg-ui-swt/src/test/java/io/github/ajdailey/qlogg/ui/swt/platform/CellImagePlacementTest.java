package io.github.ajdailey.qlogg.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.ui.swt.ScaleSlack;
import java.time.Duration;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Where {@link Win32CellImages} draws, read from the screen on every platform: the image's first
 * opaque column {@link Win32CellImages#INSET} points right of the cell's left edge, centered in the
 * row, at the same offset in every row; the pom runs it again at {@code -Dswt.autoScale=150}. Also
 * the transparent margin it subtracts and the row pitch {@link Zoom#whole} rounds to.
 */
@Tag("swt")
class CellImagePlacementTest {
  private static final Duration BOUND = Duration.ofSeconds(5);
  private static final RGB BOX = new RGB(0xd0, 0x20, 0x20);
  private static final int TOLERANCE = 24;

  /** Transparent columns and rows around the drawn box, as the toolkit's check box icons have. */
  private static final int PADDING = 2;

  private static final int SIZE = 16;

  private Display display;

  @BeforeEach
  void open() {
    display = new Display();
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  @Test
  void theMarginCountsTransparentColumnsOnly() {
    assertEquals(PADDING, Win32CellImages.leftMargin(box()));
    ImageData opaque = new ImageData(4, 4, 24, new PaletteData(0xff0000, 0xff00, 0xff));
    assertEquals(0, Win32CellImages.leftMargin(opaque));
    ImageData keyed = new ImageData(4, 4, 24, new PaletteData(0xff0000, 0xff00, 0xff));
    keyed.transparentPixel = 0;
    keyed.setPixel(3, 1, 0xffffff);
    assertEquals(3, Win32CellImages.leftMargin(keyed));
  }

  @Test
  void aRowPitchSpansWholePixels() {
    assertEquals(21, Zoom.whole(21, 100));
    assertEquals(21, Zoom.whole(21, 200));
    assertEquals(22, Zoom.whole(21, 150));
    assertEquals(22, Zoom.whole(22, 150));
    assertEquals(24, Zoom.whole(21, 125));
    assertEquals(24, Zoom.whole(21, 175));
    for (int zoom : new int[] {100, 125, 150, 175, 200, 225, 250}) {
      for (int points = 1; points < 40; points++) {
        int whole = Zoom.whole(points, zoom);
        assertTrue(whole >= points && whole * zoom % 100 == 0, points + " pt at " + zoom);
      }
    }
  }

  @Test
  void aGridLineCountsTowardThePitch() {
    assertEquals(18, Win32CellImages.pitch(16, true, 150), "24 px cell under a line: 25 px pitch");
    assertEquals(18, Win32CellImages.pitch(17, false, 150));
    assertEquals(20, Win32CellImages.pitch(19, true, 150), "28 px cell under a line: 29 px pitch");
    assertEquals(20, Win32CellImages.pitch(19, false, 150));
    assertEquals(19, Win32CellImages.pitch(18, true, 100));
    assertEquals(19, Win32CellImages.pitch(19, false, 100));
  }

  /**
   * A light table shows grid lines and a dark one draws its own, so both themes must give the same
   * whole-pixel pitch. A larger font makes the native row taller than the image, so the pitch comes
   * from {@link Win32CellImages#pitch}.
   */
  @Test
  void theRowPitchIsTheSameWithAndWithoutGridLines() {
    assumeTrue("win32".equals(SWT.getPlatform()), "only win32 draws the images in MeasureItem");
    Shell shell = new Shell(display);
    shell.setLayout(new FillLayout());
    Font font = null;
    Image box = new Image(display, box());
    try {
      int[] pitches = new int[2];
      for (int pass = 0; pass < 2; pass++) {
        Table table = new Table(shell, SWT.BORDER | SWT.FULL_SELECTION);
        if (font == null) {
          font = new Font(display, table.getFont().getFontData()[0].getName(), 12, SWT.NORMAL);
        }
        table.setFont(font);
        table.setHeaderVisible(true);
        table.setLinesVisible(pass == 0);
        new TableColumn(table, SWT.LEFT).setWidth(90);
        new TableColumn(table, SWT.LEFT).setWidth(90);
        Win32CellImages cells = new Win32CellImages(table, 1);
        for (int i = 0; i < 7; i++) {
          TableItem item = new TableItem(table, SWT.NONE);
          item.setText(new String[] {"mock " + i, ""});
          cells.show(item, box);
        }
      }
      shell.setSize(400, 600);
      shell.open();
      ScreenPixels.read(shell, BOUND);
      Control[] tables = shell.getChildren();
      for (int pass = 0; pass < 2; pass++) {
        Table table = (Table) tables[pass];
        pitches[pass] = table.getItem(6).getBounds().y - table.getItem(0).getBounds().y;
        int zoom = Zoom.of(table);
        assertEquals(0, pitches[pass] * zoom % 600, "six rows span whole pixels at " + zoom);
      }
      assertEquals(pitches[0], pitches[1], "six rows with and without grid lines");
    } finally {
      box.dispose();
      if (font != null) {
        font.dispose();
      }
    }
  }

  @Test
  void theBoxSitsInsetPointsIntoEveryCellAndCentered() {
    assumeTrue(
        "win32".equals(SWT.getPlatform()) || System.getProperty("swt.autoScale") == null,
        "only win32 scales widgets by a numeric swt.autoScale");
    placeBoxes(false, 0);
    placeBoxes(false, 12);
    // Elsewhere the measured row holds the grid line, so the pitch would grow at each measure.
    if ("win32".equals(SWT.getPlatform())) {
      placeBoxes(true, 0);
      placeBoxes(true, 12);
    }
  }

  /**
   * Checks the box in each of five rows; without grid lines the cell's last pixel row is left out,
   * as {@link Win32GridLines} draws its rule there. A 12 pt font makes the row taller than the box.
   */
  private void placeBoxes(boolean lines, int points) {
    Shell shell = new Shell(display);
    shell.setLayout(new FillLayout());
    Table table = new Table(shell, SWT.BORDER | SWT.FULL_SELECTION);
    table.setHeaderVisible(true);
    table.setLinesVisible(lines);
    Font font = null;
    if (points > 0) {
      font = new Font(display, table.getFont().getFontData()[0].getName(), points, SWT.NORMAL);
      table.setFont(font);
    }
    for (int i = 0; i < 3; i++) {
      new TableColumn(table, SWT.LEFT).setWidth(90);
    }
    Win32CellImages cells = new Win32CellImages(table, 2);
    Image box = new Image(display, box());
    try {
      for (int i = 0; i < 5; i++) {
        TableItem item = new TableItem(table, SWT.NONE);
        item.setText(new String[] {"mock " + i, "flagword", ""});
        cells.show(item, box);
      }
      shell.setSize(400, 300);
      shell.open();
      ScreenPixels pixels = ScreenPixels.read(table, BOUND);
      int slack = pixels.pixels(Math.max(ScaleSlack.of(table), Zoom.of(table) % 100 == 0 ? 0 : 1));
      int inset = pixels.pixels(Win32CellImages.INSET);
      int firstTop = -1;
      for (int i = 0; i < table.getItemCount(); i++) {
        Rectangle cell = table.getItem(i).getBounds(2);
        int left = pixels.pixels(cell.x);
        int top = pixels.pixels(cell.y);
        int bottom = pixels.pixels(cell.y + cell.height) - (lines ? 0 : 1);
        int[] drawn = boxBounds(pixels, left, top, pixels.pixels(cell.x + cell.width), bottom);
        assumeTrue(drawn != null || i > 0, "a screen read shows the table");
        String row = (lines ? "lines, " : "no lines, ") + points + " pt, row " + i;
        assertTrue(drawn != null, row + " shows no box");
        int x = drawn[0] - left;
        assertTrue(Math.abs(x - inset) <= slack, row + ": box " + x + " px into the cell");
        int above = drawn[1] - top;
        int under = bottom - drawn[2] - 1;
        assertTrue(
            Math.abs(above - under) <= 1 + slack,
            row + ": " + above + " px above the box, " + under + " below");
        if (firstTop < 0) {
          firstTop = above;
        }
        assertEquals(firstTop, above, row + " offset matches row 0");
      }
    } finally {
      box.dispose();
      shell.dispose();
      if (font != null) {
        font.dispose();
      }
    }
  }

  /** Returns the left column, top row and bottom row of the box in a cell, or null. */
  private static int[] boxBounds(ScreenPixels pixels, int left, int top, int right, int bottom) {
    int first = -1;
    int high = -1;
    int low = -1;
    for (int x = left; x < Math.min(right, pixels.width()); x++) {
      for (int y = top; y < Math.min(bottom, pixels.height()); y++) {
        if (ScreenPixels.near(pixels.at(x, y), BOX, TOLERANCE)) {
          if (first < 0) {
            first = x;
          }
          high = high < 0 ? y : Math.min(high, y);
          low = Math.max(low, y);
        }
      }
    }
    return first < 0 ? null : new int[] {first, high, low};
  }

  /** A box outline {@link #PADDING} pixels in from each edge of a transparent square. */
  private static ImageData box() {
    ImageData data = new ImageData(SIZE, SIZE, 24, new PaletteData(0xff0000, 0xff00, 0xff));
    data.alphaData = new byte[SIZE * SIZE];
    int ink = data.palette.getPixel(BOX);
    int last = SIZE - 1 - PADDING;
    for (int y = PADDING; y <= last; y++) {
      for (int x = PADDING; x <= last; x++) {
        if (x == PADDING || x == last || y == PADDING || y == last) {
          data.setPixel(x, y, ink);
          data.setAlpha(x, y, 0xff);
        }
      }
    }
    return data;
  }
}
