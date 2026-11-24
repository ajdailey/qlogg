package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import org.eclipse.swt.widgets.Display;

/**
 * cocoa: native controls always follow the system appearance, and {@link CocoaChrome} recolors the
 * shells. Do not pin the cocoa appearance: cocoa SWT reads its widget colors once, under the system
 * appearance, and an application appearance that differs draws text in its own appearance over
 * those backgrounds, white on light gray or black on dark gray.
 */
final class CocoaThemeFollow implements ThemeFollow {
  private final Display display;
  private CocoaChrome chrome;

  CocoaThemeFollow(Display display) {
    this.display = display;
  }

  @Override
  public boolean start(ThemeMode mode) {
    return false;
  }

  @Override
  public void watch(boolean dark, UiDispatcher timers, Runnable refresh) {
    chrome = new CocoaChrome(display, dark);
    NativeTheme.poll(display, timers, refresh);
  }

  @Override
  public void systemChanged(boolean dark) {
    if (chrome != null) {
      chrome.apply(dark);
    }
  }

  @Override
  public void preferDark(boolean dark) {}

  @Override
  public void showOnWindows(boolean dark, WindowRebuild windows) {}
}
