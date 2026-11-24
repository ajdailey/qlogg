package io.github.ajdailey.qlogg.core.util.os;

import java.nio.file.Path;

/** Linux: GTK SWT and WebKitGTK write under the user's home. */
final class LinuxFileConfinement implements FileConfinement {
  @Override
  public void confineHome(boolean perUser, Path settings) {
    if (!perUser) {
      System.setProperty("user.home", settings.toString());
    }
  }

  @Override
  public void placeBrowserData(BrowserData data) {}

  @Override
  public boolean embedsGuide(boolean perUser) {
    return perUser;
  }
}
