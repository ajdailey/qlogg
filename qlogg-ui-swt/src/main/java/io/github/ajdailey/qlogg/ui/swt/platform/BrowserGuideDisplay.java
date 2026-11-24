package io.github.ajdailey.qlogg.ui.swt.platform;

import java.nio.file.Path;

/** win32, cocoa and GTK 3: the embedded browser shows the guide. */
final class BrowserGuideDisplay implements GuideDisplay {
  @Override
  public boolean show(Path file, Shows shows) {
    return shows.inBrowser(file);
  }
}
