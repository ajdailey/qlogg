package io.github.ajdailey.qlogg.launcher;

import io.github.ajdailey.qlogg.app.about.UserGuide;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Process-wide settings applied before the Display exists. */
final class PlatformSetup {
  /** The SWT property naming the WebView2 user data folder. */
  static final String EDGE_DATA_DIR = "org.eclipse.swt.browser.EdgeDataDir";

  /** The WebView2 folder's name in the settings directory's cache folder. */
  static final String WEBVIEW2 = "webview2";

  private static final Logger LOG = LoggerFactory.getLogger(PlatformSetup.class);

  private PlatformSetup() {}

  /**
   * In explicit and portable mode on Linux, sets {@code user.home} to the settings directory so GTK
   * SWT writes its {@code .swt/trims.prefs} there instead of the user's home. On Windows, in every
   * mode, sets {@link #EDGE_DATA_DIR} to {@code <settings>/cache/webview2}, so the WebView2 browser
   * of the user guide writes its data inside the settings directory.
   *
   * @param os the system
   * @param settings the settings directory
   */
  static void apply(OsTraits os, SettingsLocation settings) {
    if (settings.mode() != SettingsMode.PER_USER && os.confinesToSettings()) {
      System.setProperty("user.home", settings.directory().toString());
    }
    if (os.webView2()) {
      try {
        System.setProperty(
            EDGE_DATA_DIR,
            SafePaths.child(SafePaths.child(settings.directory(), UserGuide.FOLDER), WEBVIEW2)
                .toString());
      } catch (IOException e) {
        LOG.warn("No WebView2 data folder: {}", LogText.oneLine(e.getMessage()));
      }
    }
  }
}
