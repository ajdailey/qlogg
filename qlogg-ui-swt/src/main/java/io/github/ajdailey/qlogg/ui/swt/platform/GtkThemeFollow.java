package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import org.eclipse.swt.widgets.Display;

/**
 * GTK: the theme applies to existing controls, and the system setting is read once when the Display
 * is created, so a change made while QLogg runs applies at the next start.
 */
final class GtkThemeFollow implements ThemeFollow {
  private final Display display;

  GtkThemeFollow(Display display) {
    this.display = display;
  }

  @Override
  public boolean start(ThemeMode mode) {
    return mode != ThemeMode.SYSTEM && NativeTheme.set(display, mode);
  }

  @Override
  public void watch(boolean dark, UiDispatcher timers, Runnable refresh) {}

  @Override
  public void systemChanged(boolean dark) {}

  @Override
  public void preferDark(boolean dark) {
    if (!display.isDisposed()) {
      display.setDarkThemePreferred(dark);
    }
  }

  @Override
  public void showOnWindows(boolean dark, WindowRebuild windows) {}
}
