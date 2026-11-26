package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.TableItem;

/**
 * Cell images on GTK and cocoa: {@link TableItem#setImage(int, Image)}. Owner drawing would make
 * GTK draw every cell's text in the system list color read at startup, dark on a dark theme.
 */
final class NativeCellImages implements CellImages {
  private final int column;

  /**
   * Shows images in a column.
   *
   * @param column the column's index
   */
  NativeCellImages(int column) {
    this.column = column;
  }

  @Override
  public void show(TableItem item, Image image) {
    item.setImage(column, image);
  }

  @Override
  public Image image(TableItem item) {
    return item.getImage(column);
  }
}
