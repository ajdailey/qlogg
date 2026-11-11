package io.github.ajdailey.qlogg.app.highlight;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.core.model.Rgb;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One color label's settings: {@code Ctrl+Shift+N} paints selected text with it, and {@code Ctrl+D}
 * steps through the labels with {@link #cycle} set.
 *
 * @param name the label shown in the context menu
 * @param foreground text color, or null to keep the text color
 * @param background background color
 * @param cycle true includes the label in the {@code Ctrl+D} cycle
 */
public record ColorLabel(String name, Rgb foreground, Rgb background, boolean cycle) {
  /** Labels there are. */
  public static final int COUNT = 9;

  /** Longest name. */
  public static final int MAX_NAME_CHARS = 64;

  private static final int[][] DEFAULT_COLORS = {
    {0x10236b, 0xb3c6ff},
    {0x6b1047, 0xffb3d4},
    {0x1f6b10, 0xbdf5b0},
    {0x6b5310, 0xffe9a8},
    {0x3d106b, 0xdab8ff},
    {0x106b6b, 0xb0f5f5},
    {0x10466b, 0xb0dcf5},
    {0x24106b, 0xc4bdea},
    {-1, 0xc8c8c8},
  };

  /** The dark-palette variant of each default, in the same order. */
  private static final int[][] DARK_COLORS = {
    {0xd6e0ff, 0x2a3f7a},
    {0xffd6e8, 0x6e2a4f},
    {0xd8f7cf, 0x2f5e26},
    {0xfff1c7, 0x5e4c16},
    {0xead6ff, 0x4a2d70},
    {0xcff7f7, 0x215e5e},
    {0xcfe8f7, 0x214a63},
    {0xddd8f2, 0x3a3268},
    {-1, 0x4a4a4a},
  };

  /**
   * Returns the labels a palette paints with: a label whose colors are still the light default at
   * its position takes the dark default on a dark palette. A color the user set is never changed.
   *
   * @param labels {@link #COUNT} labels from the settings
   * @param dark true for a dark palette
   * @return the labels to paint with
   */
  public static List<ColorLabel> forPalette(List<ColorLabel> labels, boolean dark) {
    if (!dark) {
      return labels;
    }
    List<ColorLabel> painted = new ArrayList<ColorLabel>(labels.size());
    for (int i = 0; i < labels.size(); i++) {
      ColorLabel label = labels.get(i);
      if (i < COUNT && hasColors(label, DEFAULT_COLORS[i])) {
        label =
            new ColorLabel(
                label.name(), color(DARK_COLORS[i][0]), Rgb.of(DARK_COLORS[i][1]), label.cycle());
      }
      painted.add(label);
    }
    return Collections.unmodifiableList(painted);
  }

  // View colors.

  /**
   * Returns the labels a palette paints with: {@link #forPalette(List, boolean)} for the palette's
   * side, and a default label that keeps the text color takes {@link Palette#labelGray}.
   *
   * @param labels {@link #COUNT} labels from the settings
   * @param palette the palette
   * @return the labels to paint with
   */
  public static List<ColorLabel> forPalette(List<ColorLabel> labels, Palette palette) {
    List<ColorLabel> sided = forPalette(labels, palette.dark());
    List<ColorLabel> painted = new ArrayList<ColorLabel>(sided.size());
    for (int i = 0; i < sided.size(); i++) {
      ColorLabel label = sided.get(i);
      if (i < COUNT && DEFAULT_COLORS[i][0] < 0 && hasColors(labels.get(i), DEFAULT_COLORS[i])) {
        label = new ColorLabel(label.name(), null, palette.labelGray(), label.cycle());
      }
      painted.add(label);
    }
    return Collections.unmodifiableList(painted);
  }

  private static boolean hasColors(ColorLabel label, int[] colors) {
    Rgb foreground = color(colors[0]);
    return label.background().equals(Rgb.of(colors[1]))
        && (foreground == null
            ? label.foreground() == null
            : foreground.equals(label.foreground()));
  }

  private static Rgb color(int value) {
    return value < 0 ? null : Rgb.of(value);
  }

  /** Checks the components. */
  public ColorLabel {
    if (name == null || background == null) {
      throw new IllegalArgumentException("name and background are required");
    }
    if (name.length() > MAX_NAME_CHARS) {
      throw new IllegalArgumentException("name is too long");
    }
  }

  /**
   * Returns the default labels.
   *
   * @return {@link #COUNT} labels, all in the cycle
   */
  public static List<ColorLabel> defaults() {
    List<ColorLabel> labels = new ArrayList<ColorLabel>();
    for (int i = 0; i < COUNT; i++) {
      int[] colors = DEFAULT_COLORS[i];
      labels.add(
          new ColorLabel(
              UiText.LABEL_DEFAULT_NAME.format(i + 1),
              colors[0] < 0 ? null : Rgb.of(colors[0]),
              Rgb.of(colors[1]),
              true));
    }
    return Collections.unmodifiableList(labels);
  }
}
