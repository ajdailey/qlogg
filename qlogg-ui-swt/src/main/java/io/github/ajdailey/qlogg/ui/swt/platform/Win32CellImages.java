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
 * <p>{@code SWT.MeasureItem} rounds the row height up to a whole number of pixels ({@link
 * Zoom#whole}). SWT rounds each row's top from pixels to points on its own, so at a fractional zoom
 * a row pitch of a fractional pixel count puts the image one pixel higher in some rows than in
 * others.
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
      event.height = Zoom.whole(Math.max(event.height, size.height), Zoom.of(table));
    } else {
      Rectangle cell = item.getBounds(column);
      event.gc.drawImage(
          image,
          cell.x + INSET - margin(image),
          cell.y + Math.max(0, (cell.height - size.height) / 2));
    }
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
