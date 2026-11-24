package io.github.ajdailey.qlogg.ui.swt.platform;

import java.nio.file.Path;

/**
 * Where Help > User Guide shows the guide, one implementation per platform from {@link
 * PlatformTraits#guideDisplay}.
 */
public interface GuideDisplay {
  /**
   * Shows the guide in an embedded browser, or, on GTK 4, a notice naming the file with no {@code
   * Browser} created: WebKitGTK 6.0 aborts the JVM when the browser starts under GTK 4 ("Failed to
   * fully launch dbus-proxy").
   *
   * @param file the guide
   * @param shows shows it
   * @return false when the browser could not be created
   */
  boolean show(Path file, Shows shows);

  /** Shows the guide. */
  interface Shows {
    /**
     * Shows the guide in an embedded browser.
     *
     * @param file the guide
     * @return false when the browser could not be created
     */
    boolean inBrowser(Path file);

    /**
     * Shows a notice naming the guide's file.
     *
     * @param file the guide
     */
    void notice(Path file);
  }
}
