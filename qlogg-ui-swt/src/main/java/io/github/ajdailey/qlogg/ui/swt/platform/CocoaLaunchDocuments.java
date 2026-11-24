package io.github.ajdailey.qlogg.ui.swt.platform;

import java.util.List;

/** cocoa: the launch waits for the files the system sends as events. */
final class CocoaLaunchDocuments implements LaunchDocuments {
  @Override
  public List<String> await(Wait wait) {
    return wait.run();
  }
}
