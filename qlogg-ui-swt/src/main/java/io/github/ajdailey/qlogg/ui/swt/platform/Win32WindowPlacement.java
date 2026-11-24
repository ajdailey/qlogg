package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Shell;

/** win32: a second placement when the sized shell's trim differs from the computed one. */
final class Win32WindowPlacement implements WindowPlacement {
  @Override
  public void place(Shell shell, Placer placer) {
    int[] computed = TrimPlacement.place(shell, placer, true);
    Point size = shell.getSize();
    Rectangle client = shell.getClientArea();
    int trimWidth = size.x - client.width;
    int trimHeight = size.y - client.height;
    if (trimWidth != computed[0] || trimHeight != computed[1]) {
      placer.place(trimWidth, trimHeight, true);
    }
  }
}
