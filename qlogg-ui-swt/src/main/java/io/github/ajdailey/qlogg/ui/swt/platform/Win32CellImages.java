package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

/**
 * Cell images on win32, drawn in {@code SWT.PaintItem} {@link #INSET} points into the cell and held
 * as item data. {@link TableItem#setImage(int, Image)} on any column attaches an image list to the
 * list view, which then reserves an empty image slot at the start of column 0 in every row.
 */
final class Win32CellImages implements CellImages {
  /** Points between the cell's left edge and the image. */
  static final int INSET = 3;

  private final String key;
  private final Table table;
  private final int column;

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
    Image image = image((TableItem) event.item);
    if (image == null || image.isDisposed()) {
      return;
    }
    Rectangle size = image.getBounds();
    if (event.type == SWT.MeasureItem) {
      event.width = Math.max(event.width, INSET + size.width);
      event.height = Math.max(event.height, size.height);
    } else {
      event.gc.drawImage(
          image, event.x + INSET, event.y + Math.max(0, (event.height - size.height) / 2));
    }
  }
}
