package io.github.ajdailey.qlogg.ui.swt;

import static io.github.ajdailey.qlogg.ui.swt.Pixels.contrast;
import static io.github.ajdailey.qlogg.ui.swt.Pixels.mode;
import static io.github.ajdailey.qlogg.ui.swt.Pixels.strongest;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.TabFolder;

/**
 * A dialog's tab folder as the color tests see it: a native {@link TabFolder}, or the flat,
 * borderless {@link CTabFolder} of {@code Win32DialogTabs}. The scratchpad's bordered text tabs are
 * not one.
 */
final class DialogTabReads {
  private DialogTabReads() {}

  /** Captures an area of the screen. */
  interface Screen {
    /**
     * Returns the pixels of an area.
     *
     * @param area the area in display coordinates
     * @return the pixels
     */
    ImageData capture(Rectangle area);
  }

  /**
   * A folder read on screen.
   *
   * @param row the empty part of the tab row, or null where the tabs fill it
   * @param edge the folder's left edge below the row, where the native folder draws its frame
   * @param ink the selected tab's text
   * @param ground the selected tab's background
   * @param contrast the WCAG contrast of {@code ink} on {@code ground}
   */
  record Reading(RGB row, RGB edge, RGB ink, RGB ground, double contrast) {}

  /**
   * Reads a folder's row, edge and selected tab.
   *
   * @param tabs the folder, shown
   * @param screen captures the screen
   * @return the reading
   */
  static Reading read(Composite tabs, Screen screen) {
    Rectangle last = itemBounds(tabs, count(tabs) - 1);
    Rectangle selected = itemBounds(tabs, selection(tabs));
    Point size = tabs.getSize();
    int rowX = last.x + last.width + 4;
    RGB row = null;
    if (size.x - 4 - rowX > 0) {
      row = mode(screen.capture(area(tabs, rowX, last.y + 3, size.x - 4 - rowX, last.height - 6)));
    }
    RGB edge = mode(screen.capture(area(tabs, 0, last.y + last.height + 10, 2, 20)));
    ImageData tab =
        screen.capture(area(tabs, selected.x, selected.y, selected.width, selected.height));
    RGB ground = mode(tab);
    RGB ink = strongest(tab, ground);
    return new Reading(row, edge, ink, ground, contrast(ink, ground));
  }

  /** Returns a composite's first child that is a dialog tab folder, or null. */
  static Composite find(Composite parent) {
    for (Control child : parent.getChildren()) {
      if (isTabs(child)) {
        return (Composite) child;
      }
    }
    return null;
  }

  static boolean isTabs(Control control) {
    return control instanceof TabFolder
        || (control instanceof CTabFolder
            && (control.getStyle() & SWT.FLAT) != 0
            && (control.getStyle() & SWT.BORDER) == 0);
  }

  static int count(Composite tabs) {
    return tabs instanceof CTabFolder folder
        ? folder.getItemCount()
        : ((TabFolder) tabs).getItemCount();
  }

  static int selection(Composite tabs) {
    return tabs instanceof CTabFolder folder
        ? folder.getSelectionIndex()
        : ((TabFolder) tabs).getSelectionIndex();
  }

  static void select(Composite tabs, int index) {
    if (tabs instanceof CTabFolder folder) {
      folder.setSelection(index);
    } else {
      ((TabFolder) tabs).setSelection(index);
    }
  }

  static Control selectedPage(Composite tabs) {
    return tabs instanceof CTabFolder folder
        ? folder.getSelection().getControl()
        : ((TabFolder) tabs).getItem(selection(tabs)).getControl();
  }

  private static Rectangle itemBounds(Composite tabs, int index) {
    return tabs instanceof CTabFolder folder
        ? folder.getItem(index).getBounds()
        : ((TabFolder) tabs).getItem(index).getBounds();
  }

  private static Rectangle area(Control control, int x, int y, int width, int height) {
    Point origin = control.toDisplay(x, y);
    return new Rectangle(origin.x, origin.y, width, height);
  }
}
