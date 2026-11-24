package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.theme.ThemeController;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import org.eclipse.swt.widgets.Display;

/** The native theme setting and the system-setting poll the {@link ThemeFollow} classes share. */
final class NativeTheme {
  /** Milliseconds between reads of the system setting on cocoa and win32. */
  private static final int POLL_MILLIS = 500;

  private NativeTheme() {}

  /**
   * Sets the theme a setting resolves to, with the system setting read now, and records it.
   *
   * @param display the Display
   * @param mode the theme setting
   * @return true for dark
   */
  static boolean set(Display display, ThemeMode mode) {
    boolean value = ThemeController.resolve(mode, Display.isSystemDarkTheme()).dark();
    display.setDarkThemePreferred(value);
    display.setData(DialogColors.NATIVE_DARK, value);
    return value;
  }

  /**
   * Calls {@code refresh} every 500 ms until the Display is disposed.
   *
   * @param display the Display
   * @param timers schedules the calls
   * @param refresh the call
   */
  static void poll(final Display display, final UiDispatcher timers, final Runnable refresh) {
    timers.timerExec(
        POLL_MILLIS,
        new Runnable() {
          @Override
          public void run() {
            if (!display.isDisposed()) {
              refresh.run();
              timers.timerExec(POLL_MILLIS, this);
            }
          }
        });
  }
}
