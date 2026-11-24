package io.github.ajdailey.qlogg.launcher;

import io.github.ajdailey.qlogg.app.about.UserGuide;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.util.os.FileConfinement;
import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Process-wide settings applied before the Display exists. */
final class PlatformSetup {
  /** The WebView2 folder's name in the settings directory's cache folder. */
  static final String WEBVIEW2 = "webview2";

  private static final Logger LOG = LoggerFactory.getLogger(PlatformSetup.class);

  private PlatformSetup() {}

  /**
   * Keeps the files GTK SWT and the user guide's WebView2 browser write inside the settings
   * directory ({@link FileConfinement}): the WebView2 data folder is {@code
   * <settings>/cache/webview2}.
   *
   * @param os the system
   * @param settings the settings directory
   */
  static void apply(OsTraits os, final SettingsLocation settings) {
    FileConfinement confinement = os.confinement();
    confinement.confineHome(settings.mode() == SettingsMode.PER_USER, settings.directory());
    try {
      confinement.placeBrowserData(
          new FileConfinement.BrowserData() {
            @Override
            public Path folder() throws IOException {
              return SafePaths.child(
                  SafePaths.child(settings.directory(), UserGuide.FOLDER), WEBVIEW2);
            }
          });
    } catch (IOException e) {
      LOG.warn("No WebView2 data folder: {}", LogText.oneLine(e.getMessage()));
    }
  }
}
