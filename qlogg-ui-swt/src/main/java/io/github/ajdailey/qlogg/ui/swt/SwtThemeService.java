package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.theme.ThemeListener;
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
 * <p>{@link #preferDark} calls {@link Display#setDarkThemePreferred}: cocoa sets the application's
 * appearance and GTK the theme's dark variant, both at once. On win32 it does nothing: win32
 * controls read the setting only when created, so {@link #startWith} sets it before the first
 * Shell, and {@link #showsNow} reports whether that startup setting matches a request.
 */
public final class SwtThemeService implements ThemeService {
  private final List<ThemeListener> listeners = new ArrayList<ThemeListener>();
  private final Display display;
  private boolean dark;
  private boolean nativeDark;

  /**
   * Creates the service; call on the UI thread.
   *
   * @param display the Display
   */
  public SwtThemeService(Display display) {
    this.display = display;
    dark = Display.isSystemDarkTheme();
    display.addListener(
        SWT.Settings,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            boolean now = Display.isSystemDarkTheme();
            if (now != dark) {
              dark = now;
              for (ThemeListener listener : new ArrayList<ThemeListener>(listeners)) {
                listener.systemThemeChanged(now);
              }
            }
          }
        });
  }

  @Override
  public boolean systemDark() {
    return dark;
  }

  @Override
  public void addListener(ThemeListener listener) {
    listeners.add(listener);
  }

  /**
   * Sets the native controls' theme before the first Shell exists.
   *
   * @param value true for dark controls
   */
  public void startWith(boolean value) {
    nativeDark = value;
    display.setDarkThemePreferred(value);
  }

  @Override
  public void preferDark(boolean value) {
    if (!"win32".equals(SWT.getPlatform()) && !display.isDisposed()) {
      display.setDarkThemePreferred(value);
    }
  }

  @Override
  public boolean showsNow(boolean value) {
    return !"win32".equals(SWT.getPlatform()) || value == nativeDark;
  }
}
