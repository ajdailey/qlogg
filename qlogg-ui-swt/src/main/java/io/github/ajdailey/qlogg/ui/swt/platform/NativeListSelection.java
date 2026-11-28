package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.widgets.List;

/** Lists on GTK 3, GTK 4 and cocoa: the native selection stays. */
final class NativeListSelection implements ListSelection {
  @Override
  public void draw(List list) {}
}
