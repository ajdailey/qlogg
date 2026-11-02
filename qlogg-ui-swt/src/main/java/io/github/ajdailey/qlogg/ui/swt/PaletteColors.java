package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.core.model.Rgb;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Display;

/**
 * The SWT colors of one {@link Palette}, plus a bounded cache of highlight colors keyed by packed
 * RGB. The owning widget disposes them with itself or when the palette changes.
 */
final class PaletteColors {
  /** Highlight colors kept before the cache is emptied between paints. */
  static final int MAX_CACHED = 512;

  final Palette palette;
  final Color text;
  final Color background;
  final Color selection;
  final Color selectionText;
  final Color lineNumbers;
  final Color gutter;
  final Color matchMarker;
  final Color markMarker;
  final Color overview;
  final Color overviewFrame;
  private final Display display;
  private final List<Color> all = new ArrayList<Color>();
  private final Map<Integer, Color> cached = new HashMap<Integer, Color>();

  PaletteColors(Display display, Palette palette) {
    this.display = display;
    this.palette = palette;
    text = color(palette.text());
    background = color(palette.background());
    selection = color(palette.selection());
    selectionText = color(palette.selectionText());
    lineNumbers = color(palette.lineNumbers());
    gutter = color(palette.gutter());
    matchMarker = color(palette.matchMarker());
    markMarker = color(palette.markMarker());
    overview = color(palette.overview());
    overviewFrame = color(palette.overviewFrame());
  }

  private Color color(Rgb rgb) {
    Color color = new Color(display, rgb.red(), rgb.green(), rgb.blue());
    all.add(color);
    return color;
  }

  /**
   * Returns a highlight color, created once per RGB value.
   *
   * @param rgb packed {@code 0xRRGGBB}
   * @return the color, valid until {@link #trim} or {@link #dispose}
   */
  Color cached(int rgb) {
    Integer key = rgb & 0xffffff;
    Color color = cached.get(key);
    if (color == null) {
      color = new Color(display, (rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
      cached.put(key, color);
    }
    return color;
  }

  /** Empties the highlight cache once it passes {@link #MAX_CACHED}; call before a paint. */
  void trim() {
    if (cached.size() > MAX_CACHED) {
      for (Color color : cached.values()) {
        color.dispose();
      }
      cached.clear();
    }
  }

  /**
   * Blends a packed {@code 0xAARRGGBB} color over a background.
   *
   * @param argb the color; alpha 0 returns the background
   * @param under the background as {@code 0xRRGGBB}
   * @return {@code 0xRRGGBB}
   */
  static int blend(int argb, int under) {
    int alpha = (argb >>> 24) & 0xff;
    if (alpha == 0xff) {
      return argb & 0xffffff;
    }
    int result = 0;
    for (int shift = 0; shift <= 16; shift += 8) {
      int top = (argb >> shift) & 0xff;
      int bottom = (under >> shift) & 0xff;
      result |= ((top * alpha + bottom * (255 - alpha) + 127) / 255) << shift;
    }
    return result;
  }

  /**
   * Packs a palette color.
   *
   * @param rgb the color
   * @return {@code 0xRRGGBB}
   */
  static int packed(Rgb rgb) {
    return rgb.red() << 16 | rgb.green() << 8 | rgb.blue();
  }

  void dispose() {
    for (Color color : all) {
      color.dispose();
    }
    for (Color color : cached.values()) {
      color.dispose();
    }
    cached.clear();
  }
}
