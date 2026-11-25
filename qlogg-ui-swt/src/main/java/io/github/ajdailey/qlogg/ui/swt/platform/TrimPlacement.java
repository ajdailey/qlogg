package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Shell;

/** The trim {@code computeTrim} reports for a 100 x 100 client area. */
final class TrimPlacement {
  private static final int CLIENT = 100;

  private TrimPlacement() {}

  /**
   * Places a shell once from the trim {@code computeTrim} reports.
   *
   * @param shell the shell
   * @param placer computes and applies a placement
   * @param positioned false where the window system ignores a location
   * @return the trim's width and height
   */
  static int[] place(Shell shell, WindowPlacement.Placer placer, boolean positioned) {
    Rectangle trim = shell.computeTrim(0, 0, CLIENT, CLIENT);
    int[] size = {trim.width - CLIENT, trim.height - CLIENT};
    placer.place(size[0], size[1], positioned);
    return size;
  }
}
