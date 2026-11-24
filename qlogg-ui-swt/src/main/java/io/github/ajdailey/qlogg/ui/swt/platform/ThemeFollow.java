package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;

/**
 * How native controls take the light or dark theme, one implementation per platform, created per
 * Display by {@link PlatformTraits#themeFollow}. {@code SwtThemeService} calls every method on
 * every platform. Confined to the UI thread.
 *
 * <p>GTK applies {@code Display.setDarkThemePreferred} to existing controls at once; win32 controls
 * read it only when created; cocoa controls follow the system appearance and it is never called.
 */
public interface ThemeFollow {
  /**
   * Sets the native controls' startup theme for a setting and records it as {@link
   * DialogColors#NATIVE_DARK}: for light and dark on GTK, and for every setting on win32, where
   * system mode reads the system setting here. Call before the first Shell exists.
   *
   * @param mode the theme setting
   * @return the startup setting made, false when none was made
   */
  boolean start(ThemeMode mode);

  /**
   * Starts following the system setting. win32 and cocoa call {@code refresh} every 500 ms, since
   * cocoa sends no {@code SWT.Settings} for an appearance change and win32 sends it 2 s after
   * {@code WM_SETTINGCHANGE}. cocoa also starts recoloring shells shown while the appearance
   * differs from {@code dark}.
   *
   * @param dark the system setting now
   * @param timers schedules the polls
   * @param refresh reads the system setting and reports a change
   */
  void watch(boolean dark, UiDispatcher timers, Runnable refresh);

  /**
   * Takes a changed system setting: cocoa recolors every shell.
   *
   * @param dark the new setting
   */
  void systemChanged(boolean dark);

  /**
   * Applies a palette side to existing native controls, on GTK only.
   *
   * @param dark true for dark
   */
  void preferDark(boolean dark);

  /**
   * Reports whether native controls show a side now: on GTK after {@link #preferDark}, on cocoa at
   * every start, and on win32 only when it is the startup setting.
   *
   * @param dark the side
   * @param nativeDark the startup setting {@link #start} made
   * @return false when the side shows only after a restart
   */
  boolean showsNow(boolean dark, boolean nativeDark);
}
