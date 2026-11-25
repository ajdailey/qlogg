package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.widgets.ToolItem;

/** Gives a tool item room for its image where the SWT backend lays it out too small. */
public interface ToolItemSizing {
  /** The running SWT backend's sizing. */
  ToolItemSizing PLATFORM = PlatformTraits.PLATFORM.toolItemSizing();

  /**
   * Prepares an item for an image of its icon's size; call before {@code ToolItem.setImage}.
   *
   * @param item the item
   */
  void keepIconSize(ToolItem item);
}
