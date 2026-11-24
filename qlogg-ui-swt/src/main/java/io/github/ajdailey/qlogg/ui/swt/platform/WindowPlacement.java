package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.widgets.Shell;

/**
 * How a main window is sized and positioned from its trim, one implementation per platform from
 * {@link PlatformTraits#placement}.
 */
public interface WindowPlacement {
  /**
   * Places a shell from the trim {@code computeTrim} reports. win32 places it again with the trim
   * measured from the sized shell, since {@code computeTrim} overstates the height once a menu bar
   * is set. GTK under Wayland cannot move a shell, so the placement there sets no location.
   *
   * @param shell the shell
   * @param placer computes and applies a placement
   */
  void place(Shell shell, Placer placer);

  /** Computes and applies a placement. */
  interface Placer {
    /**
     * Computes a placement for a trim and applies it to the shell.
     *
     * @param trimWidth the trim's width in points
     * @param trimHeight the trim's height in points
     * @param positions true when the shell can be moved to a location
     */
    void place(int trimWidth, int trimHeight, boolean positions);
  }
}
