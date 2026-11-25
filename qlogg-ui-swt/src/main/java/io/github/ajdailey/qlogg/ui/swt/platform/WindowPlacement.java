package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.widgets.Shell;

/**
 * How a main window is sized and positioned from its trim, one implementation per platform from
 * {@link PlatformTraits#windowPlacement}.
 */
public interface WindowPlacement {
  /** The running platform's placement. */
  WindowPlacement PLATFORM = PlatformTraits.PLATFORM.windowPlacement();

  /**
   * Places a shell from the trim {@code computeTrim} reports. win32 places it again with the trim
   * measured from the sized shell, since {@code computeTrim} overstates the height once a menu bar
   * is set, then moves it inside its monitor's work area. GTK under Wayland cannot move a shell, so
   * the placement there sets no location.
   *
   * @param shell the shell
   * @param placer computes and applies a placement
   */
  void place(Shell shell, Placer placer);

  /**
   * Runs after {@code Shell.open}. GTK 4 drops a maximize requested before the shell maps, so its
   * class maximizes a shell that should be maximized and opened restored; the others do nothing.
   *
   * @param shell the opened shell
   * @param maximized {@code getMaximized()} read before {@code open}
   */
  void afterOpen(Shell shell, boolean maximized);

  /** Computes and applies a placement. */
  interface Placer {
    /**
     * Computes a placement for a trim and applies it to the shell.
     *
     * @param trimWidth the trim's width in points
     * @param trimHeight the trim's height in points
     * @param positioned false where the window system ignores a location
     */
    void place(int trimWidth, int trimHeight, boolean positioned);
  }
}
