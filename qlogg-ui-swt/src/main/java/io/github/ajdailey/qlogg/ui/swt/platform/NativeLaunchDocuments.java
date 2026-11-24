package io.github.ajdailey.qlogg.ui.swt.platform;

import java.util.ArrayList;
import java.util.List;

/**
 * win32 and GTK: every file is an argument, so the launch waits for none and creates no Display.
 */
final class NativeLaunchDocuments implements LaunchDocuments {
  @Override
  public List<String> await(Wait wait) {
    return new ArrayList<String>();
  }
}
