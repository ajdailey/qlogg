package io.github.ajdailey.qlogg.app.theme;

/** Receives the palette, and the theme's light or dark side, when they change. */
public interface PaletteListener {
  /**
   * The palette changed; the view recreates its colors from it and repaints.
   *
   * @param palette the new palette
   */
  void paletteChanged(Palette palette);

  /**
   * The side {@code ui.theme} resolves to changed, after any palette change it brought.
   *
   * @param dark true for dark
   */
  default void themeChanged(boolean dark) {
    // Optional.
  }
}
