package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.widgets.Control;

/** The zoom SWT scales a control's points by. */
public final class Zoom {
  private Zoom() {}

  /**
   * Returns the zoom SWT scales a control's points by: a numeric {@code swt.autoScale} value, else
   * the control's monitor zoom.
   *
   * @param control the control
   * @return the zoom in percent
   */
  public static int of(Control control) {
    String scale = System.getProperty("swt.autoScale");
    if (scale != null) {
      try {
        return Integer.parseInt(scale.trim());
      } catch (NumberFormatException named) {
        return control.getMonitor().getZoom();
      }
    }
    return control.getMonitor().getZoom();
  }

  /**
   * Returns the smallest length of at least {@code points} that spans a whole number of pixels at a
   * zoom, so items stacked at that pitch round to pixels the same way.
   *
   * @param points the length in points
   * @param zoom the zoom in percent
   * @return the length in points
   */
  public static int whole(int points, int zoom) {
    int step = 100 / gcd(zoom, 100);
    return (points + step - 1) / step * step;
  }

  private static int gcd(int a, int b) {
    int x = Math.abs(a);
    int y = Math.abs(b);
    while (y != 0) {
      int r = x % y;
      x = y;
      y = r;
    }
    return x == 0 ? 1 : x;
  }
}
