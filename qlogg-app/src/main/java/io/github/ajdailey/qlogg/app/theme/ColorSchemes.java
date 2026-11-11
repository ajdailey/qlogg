package io.github.ajdailey.qlogg.app.theme;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.model.Rgb;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The color schemes {@code view.colors} names: Light, Dark, the bundled ones, then the user's.
 * Immutable.
 *
 * <p>Each bundled scheme starts from a published terminal palette or a plain pair, with each color
 * moved toward black or white only as far as the contrast floors in {@code ColorSchemesTest} need.
 */
public final class ColorSchemes {
  /** The {@code view.colors} value that keeps the theme's palette. */
  public static final String FOLLOW_THEME = "follow";

  /** {@link Palette#LIGHT} whatever the theme. */
  public static final ColorScheme LIGHT =
      new ColorScheme("light", UiText.VIEW_COLORS_LIGHT.text(), Palette.LIGHT);

  /** {@link Palette#DARK} whatever the theme. */
  public static final ColorScheme DARK =
      new ColorScheme("dark", UiText.VIEW_COLORS_DARK.text(), Palette.DARK);

  /** The bundled schemes, in list order. */
  public static final List<ColorScheme> BUNDLED =
      List.of(
          scheme(
              "sandstone",
              UiText.VIEW_COLORS_SANDSTONE,
              false,
              0x586c73,
              0xfdf6e3,
              0x2076b3,
              0xfdf6e3,
              0xeee8d5,
              0x7c8787,
              0xeee8d5,
              0xf7e8bb,
              0xddebf1,
              0xf7f0dc,
              0xdc322f,
              0x268bd2,
              0xeee8d5,
              0x7c8787,
              0x7b8e00,
              0xcb4b16,
              0xf3edd9,
              0x6c71c4,
              0xa87f00,
              0x27948c,
              0xebe8e1),
          scheme(
              "nightwater",
              UiText.VIEW_COLORS_NIGHTWATER,
              true,
              0x8b9b9d,
              0x002b36,
              0x0f5a6e,
              0xfdf6e3,
              0x073642,
              0x697d83,
              0x073642,
              0x3b3000,
              0x073544,
              0x073642,
              0xde3e3b,
              0x268bd2,
              0x073642,
              0x697d83,
              0x859900,
              0xcd5320,
              0x04303c,
              0x6d72c4,
              0xb58900,
              0x2aa198,
              0x18353d),
          scheme(
              "garden-light",
              UiText.VIEW_COLORS_GARDEN_LIGHT,
              false,
              0x2e3436,
              0xeeeeec,
              0x3465a4,
              0xeeeeec,
              0xe2e2df,
              0x787975,
              0xd3d7cf,
              0xfce94f,
              0xc5d6ea,
              0xe6e6e3,
              0xcc0000,
              0x3465a4,
              0xd3d7cf,
              0x787975,
              0x458805,
              0xc55800,
              0xe4e4e1,
              0x75507b,
              0x917600,
              0x058688,
              0xbabdb6),
          scheme(
              "garden-dark",
              UiText.VIEW_COLORS_GARDEN_DARK,
              true,
              0xd3d7cf,
              0x2e3436,
              0x204a87,
              0xeeeeec,
              0x383e40,
              0x888a85,
              0x262b2d,
              0x6f5a00,
              0x24456b,
              0x343a3c,
              0xf14a4a,
              0x729fcf,
              0x262b2d,
              0x888a85,
              0x8ae234,
              0xfcaf3e,
              0x33393b,
              0xad7fa8,
              0xedd400,
              0x34e2e2,
              0x555753),
          scheme(
              "legal-pad",
              UiText.VIEW_COLORS_LEGAL_PAD,
              false,
              0x000000,
              0xffffdd,
              0x3874d8,
              0xffffff,
              0xf4f4cc,
              0x7a7a60,
              0xf0f0c8,
              0xffe680,
              0xc9e7ff,
              0xf8f8d4,
              0xe0301e,
              0x1e8ae0,
              0xf0f0c8,
              0x7a7a60,
              0x2e9c43,
              0xe8590c,
              0xececc4,
              0x8e44ad,
              0xc67600,
              0x00897b,
              0xc8c8b4),
          scheme(
              "paper",
              UiText.VIEW_COLORS_PAPER,
              false,
              0x000000,
              0xffffff,
              0x3874d8,
              0xffffff,
              0xeef3fb,
              0x8a8a8a,
              0xf3f3f3,
              0xffe680,
              0xc9e7ff,
              0xf7f7f7,
              0xe0301e,
              0x1e8ae0,
              0xf3f3f3,
              0x8a8a8a,
              0x2e9e44,
              0xe8590c,
              0xebebeb,
              0x8e44ad,
              0xc77700,
              0x00897b,
              0xc8c8c8),
          scheme(
              "midnight",
              UiText.VIEW_COLORS_MIDNIGHT,
              true,
              0xffffff,
              0x000000,
              0x264f78,
              0xffffff,
              0x1c1c1c,
              0x858585,
              0x141414,
              0x695c1a,
              0x1f4a6b,
              0x141414,
              0xf14c4c,
              0x3794ff,
              0x141414,
              0x858585,
              0x4ec26a,
              0xff7b3a,
              0x181818,
              0xc27ae0,
              0xf0a030,
              0x35c4b0,
              0x4a4a4a),
          scheme(
              "phosphor",
              UiText.VIEW_COLORS_PHOSPHOR,
              true,
              0x00ff00,
              0x000000,
              0x00a000,
              0x000000,
              0x0c1f0c,
              0x2e8b2e,
              0x081408,
              0x4a4a00,
              0x123c5a,
              0x081408,
              0xf14c4c,
              0x3794ff,
              0x081408,
              0x2e8b2e,
              0x4ec26a,
              0xff7b3a,
              0x0a1a0a,
              0xc27ae0,
              0xf0a030,
              0x35c4b0,
              0x2a4a2a),
          scheme(
              "graphite",
              UiText.VIEW_COLORS_GRAPHITE,
              true,
              0xaaaaaa,
              0x000000,
              0x4a4a4a,
              0xffffff,
              0x1c1c1c,
              0x707070,
              0x141414,
              0x483f12,
              0x1c4260,
              0x141414,
              0xf14c4c,
              0x3794ff,
              0x141414,
              0x707070,
              0x4ec26a,
              0xff7b3a,
              0x181818,
              0xc27ae0,
              0xf0a030,
              0x35c4b0,
              0x3f3f3f));

  /** Light, Dark and the bundled schemes. */
  public static final ColorSchemes NONE = new ColorSchemes(List.<ColorScheme>of());

  private final List<ColorScheme> all;

  /**
   * Creates the list.
   *
   * @param user the user's schemes; none may take a {@link #reserved} id
   */
  public ColorSchemes(List<ColorScheme> user) {
    List<ColorScheme> list = new ArrayList<ColorScheme>();
    list.add(LIGHT);
    list.add(DARK);
    list.addAll(BUNDLED);
    for (ColorScheme scheme : user) {
      if (reserved(scheme.id())) {
        throw new IllegalArgumentException("scheme id is reserved");
      }
      list.add(scheme);
    }
    all = Collections.unmodifiableList(list);
  }

  /**
   * Reports whether a user scheme cannot take an id.
   *
   * @param id the id
   * @return true for {@link #FOLLOW_THEME}, {@link #LIGHT}, {@link #DARK} and the bundled ids
   */
  public static boolean reserved(String id) {
    if (FOLLOW_THEME.equals(id) || LIGHT.id().equals(id) || DARK.id().equals(id)) {
      return true;
    }
    for (ColorScheme scheme : BUNDLED) {
      if (scheme.id().equals(id)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the schemes.
   *
   * @return {@link #LIGHT}, {@link #DARK}, the bundled schemes, then the user's
   */
  public List<ColorScheme> all() {
    return all;
  }

  /**
   * Finds a scheme.
   *
   * @param id the {@code view.colors} value
   * @return the scheme, or null for {@link #FOLLOW_THEME} and an unknown id
   */
  public ColorScheme find(String id) {
    for (ColorScheme scheme : all) {
      if (scheme.id().equals(id)) {
        return scheme;
      }
    }
    return null;
  }

  /** Builds a bundled scheme from its colors in {@link Palette} component order. */
  private static ColorScheme scheme(String id, UiText name, boolean dark, int... colors) {
    Rgb[] c = new Rgb[colors.length];
    for (int i = 0; i < colors.length; i++) {
      c[i] = Rgb.of(colors[i]);
    }
    return new ColorScheme(id, name.text(), Palette.of(dark, c));
  }
}
