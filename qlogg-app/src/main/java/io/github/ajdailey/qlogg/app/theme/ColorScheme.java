package io.github.ajdailey.qlogg.app.theme;

/**
 * A named palette the log views can use in place of the theme's.
 *
 * @param id the {@code view.colors} value: a bundled id, or a user scheme file's base name
 * @param name the name shown in Tools > Preferences
 * @param palette the colors
 */
public record ColorScheme(String id, String name, Palette palette) {
  /** Checks the components. */
  public ColorScheme {
    if (id == null || name == null || palette == null) {
      throw new IllegalArgumentException("id, name and palette are required");
    }
  }
}
