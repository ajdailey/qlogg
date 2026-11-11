package io.github.ajdailey.qlogg.app.theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves {@code ui.theme} and {@code view.colors} to a {@link Palette} and pushes a change to the
 * views. {@link ThemeMode#SYSTEM} follows {@link ThemeService#systemDark} and its change events.
 * Each mode change, and each system change in {@link ThemeMode#SYSTEM}, also sets {@link
 * ThemeService#preferDark} from {@code ui.theme} alone, so native controls follow the theme while a
 * fixed scheme keeps the palette; the startup setting is the launcher's. Confined to the UI thread.
 */
public final class ThemeController {
  private final List<PaletteListener> listeners = new ArrayList<PaletteListener>();
  private final ThemeService service;
  private ThemeMode mode;
  private boolean systemDark;
  private Palette palette;
  // View colors.
  private final ColorSchemes schemes;
  private String colors;

  /**
   * Creates the controller with the theme's palette.
   *
   * @param service the system setting
   * @param mode the setting at startup
   */
  public ThemeController(ThemeService service, ThemeMode mode) {
    this(service, mode, ColorSchemes.NONE, ColorSchemes.FOLLOW_THEME);
  }

  /**
   * Creates the controller.
   *
   * @param service the system setting
   * @param mode the setting at startup
   * @param schemes the schemes {@code colors} may name
   * @param colors {@code view.colors} at startup
   */
  public ThemeController(
      ThemeService service, ThemeMode mode, ColorSchemes schemes, String colors) {
    this.service = service;
    this.mode = mode;
    this.schemes = schemes;
    this.colors = colors;
    this.systemDark = service.systemDark();
    this.palette = resolve(mode, systemDark, schemes.find(colors));
    service.addListener(
        new ThemeListener() {
          @Override
          public void systemThemeChanged(boolean dark) {
            systemDark = dark;
            if (ThemeController.this.mode == ThemeMode.SYSTEM) {
              ThemeController.this.service.preferDark(dark);
            }
            apply(current());
          }
        });
  }

  /**
   * Returns the palette of a mode.
   *
   * @param mode the setting
   * @param systemDark the system setting
   * @return {@link Palette#DARK} for dark, and for system while the system is dark; else {@link
   *     Palette#LIGHT}
   */
  public static Palette resolve(ThemeMode mode, boolean systemDark) {
    return switch (mode) {
      case LIGHT -> Palette.LIGHT;
      case DARK -> Palette.DARK;
      case SYSTEM -> systemDark ? Palette.DARK : Palette.LIGHT;
    };
  }

  /**
   * Returns the setting.
   *
   * @return the mode
   */
  public ThemeMode mode() {
    return mode;
  }

  /**
   * Returns the resolved palette.
   *
   * @return the palette
   */
  public Palette palette() {
    return palette;
  }

  /**
   * Changes the setting and pushes the palette when it changes.
   *
   * @param next the mode
   * @return true when native controls show the mode now; false when they show it from the next
   *     start
   */
  public boolean setMode(ThemeMode next) {
    mode = next;
    boolean dark = resolve(next, systemDark).dark();
    service.preferDark(dark);
    apply(current());
    return service.showsNow(dark);
  }

  /**
   * Adds a listener.
   *
   * @param listener called on the UI thread
   */
  public void addListener(PaletteListener listener) {
    listeners.add(listener);
  }

  /**
   * Removes a listener.
   *
   * @param listener a listener added earlier
   */
  public void removeListener(PaletteListener listener) {
    listeners.remove(listener);
  }

  // View colors.

  /**
   * Returns the palette of a mode and a scheme.
   *
   * @param mode the setting
   * @param systemDark the system setting
   * @param scheme the chosen scheme, or null to follow the theme
   * @return the scheme's palette, or {@link #resolve(ThemeMode, boolean)} without one
   */
  public static Palette resolve(ThemeMode mode, boolean systemDark, ColorScheme scheme) {
    return scheme == null ? resolve(mode, systemDark) : scheme.palette();
  }

  /**
   * Returns the schemes {@link #setColors} takes.
   *
   * @return the schemes
   */
  public ColorSchemes schemes() {
    return schemes;
  }

  /**
   * Returns {@code view.colors}.
   *
   * @return a scheme id or {@link ColorSchemes#FOLLOW_THEME}
   */
  public String colors() {
    return colors;
  }

  /**
   * Changes {@code view.colors} and pushes the palette when it changes. Native controls keep
   * following the theme. An id that names no scheme follows the theme.
   *
   * @param id a scheme id or {@link ColorSchemes#FOLLOW_THEME}
   */
  public void setColors(String id) {
    colors = id;
    apply(current());
  }

  private Palette current() {
    return resolve(mode, systemDark, schemes.find(colors));
  }

  private void apply(Palette next) {
    if (next == palette) {
      return;
    }
    palette = next;
    for (PaletteListener listener : new ArrayList<PaletteListener>(listeners)) {
      listener.paletteChanged(next);
    }
  }
}
