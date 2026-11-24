package io.github.ajdailey.qlogg.core.util.os;

import java.nio.file.Path;

/** macOS: SWT and WebKit write nothing under the user's home that needs moving. */
final class MacFileConfinement implements FileConfinement {
  @Override
  public void confineHome(boolean perUser, Path settings) {}

  @Override
  public void placeBrowserData(BrowserData data) {}

  @Override
  public boolean embedsGuide(boolean perUser) {
    return true;
  }
}
