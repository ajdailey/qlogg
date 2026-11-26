package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.TableItem;

/**
 * The images of one table column other than the first, at the cell's left: {@link NativeCellImages}
 * sets them on the items; {@link Win32CellImages} draws them, since win32 gives column 0 an empty
 * image slot in every row once any item holds an image. A table takes one from {@link
 * PlatformTraits#cellImages}. Confined to the UI thread.
 */
public interface CellImages {
  /**
   * Shows an image in a row's cell.
   *
   * @param item the row
   * @param image the image, or null for none
   */
  void show(TableItem item, Image image);

  /**
   * Returns the image a row's cell shows.
   *
   * @param item the row
   * @return the image {@link #show} took, or null
   */
  Image image(TableItem item);
}
