package io.github.ajdailey.qlogg.app.theme;

import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.model.TokenKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The text color of each syntax-color {@link TokenKind} in a palette. Immutable.
 *
 * @param colors one color per kind, in {@link TokenKind} declaration order
 */
public record TokenColors(List<Rgb> colors) {
  /** Checks and copies the colors. */
  public TokenColors {
    if (colors == null || colors.size() != TokenKind.values().length) {
      throw new IllegalArgumentException("one color per token kind is required");
    }
    colors = List.copyOf(colors);
  }

  /**
   * Creates the colors from packed values.
   *
   * @param rgb one {@code 0xRRGGBB} per kind, in {@link TokenKind} declaration order
   * @return the colors
   */
  public static TokenColors of(int... rgb) {
    List<Rgb> list = new ArrayList<Rgb>();
    for (int value : rgb) {
      list.add(Rgb.of(value));
    }
    return new TokenColors(list);
  }

  /**
   * Returns a kind's color.
   *
   * @param kind the kind
   * @return the color
   */
  public Rgb color(TokenKind kind) {
    return colors.get(kind.ordinal());
  }

  /**
   * Returns a kind's key in a scheme file.
   *
   * @param kind the kind
   * @return {@code token.} and the kind's name in lowercase
   */
  public static String key(TokenKind kind) {
    return "token." + kind.name().toLowerCase(Locale.ROOT);
  }
}
