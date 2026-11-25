package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Shell;

/**
 * win32: a second placement when the sized shell's trim differs from the computed one, then the
 * shell moved inside its monitor's work area. A placement without a location keeps the position
 * Windows chose at creation (for example 114,114), which can put a tall window's bottom under the
 * taskbar.
 */
final class Win32WindowPlacement implements WindowPlacement {
  @Override
  public void place(Shell shell, Placer placer) {
    placeTrim(shell, placer);
    fit(shell, shell.getMonitor().getClientArea());
  }

  /**
   * Fits a shell's bounds into a work area, size first, then location.
   *
   * @param shell the shell
   * @param workArea the work area
   */
  static void fit(Shell shell, Rectangle workArea) {
    Rectangle bounds = shell.getBounds();
    WindowGeometry.Rect fitted =
        WindowGeometry.fit(
            new WindowGeometry.Rect(bounds.x, bounds.y, bounds.width, bounds.height),
            new WindowGeometry.Rect(workArea.x, workArea.y, workArea.width, workArea.height));
    if (fitted.x() != bounds.x
        || fitted.y() != bounds.y
        || fitted.width() != bounds.width
        || fitted.height() != bounds.height) {
      shell.setBounds(fitted.x(), fitted.y(), fitted.width(), fitted.height());
    }
  }

  private static void placeTrim(Shell shell, Placer placer) {
    int[] computed = TrimPlacement.place(shell, placer, true);
    Point size = shell.getSize();
    Rectangle client = shell.getClientArea();
    int trimWidth = size.x - client.width;
    int trimHeight = size.y - client.height;
    if (trimWidth != computed[0] || trimHeight != computed[1]) {
      placer.place(trimWidth, trimHeight, true);
    }
  }

  @Override
  public void afterOpen(Shell shell, boolean maximized) {}
}
