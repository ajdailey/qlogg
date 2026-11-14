package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.widgets.ToolItem;

/** Gives a tool item room for its image where the SWT backend lays it out too small. */
public interface ToolItemSizing {
  /**
   * Prepares an item for an image of its icon's size; call before {@code ToolItem.setImage}.
   *
   * @param item the item
   */
  void keepIconSize(ToolItem item);

  /**
   * Returns the sizing for the running SWT backend.
   *
   * @return {@link PlatformTraits#toolItemSizing}
   */
  static ToolItemSizing platform() {
    return PlatformTraits.PLATFORM.toolItemSizing();
  }
}
