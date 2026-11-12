package io.github.ajdailey.qlogg.ui.swt;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Shell;

/**
 * Keeps a main window above other windows (View > Always on Top). SWT has no public call for it, so
 * each supported platform has a class that reaches SWT internals by reflection, and the product
 * compiles against every platform's SWT jar. Confined to the UI thread.
 *
 * <table>
 *   <caption>Implementation per platform</caption>
 *   <tr><th>Platform</th><th>Class</th></tr>
 *   <tr><td>win32</td><td>{@link Win32Stacking}</td></tr>
 *   <tr><td>cocoa</td><td>{@link CocoaStacking}</td></tr>
 *   <tr><td>GTK 3 on X11</td><td>{@link GtkX11Stacking}</td></tr>
 *   <tr><td>GTK 3 on Wayland, GTK 4</td><td>{@link #WINDOW_MANAGER}</td></tr>
 *   <tr><td>Any other</td><td>{@link #NONE}</td></tr>
 * </table>
 */
interface WindowStacking {
  /** Unsupported; {@link #setOnTop} does nothing. */
  WindowStacking NONE = new Unsupported(false);

  /** Unsupported on a Linux desktop, whose window manager's window menu offers the setting. */
  WindowStacking WINDOW_MANAGER = new Unsupported(true);

  /**
   * This platform's stacking, chosen on first use. GTK reads the backend the Display records, so
   * the first use follows the Display's creation.
   */
  WindowStacking PLATFORM = forPlatform();

  /**
   * Reports whether {@link #setOnTop} takes effect; false after a reflective call failed.
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
   * Returns the implementation for the running SWT.
   *
   * @return the stacking
   */
  static WindowStacking forPlatform() {
    return switch (SWT.getPlatform()) {
      case "win32" -> new Win32Stacking();
      case "cocoa" -> new CocoaStacking();
      case "gtk" ->
          "1".equals(System.getenv("SWT_GTK4"))
                  || !"x11".equals(System.getProperty(MainWindow.GDK_BACKEND))
              ? WINDOW_MANAGER
              : new GtkX11Stacking();
      default -> NONE;
    };
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
  }
}
