package io.github.ajdailey.qlogg.app.theme;

import io.github.ajdailey.qlogg.core.model.Rgb;
import java.util.List;

/**
 * Colors of the log views. The views create their toolkit colors from it and recreate them when the
 * palette changes.
 *
 * @param dark true for a dark palette; picks the default color label variants
 * @param text line text
 * @param background view background
 * @param selection selected text background
 * @param selectionText selected text
 * @param currentLine background of the caret's line
 * @param lineNumbers line number text
 * @param gutter line number background
 * @param match search match background
 * @param mark marked line background
 * @param resultRow filtered-view row background
 * @param matchMarker match bullets in the gutter and match lines in the overview bar
 * @param markMarker mark bullets in the gutter and mark lines in the overview bar
 * @param overview overview bar background
 * @param overviewFrame the overview bar's left edge and its first and last visible line
 * @param newLinesDot the tab dot for new lines
 * @param newMatchesDot the tab dot for new matching lines
 * @param contextRow filtered-view background of a context line around a search result
 * @param followStoppedDot the tab dot for follow stopped by a keyword trigger; the overview bar's
 *     time-spread layer draws rows whose time runs backward in it
 * @param spreadPause overview bar shading of rows spanning more time than the average
 * @param spreadActivity overview bar shading of rows with more lines per second than the average
 * @param labelGray background of a default color label that keeps the line text color
 */
public record Palette(
    boolean dark,
    Rgb text,
    Rgb background,
    Rgb selection,
    Rgb selectionText,
    Rgb currentLine,
    Rgb lineNumbers,
    Rgb gutter,
    Rgb match,
    Rgb mark,
    Rgb resultRow,
    Rgb matchMarker,
    Rgb markMarker,
    Rgb overview,
    Rgb overviewFrame,
    // Tab data dot.
    Rgb newLinesDot,
    Rgb newMatchesDot,
    // Context lines.
    Rgb contextRow,
    // Keyword triggers.
    Rgb followStoppedDot,
    // Time spread.
    Rgb spreadPause,
    Rgb spreadActivity,
    // View colors.
    Rgb labelGray) {
  /** The default palette: navy {@code #1b2e44} text on white. */
  public static final Palette LIGHT =
      new Palette(
          false,
          Rgb.of(0x1b2e44),
          Rgb.of(0xffffff),
          Rgb.of(0xbfe6f8),
          Rgb.of(0x1b2e44),
          Rgb.of(0xf1f6fa),
          Rgb.of(0x6e7f91),
          Rgb.of(0xf3f5f8),
          Rgb.of(0xfdebc8),
          Rgb.of(0xd6e4f2),
          Rgb.of(0xf7f9fb),
          Rgb.of(0xc0281f),
          Rgb.of(0x0a6a96),
          Rgb.of(0xf3f5f8),
          Rgb.of(0x6e7f91),
          // Tab data dot.
          Rgb.of(0x3a9d5d),
          Rgb.of(0xa8410e),
          // Context lines.
          Rgb.of(0xebeff4),
          // Keyword triggers.
          Rgb.of(0x8338a0),
          // Time spread.
          Rgb.of(0xa86d0c),
          Rgb.of(0x18736a),
          // View colors.
          Rgb.of(0xbfc9d3));

  /** The dark palette: {@code #dce6f0} text on navy {@code #1b2e44}. */
  public static final Palette DARK =
      new Palette(
          true,
          Rgb.of(0xdce6f0),
          Rgb.of(0x1b2e44),
          Rgb.of(0x2f5f88),
          Rgb.of(0xffffff),
          Rgb.of(0x223a54),
          Rgb.of(0x8095aa),
          Rgb.of(0x162638),
          Rgb.of(0x463818),
          Rgb.of(0x1f4a6b),
          Rgb.of(0x1f3349),
          Rgb.of(0xff8a80),
          Rgb.of(0x56c1f0),
          Rgb.of(0x162638),
          Rgb.of(0x8095aa),
          // Tab data dot.
          Rgb.of(0x6cc58a),
          Rgb.of(0xff8f5a),
          // Context lines.
          Rgb.of(0x20344c),
          // Keyword triggers.
          Rgb.of(0xdcaaf2),
          // Time spread.
          Rgb.of(0xe0a84e),
          Rgb.of(0x7fd8c5),
          // View colors.
          Rgb.of(0x40597a));

  // View colors.

  /** The color components' names, in component order; a scheme file's keys. */
  public static final List<String> COLOR_NAMES =
      List.of(
          "text",
          "background",
          "selection",
          "selectionText",
          "currentLine",
          "lineNumbers",
          "gutter",
          "match",
          "mark",
          "resultRow",
          "matchMarker",
          "markMarker",
          "overview",
          "overviewFrame",
          "newLinesDot",
          "newMatchesDot",
          "contextRow",
          "followStoppedDot",
          "spreadPause",
          "spreadActivity",
          "labelGray");

  /**
   * Creates a palette from its colors in component order.
   *
   * @param dark true for a dark palette
   * @param colors one color per {@link #COLOR_NAMES} entry
   * @return the palette
   */
  public static Palette of(boolean dark, Rgb... colors) {
    if (colors.length != COLOR_NAMES.size()) {
      throw new IllegalArgumentException("one color per component is required");
    }
    Rgb[] c = colors;
    return new Palette(
        dark, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11], c[12],
        c[13], c[14], c[15], c[16], c[17], c[18], c[19], c[20]);
  }
}
