package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.widgets.ToolItem;

/** GTK 3, win32 and cocoa: SWT sizes a tool item to its image. */
final class NativeToolItemSizing implements ToolItemSizing {
  /** The one instance. */
  static final NativeToolItemSizing INSTANCE = new NativeToolItemSizing();

  private NativeToolItemSizing() {}

  @Override
  public void keepIconSize(ToolItem item) {}
}
