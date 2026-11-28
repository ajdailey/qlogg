package io.github.ajdailey.qlogg.app.theme;

import io.github.ajdailey.qlogg.core.model.Rgb;

/**
 * The color of the one-pixel rule between column-view cells: the line-number color over the
 * background at the lowest opacity from {@link #START_ALPHA} up that reaches {@link #MIN_CONTRAST}
 * against each ground a rule crosses (background, current line, gutter), else the text color.
 */
public final class CellRule {
  /** Lowest WCAG 2 contrast of the rule against each ground. */
  public static final double MIN_CONTRAST = 3.0;

  /** Opacity the search starts from, out of 255. */
  static final int START_ALPHA = 0x60;

  private CellRule() {}

  /**
   * Returns a palette's rule color.
   *
   * @param palette the palette
   * @return the color
   */
  public static Rgb of(Palette palette) {
    Rgb[] grounds = grounds(palette);
    for (int alpha = START_ALPHA; alpha <= 0xff; alpha += 8) {
      Rgb rule = blend(palette.lineNumbers(), palette.background(), alpha);
      if (meets(rule, grounds)) {
        return rule;
      }
    }
    return meets(palette.lineNumbers(), grounds) ? palette.lineNumbers() : palette.text();
  }

  /**
   * Returns the grounds a rule is drawn on.
   *
   * @param palette the palette
   * @return the background, the current line and the gutter (the header row)
   */
  public static Rgb[] grounds(Palette palette) {
    return new Rgb[] {palette.background(), palette.currentLine(), palette.gutter()};
  }

  /**
   * Returns the WCAG 2 contrast ratio of two colors.
   *
   * @param a one color
   * @param b the other
   * @return 1 to 21
   */
  public static double contrast(Rgb a, Rgb b) {
    double high = Math.max(luminance(a), luminance(b));
    double low = Math.min(luminance(a), luminance(b));
    return (high + 0.05) / (low + 0.05);
  }

  private static boolean meets(Rgb rule, Rgb[] grounds) {
    for (Rgb ground : grounds) {
      if (contrast(rule, ground) < MIN_CONTRAST) {
        return false;
      }
    }
    return true;
  }

  private static Rgb blend(Rgb top, Rgb under, int alpha) {
    return new Rgb(
        mix(top.red(), under.red(), alpha),
        mix(top.green(), under.green(), alpha),
        mix(top.blue(), under.blue(), alpha));
  }

  private static int mix(int top, int under, int alpha) {
    return (top * alpha + under * (255 - alpha) + 127) / 255;
  }

  private static double luminance(Rgb rgb) {
    return 0.2126 * linear(rgb.red()) + 0.7152 * linear(rgb.green()) + 0.0722 * linear(rgb.blue());
  }

  private static double linear(int channel) {
    double value = channel / 255.0;
    return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
  }
}
