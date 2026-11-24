package io.github.ajdailey.qlogg.ui.swt.platform;

import java.nio.file.Path;

/** GTK 4: a notice names the guide's file, and no browser is created. */
final class NoticeGuideDisplay implements GuideDisplay {
  @Override
  public boolean show(Path file, Shows shows) {
    shows.notice(file);
    return true;
  }
}
