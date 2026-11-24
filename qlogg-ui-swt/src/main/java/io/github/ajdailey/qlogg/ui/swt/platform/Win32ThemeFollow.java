package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import org.eclipse.swt.widgets.Display;

/** win32: controls read the theme setting only when created, so it is set once at startup. */
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
  public boolean showsNow(boolean dark, boolean nativeDark) {
    return dark == nativeDark;
  }
}
