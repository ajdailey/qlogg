package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.widgets.Shell;

/**
 * Keeps a main window above other windows (View > Always on Top), and minimizes a tool window
 * without its owner on cocoa ({@link #minimize}). SWT has no public call for either, so each
 * supported platform has a class that reaches SWT internals by reflection, and the product compiles
 * against every platform's SWT jar. Confined to the UI thread.
 *
 * <table>
 *   <caption>Implementation per platform</caption>
 *   <tr><th>Platform</th><th>Class</th></tr>
 *   <tr><td>win32</td><td>{@link Win32Stacking}</td></tr>
 *   <tr><td>cocoa</td><td>{@link CocoaStacking}</td></tr>
 *   <tr><td>GTK 3 on X11</td><td>{@link GtkX11Stacking}</td></tr>
 *   <tr><td>GTK 3 on Wayland, GTK 4</td><td>{@link #WINDOW_MANAGER}</td></tr>
 * </table>
 */
public interface WindowStacking {
  /** Unsupported on a Linux desktop, whose window manager's window menu offers the setting. */
  WindowStacking WINDOW_MANAGER = new Unsupported(true);

  /**
   * This platform's stacking, chosen on first use. GTK reads the backend the Display records, so
   * the first use follows the Display's creation.
   */
  WindowStacking PLATFORM = forPlatform();

  /**
   * Reports whether {@link #setOnTop} takes effect, and on cocoa {@link #minimize}; false after a
   * reflective call failed.
   *
   * @return true when supported
   */
  boolean supported();

  /**
   * Reports whether the window manager's window menu offers Always on Top where this does not.
   *
   * @return true on GTK 3 under Wayland and on GTK 4
   */
  boolean windowManagerMenu();

  /**
   * Keeps the shell above other windows, or returns it to normal stacking, without moving, resizing
   * or activating it. A failure logs one WARN and makes the stacking unsupported.
   *
   * @param shell a main window's shell
   * @param onTop true to keep it above
   */
  void setOnTop(Shell shell, boolean onTop);

  /**
   * Minimizes a shell (Window &gt; Minimize). On cocoa a tool window first leaves its owner's
   * child-window group, which AppKit otherwise takes off screen with it, and rejoins the group when
   * restored; {@link CocoaStacking} also brings the owner back after a minimize through the title
   * bar, and does nothing while unsupported. Elsewhere {@code setMinimized(true)}.
   *
   * @param shell the shell
   */
  void minimize(Shell shell);

  /**
   * Returns the implementation for the running SWT.
   *
   * @return {@link PlatformTraits#stacking}
   */
  static WindowStacking forPlatform() {
    return PlatformTraits.PLATFORM.stacking();
  }

  /**
   * A stacking with no effect.
   *
   * @param windowManagerMenu what {@link #windowManagerMenu} reports
   */
  record Unsupported(boolean windowManagerMenu) implements WindowStacking {
    @Override
    public boolean supported() {
      return false;
    }

    @Override
    public void setOnTop(Shell shell, boolean onTop) {
      // Nothing to set.
    }

    @Override
    public void minimize(Shell shell) {
      shell.setMinimized(true);
    }
  }
}
