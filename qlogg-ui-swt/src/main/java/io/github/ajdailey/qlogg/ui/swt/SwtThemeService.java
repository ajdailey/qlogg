package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.theme.ThemeController;
import io.github.ajdailey.qlogg.app.theme.ThemeListener;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.theme.ThemeService;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * The system's light or dark setting from {@link Display#isSystemDarkTheme}, with a change event
 * from {@code SWT.Settings}. On GTK the setting is read once when the Display is created, so a
 * change made while QLogg runs applies at the next start; win32 and cocoa read it per call.
 *
 * <p>Native controls take the theme from {@link Display#setDarkThemePreferred}. GTK applies a
 * change at once, so {@link #preferDark} calls it there. win32 controls read it only when created,
 * and cocoa SWT reads its system colors (widget, list and text backgrounds) only when the Display
 * is created: a later call turns cocoa's text light or dark over backgrounds still in the old
 * appearance, leaving text unreadable. On both, {@link #open} sets it at startup, {@link
 * #preferDark} does nothing, and {@link #showsNow} reports whether the startup setting matches a
 * request.
 */
public final class SwtThemeService implements ThemeService {
  private static final boolean GTK = "gtk".equals(SWT.getPlatform());
  private static final boolean COCOA = "cocoa".equals(SWT.getPlatform());

  private final List<ThemeListener> listeners = new ArrayList<ThemeListener>();
  private final Display display;
  private boolean dark;
  private boolean nativeDark;

  /**
   * Creates the service with native controls following the system; call on the UI thread.
   *
   * @param display the Display
   */
  public SwtThemeService(Display display) {
    this(display, false, false);
  }

  private SwtThemeService(Display display, boolean pinned, boolean pinnedDark) {
    this.display = display;
    dark = Display.isSystemDarkTheme();
    // win32 controls stay light unless told otherwise; cocoa controls follow the system.
    nativeDark = pinned ? pinnedDark : COCOA && dark;
    display.addListener(
        SWT.Settings,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            boolean now = Display.isSystemDarkTheme();
            if (now != dark) {
              dark = now;
              if (COCOA && !pinned) {
                nativeDark = now;
              }
              for (ThemeListener listener : new ArrayList<ThemeListener>(listeners)) {
                listener.systemThemeChanged(now);
              }
            }
          }
        });
  }

  /**
   * Sets the native controls' theme for {@code mode} before the first Shell exists and creates the
   * service. On cocoa, when the theme differs from the system appearance, the Display is disposed
   * and replaced, so the new one reads its system colors in that theme.
   *
   * @param display a Display with no Shell
   * @param mode the theme setting
   * @return the service; its {@link #display} is the one to use
   */
  public static SwtThemeService open(Display display, ThemeMode mode) {
    if (mode == ThemeMode.SYSTEM) {
      return new SwtThemeService(display);
    }
    boolean systemDark = Display.isSystemDarkTheme();
    boolean value = ThemeController.resolve(mode, systemDark).dark();
    display.setDarkThemePreferred(value);
    Display shown = display;
    if (COCOA && value != systemDark) {
      display.dispose();
      shown = new Display();
      shown.setDarkThemePreferred(value);
    }
    return new SwtThemeService(shown, true, value);
  }

  /**
   * Returns the Display the service was created for.
   *
   * @return the Display
   */
  public Display display() {
    return display;
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
    if (GTK && !display.isDisposed()) {
      display.setDarkThemePreferred(value);
    }
  }

  @Override
  public boolean showsNow(boolean value) {
    return GTK || value == nativeDark;
  }
}
