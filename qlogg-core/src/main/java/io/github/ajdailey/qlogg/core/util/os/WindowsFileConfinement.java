package io.github.ajdailey.qlogg.core.util.os;

import java.io.IOException;
import java.nio.file.Path;

/** Windows: WebView2 keeps a user data folder. */
final class WindowsFileConfinement implements FileConfinement {
  @Override
  public void confineHome(boolean perUser, Path settings) {}

  @Override
  public void placeBrowserData(BrowserData data) throws IOException {
    System.setProperty(EDGE_DATA_DIR, data.folder().toString());
  }

  @Override
  public boolean embedsGuide(boolean perUser) {
    return true;
  }
}
