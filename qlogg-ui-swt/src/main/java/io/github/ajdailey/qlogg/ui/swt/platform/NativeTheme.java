package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.theme.ThemeController;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import org.eclipse.swt.widgets.Display;

/**
 * The native theme setting, the system setting and its poll. Only this package reads {@link
 * Display#isSystemDarkTheme}.
 */
public final class NativeTheme {
  /** Milliseconds between reads of the system setting on cocoa and win32. */
  private static final int POLL_MILLIS = 500;

  /** Display data key: the first side {@link #set} gave, kept through live switches. */
  private static final String START_DARK = NativeTheme.class.getName() + ".startDark";

  private NativeTheme() {}

  /**
   * Reads the system's light or dark setting.
   *
   * @return {@link Display#isSystemDarkTheme}
   */
  public static boolean systemDark() {
    return Display.isSystemDarkTheme();
  }

  /**
   * Sets the theme a setting resolves to, with the system setting read now, and records it.
   *
   * @param display the Display
   * @param mode the theme setting
   * @return true for dark
   */
  static boolean set(Display display, ThemeMode mode) {
    return set(display, ThemeController.resolve(mode, Display.isSystemDarkTheme()).dark());
  }

  /**
   * Sets a side and records it. The first call also sets the side {@link #startDark} reports.
   *
   * @param display the Display
   * @param dark true for dark
   * @return {@code dark}
   */
  static boolean set(Display display, boolean dark) {
    display.setDarkThemePreferred(dark);
    display.setData(DialogColors.NATIVE_DARK, dark);
    if (display.getData(START_DARK) == null) {
      display.setData(START_DARK, dark);
    }
    return dark;
  }

  /**
   * Reports the native side the Display started with. win32 pop-up menus keep it after a live
   * switch.
   *
   * @param display the Display
   * @return the first side {@link #set} gave, or {@link DialogColors#nativeDark} before any
   */
  static boolean startDark(Display display) {
    Object start = display.getData(START_DARK);
    return start == null ? DialogColors.nativeDark(display) : Boolean.TRUE.equals(start);
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
