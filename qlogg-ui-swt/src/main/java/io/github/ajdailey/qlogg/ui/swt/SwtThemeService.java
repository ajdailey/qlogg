package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.theme.ThemeListener;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.theme.ThemeService;
import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import io.github.ajdailey.qlogg.ui.swt.platform.ThemeFollow;
import io.github.ajdailey.qlogg.ui.swt.platform.WindowRebuild;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * The system's light or dark setting from {@link Display#isSystemDarkTheme}, with a change event
 * from {@code SWT.Settings}. On GTK the setting is read once when the Display is created, so a
 * change made while QLogg runs applies at the next start; win32 and cocoa read it per call. Native
 * controls take the theme through the platform's {@link ThemeFollow}, which on win32 rebuilds the
 * main windows through the {@link WindowRebuild} the launcher sets and on cocoa pins the
 * application appearance in every mode.
 */
public final class SwtThemeService implements ThemeService {
  private static final BooleanSupplier SYSTEM_SETTING =
      new BooleanSupplier() {
        @Override
        public boolean getAsBoolean() {
          return Display.isSystemDarkTheme();
        }
      };

  private final List<ThemeListener> listeners = new ArrayList<ThemeListener>();
  private final BooleanSupplier system;
  private final ThemeFollow follow;
  private boolean dark;
  private WindowRebuild windows = WindowRebuild.NONE;

  /**
   * Creates the service with native controls following the system; call on the UI thread.
   *
   * @param display the Display
   */
  public SwtThemeService(Display display) {
    this(display, PlatformTraits.PLATFORM.themeFollow(display), SYSTEM_SETTING);
  }

  /**
   * Creates the service over a given setting, with the running platform's {@link ThemeFollow}.
   *
   * @param display the Display
   * @param system reads the system setting
   */
  SwtThemeService(Display display, BooleanSupplier system) {
    this(display, PlatformTraits.PLATFORM.themeFollow(display), system);
  }

  /**
   * Creates the service over a given setting.
   *
   * @param display the Display
   * @param follow the native controls' theme handling
   * @param system reads the system setting
   */
  SwtThemeService(Display display, ThemeFollow follow, BooleanSupplier system) {
    this.follow = follow;
    this.system = system;
    dark = system.getAsBoolean();
    display.addListener(
        SWT.Settings,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            refresh();
          }
        });
    follow.watch(
        dark,
        new SwtUiDispatcher(display),
        new Runnable() {
          @Override
          public void run() {
            refresh();
          }
        });
  }

  /** Reads the system setting and reports a change. */
  void refresh() {
    boolean now = system.getAsBoolean();
    if (now != dark) {
      dark = now;
      follow.systemChanged(now);
      for (ThemeListener listener : new ArrayList<ThemeListener>(listeners)) {
        listener.systemThemeChanged(now);
      }
    }
  }

  /**
   * Creates the service and sets the native controls' startup theme for the mode ({@link
   * ThemeFollow#start}). Call before the first Shell exists.
   *
   * @param display a Display with no Shell
   * @param mode the theme setting
   * @return the service
   */
  public static SwtThemeService open(Display display, ThemeMode mode) {
    return open(display, mode, PlatformTraits.PLATFORM.themeFollow(display));
  }

  /**
   * Creates the service over a platform's {@link ThemeFollow} and sets the startup theme.
   *
   * @param display a Display with no Shell
   * @param mode the theme setting
   * @param follow the platform's theme handling
   * @return the service
   */
  static SwtThemeService open(Display display, ThemeMode mode, ThemeFollow follow) {
    follow.start(mode);
    return new SwtThemeService(display, follow, SYSTEM_SETTING);
  }

  /**
   * Sets the main windows a switch rebuilds where native controls take the theme only when created;
   * {@link WindowRebuild#NONE} until called.
   *
   * @param windows the main windows
   */
  public void setWindows(WindowRebuild windows) {
    this.windows = windows;
  }

  @Override
  public boolean systemDark() {
    return dark;
  }

  @Override
  public void addListener(ThemeListener listener) {
    listeners.add(listener);
  }

  @Override
  public void preferDark(boolean value) {
    follow.preferDark(value);
    follow.showOnWindows(value, windows);
  }
}
