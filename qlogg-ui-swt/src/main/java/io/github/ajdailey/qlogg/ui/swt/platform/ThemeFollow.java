package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;

/**
 * How native controls take the light or dark theme, one implementation per platform, created per
 * Display by {@link PlatformTraits#themeFollow}. {@code SwtThemeService} calls every method on
 * every platform. Confined to the UI thread.
 *
 * <p>GTK applies {@code Display.setDarkThemePreferred} to existing controls at once; win32 controls
 * read it only when created, so a switch rebuilds the windows; cocoa pins the application
 * appearance with it, and {@code CocoaChrome} recolors the shells from the side SWT cached its
 * colors under.
 */
public interface ThemeFollow {
  /**
   * Sets the native controls' startup theme for a setting and records it as {@link
   * DialogColors#NATIVE_DARK}: for light and dark on GTK, and for every setting on win32 and cocoa,
   * where system mode reads the system setting here. Call before the first Shell exists.
   *
   * @param mode the theme setting
   * @return the startup setting made, false when none was made
   */
  boolean start(ThemeMode mode);

  /**
   * Starts following the system setting. win32 and cocoa call {@code refresh} every 500 ms, since
   * cocoa sends no {@code SWT.Settings} for an appearance change and win32 sends it 2 s after
   * {@code WM_SETTINGCHANGE}. cocoa also starts {@code CocoaChrome} and applies the startup pin.
   *
   * @param dark the system setting now, the side cocoa SWT cached its colors under
   * @param timers schedules the polls
   * @param refresh reads the system setting and reports a change
   */
  void watch(boolean dark, UiDispatcher timers, Runnable refresh);

  /**
   * Takes a changed system setting; every platform ignores it. Under System the theme controller
   * passes the new side to {@link #preferDark} and {@link #showOnWindows}.
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
   * Shows a side on the existing windows. {@code SwtThemeService} calls it after {@link
   * #preferDark} on every change of the side {@code ui.theme} resolves to: View > Theme, and a
   * system change under System. win32 sets the native theme, records it as {@link
   * DialogColors#NATIVE_DARK} and rebuilds the windows when the side differs from the recorded one,
   * since win32 controls take the theme only when created. cocoa pins the side and {@code
   * CocoaChrome} recolors every shell; GTK does nothing here.
   *
   * @param dark the side
   * @param windows rebuilds the main windows
   */
  void showOnWindows(boolean dark, WindowRebuild windows);
}
