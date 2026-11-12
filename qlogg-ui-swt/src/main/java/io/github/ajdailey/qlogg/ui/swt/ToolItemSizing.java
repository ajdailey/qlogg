package io.github.ajdailey.qlogg.ui.swt;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.ToolItem;

/** Gives a tool item room for its image where the SWT backend lays it out too small. */
interface ToolItemSizing {
  /**
   * Prepares an item for an image of its icon's size; call before {@code ToolItem.setImage}.
   *
   * @param item the item
   */
  void keepIconSize(ToolItem item);

  /**
   * Returns the sizing for the running SWT backend.
   *
   * @return {@link Gtk4ToolItemSizing} on GTK 4, else one that changes nothing
   */
  static ToolItemSizing platform() {
    if ("gtk".equals(SWT.getPlatform()) && "1".equals(System.getenv("SWT_GTK4"))) {
      return Gtk4ToolItemSizing.create();
    }
    return NativeToolItemSizing.INSTANCE;
  }
}
