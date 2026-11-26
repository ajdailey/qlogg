package io.github.ajdailey.qlogg.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.ui.swt.ScaleSlack;
import java.time.Duration;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
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
  void theBoxSitsInsetPointsIntoEveryCellAndCentered() {
    assumeTrue(
        "win32".equals(SWT.getPlatform()) || System.getProperty("swt.autoScale") == null,
        "only win32 scales widgets by a numeric swt.autoScale");
    Shell shell = new Shell(display);
    shell.setLayout(new FillLayout());
    Table table = new Table(shell, SWT.BORDER | SWT.FULL_SELECTION);
    table.setHeaderVisible(true);
    table.setLinesVisible(true);
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
        int bottom = pixels.pixels(cell.y + cell.height);
        int[] drawn = boxBounds(pixels, left, top, pixels.pixels(cell.x + cell.width), bottom);
        assumeTrue(drawn != null || i > 0, "a screen read shows the table");
        assertTrue(drawn != null, "row " + i + " shows no box");
        int x = drawn[0] - left;
        assertTrue(Math.abs(x - inset) <= slack, "row " + i + ": box " + x + " px into the cell");
        int above = drawn[1] - top;
        int under = bottom - drawn[2] - 1;
        assertTrue(
            Math.abs(above - under) <= 1 + slack,
            "row " + i + ": " + above + " px above the box, " + under + " below");
        if (firstTop < 0) {
          firstTop = above;
        }
        assertEquals(firstTop, above, "row " + i + " offset matches row 0");
      }
    } finally {
      box.dispose();
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
