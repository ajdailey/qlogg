package io.github.ajdailey.qlogg.ui.swt.platform;

import java.util.HashMap;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

/**
 * Cell images on win32, held as item data and drawn in {@code SWT.PaintItem} so the first opaque
 * column of the image sits {@link #INSET} points right of the cell's left edge, centered in the
 * row. {@link TableItem#setImage(int, Image)} on any column attaches an image list to the list
 * view, which then reserves an empty image slot at the start of column 0 in every row.
 *
 * <p>{@code SWT.MeasureItem} rounds the row pitch up to a whole number of points that spans whole
 * pixels ({@link #pitch}). SWT rounds each row's top from pixels to points on its own, so at a
 * fractional zoom a pitch of a fractional point count puts the image one pixel higher in some rows
 * than in others.
 */
final class Win32CellImages implements CellImages {
  /** Points between the cell's left edge and the image's first opaque column. */
  static final int INSET = 3;

  /** Lowest alpha of a pixel counted as drawn. */
  private static final int OPAQUE = 128;

  private final String key;
  private final Table table;
  private final int column;
  private final Map<Image, Integer> margins = new HashMap<Image, Integer>();

  /**
   * Draws the images of a column.
   *
   * @param table the table
   * @param column the column's index
   */
  Win32CellImages(Table table, int column) {
    this.table = table;
    this.column = column;
    this.key = Win32CellImages.class.getName() + column;
    Listener cells =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.index == Win32CellImages.this.column) {
              cell(event);
            }
          }
        };
    table.addListener(SWT.MeasureItem, cells);
    table.addListener(SWT.PaintItem, cells);
  }

  @Override
  public void show(TableItem item, Image image) {
    item.setData(key, image);
    Rectangle bounds = item.getBounds(column);
    table.redraw(bounds.x, bounds.y, bounds.width, bounds.height, false);
  }

  @Override
  public Image image(TableItem item) {
    return (Image) item.getData(key);
  }

  private void cell(Event event) {
    TableItem item = (TableItem) event.item;
    Image image = image(item);
    if (image == null || image.isDisposed()) {
      return;
    }
    Rectangle size = image.getBounds();
    if (event.type == SWT.MeasureItem) {
      event.width = Math.max(event.width, INSET + size.width);
      int zoom = Zoom.of(table);
      event.height =
          Zoom.whole(
              Math.max(pitch(event.height, table.getLinesVisible(), zoom), size.height), zoom);
    } else {
      Rectangle cell = item.getBounds(column);
      event.gc.drawImage(image, cell.x + INSET - margin(image), cell.y + top(cell, size));
    }
  }

  /**
   * Returns the row pitch to request from a {@code SWT.MeasureItem} height: the smallest {@link
   * Zoom#whole} pitch that holds the row. With grid lines showing, the measured height leaves out
   * the line's pixel while SWT grows the row only past the pitch that includes it, so the pixel is
   * added back; without it a light table keeps its native, fractional pitch while a dark one, with
   * its lines off, takes a whole one (measured on {@code windows-2025} at 150%: 25 px against 27).
   *
   * @param points the measured height
   * @param lines whether the table shows grid lines
   * @param zoom the zoom in percent
   * @return the pitch in points
   */
  static int pitch(int points, boolean lines, int zoom) {
    int hundredths = points * zoom + (lines ? 100 : 0);
    return Zoom.whole((hundredths + zoom - 1) / zoom, zoom);
  }

  /**
   * Returns the image's offset below the cell's top, in points, centering it in the cell's pixel
   * rows: the cell without the native grid line when lines show, or without its last pixel row,
   * where {@link Win32GridLines} draws its rule, when they do not. Half the free pixels round to
   * the nearest point, so at 150% the two gaps can differ by 2 px: a point is 1.5 px.
   */
  private int top(Rectangle cell, Rectangle size) {
    int zoom = Zoom.of(table);
    int rows = Math.round(cell.height * zoom / 100f);
    if (!table.getLinesVisible()) {
      rows--;
    }
    int free = rows - Math.round(size.height * zoom / 100f);
    if (free <= 0) {
      return 0;
    }
    int half = free / 2;
    return (half * 200 + zoom) / (2 * zoom);
  }

  /**
   * Returns the transparent columns at an image's left, in points, at most {@link #INSET}.
   *
   * @param image the image
   * @return the margin
   */
  private int margin(Image image) {
    Integer known = margins.get(image);
    if (known == null) {
      known = Math.min(INSET, Win32CellImages.leftMargin(image.getImageData()));
      margins.put(image, known);
    }
    return known;
  }

  /**
   * Returns the count of columns at the left of an image with no drawn pixel: alpha below {@link
   * #OPAQUE}, the transparent pixel, or masked out.
   *
   * @param data the image at 100% zoom
   * @return the count, the width for an image with no drawn pixel
   */
  static int leftMargin(ImageData data) {
    ImageData mask =
        data.alphaData == null && data.alpha == -1 && data.transparentPixel == -1
            ? data.getTransparencyMask()
            : null;
    for (int x = 0; x < data.width; x++) {
      for (int y = 0; y < data.height; y++) {
        if (drawn(data, mask, x, y)) {
          return x;
        }
      }
    }
    return data.width;
  }

  private static boolean drawn(ImageData data, ImageData mask, int x, int y) {
    if (data.alphaData != null || data.alpha != -1) {
      return data.getAlpha(x, y) >= OPAQUE;
    }
    if (data.transparentPixel != -1) {
      return data.getPixel(x, y) != data.transparentPixel;
    }
    return mask.getPixel(x, y) != 0;
  }
}
