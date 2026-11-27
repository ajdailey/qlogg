package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Link;

/**
 * win32: controls read the theme setting only when created, so it is set before the first Shell and
 * a later switch sets it again, flushes the menu theme ({@link Win32MenuThemes}) and rebuilds the
 * windows.
 */
final class Win32ThemeFollow implements ThemeFollow {
  private final Display display;

  Win32ThemeFollow(Display display) {
    this.display = display;
  }

  @Override
  public boolean start(ThemeMode mode) {
    return NativeTheme.set(display, mode);
  }

  @Override
  public void watch(boolean dark, UiDispatcher timers, Runnable refresh) {
    NativeTheme.poll(display, timers, refresh);
  }

  @Override
  public void systemChanged(boolean dark) {}

  @Override
  public void preferDark(boolean dark) {}

  @Override
  public void showOnWindows(boolean dark, WindowRebuild windows) {
    if (display.isDisposed() || dark == DialogColors.nativeDark(display)) {
      return;
    }
    NativeTheme.set(display, dark);
    Win32MenuThemes.flush(display, dark);
    windows.rebuild();
  }

  @Override
  public void settingsChanged() {}

  @Override
  public void colorLink(Link link) {}
}
