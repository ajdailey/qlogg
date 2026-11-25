package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import org.eclipse.swt.widgets.Display;

/**
 * cocoa: {@code setDarkThemePreferred} pins the application appearance to the side {@code ui.theme}
 * resolves to, at startup and at each change of that side; under System the poll's change re-pins
 * it, since SWT has no call that unpins. cocoa SWT reads its widget colors once, under the system
 * appearance at Display creation, while AppKit draws native text in the pinned appearance. Pin only
 * together with {@link CocoaChrome}, which recolors every shell from the cached side to the pinned
 * one: a pin alone draws white text on light gray or black on dark gray. Menus and the menu bar
 * follow the system appearance.
 */
final class CocoaThemeFollow implements ThemeFollow {
  private final Display display;
  private CocoaChrome chrome;
  private boolean started;
  private boolean pinned;

  CocoaThemeFollow(Display display) {
    this.display = display;
  }

  @Override
  public boolean start(ThemeMode mode) {
    started = true;
    pinned = NativeTheme.set(display, mode);
    return pinned;
  }

  @Override
  public void watch(boolean dark, UiDispatcher timers, Runnable refresh) {
    chrome = new CocoaChrome(display, dark, timers);
    chrome.apply(started ? pinned : dark);
    NativeTheme.poll(display, timers, refresh);
  }

  @Override
  public void systemChanged(boolean dark) {}

  @Override
  public void preferDark(boolean dark) {}

  @Override
  public void showOnWindows(boolean dark, WindowRebuild windows) {
    if (display.isDisposed()) {
      return;
    }
    started = true;
    pinned = NativeTheme.set(display, dark);
    if (chrome != null) {
      chrome.apply(dark);
    }
  }
}
