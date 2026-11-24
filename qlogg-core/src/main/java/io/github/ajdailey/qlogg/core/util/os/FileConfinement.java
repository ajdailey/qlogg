package io.github.ajdailey.qlogg.core.util.os;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Keeping the files SWT and the embedded browser write inside the settings directory, one
 * implementation per system. The launcher calls every method on every system before the Display
 * exists.
 */
public interface FileConfinement {
  /** The SWT property naming the WebView2 user data folder. */
  String EDGE_DATA_DIR = "org.eclipse.swt.browser.EdgeDataDir";

  /**
   * Moves {@code user.home} to the settings directory in explicit and portable mode on Linux, so
   * GTK SWT writes its {@code .swt/trims.prefs} there instead of the user's home. Does nothing
   * elsewhere.
   *
   * @param perUser true in per-user mode
   * @param settings the settings directory
   */
  void confineHome(boolean perUser, Path settings);

  /**
   * Sets {@link #EDGE_DATA_DIR} on Windows, in every mode, so the WebView2 browser of the user
   * guide writes its data inside the settings directory. Does nothing elsewhere.
   *
   * @param data returns the folder
   * @throws IOException when {@code data} cannot name the folder
   */
  void placeBrowserData(BrowserData data) throws IOException;

  /**
   * Reports whether Help > User Guide shows the copy in the embedded browser. On Linux in explicit
   * and portable mode it does not: WebKitGTK and Mesa write their cache and data under the user's
   * home, outside the settings directory.
   *
   * @param perUser true in per-user mode
   * @return false on Linux outside per-user mode
   */
  boolean embedsGuide(boolean perUser);

  /** Names the WebView2 data folder. */
  interface BrowserData {
    /**
     * Returns the folder.
     *
     * @return {@code <settings>/cache/webview2}
     * @throws IOException when the folder cannot be named
     */
    Path folder() throws IOException;
  }
}
