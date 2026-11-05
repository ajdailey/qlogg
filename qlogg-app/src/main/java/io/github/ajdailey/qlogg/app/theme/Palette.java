package io.github.ajdailey.qlogg.app.theme;

import io.github.ajdailey.qlogg.core.model.Rgb;

/**
 * Colors of the log views. The views create their toolkit colors from it and recreate them when the
 * palette changes.
 *
 * @param dark true for a dark palette; selects the matching icon variant
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
    Rgb contextRow) {
  /** The default palette. */
  public static final Palette LIGHT =
      new Palette(
          false,
          Rgb.of(0x1e1e1e),
          Rgb.of(0xffffff),
          Rgb.of(0x3874d8),
          Rgb.of(0xffffff),
          Rgb.of(0xeef3fb),
          Rgb.of(0x8a8a8a),
          Rgb.of(0xf3f3f3),
          Rgb.of(0xffe680),
          Rgb.of(0xc9e7ff),
          Rgb.of(0xf7f7f7),
          Rgb.of(0xe0301e),
          Rgb.of(0x1e8ae0),
          Rgb.of(0xf3f3f3),
          Rgb.of(0x8a8a8a),
          // Tab data dot.
          Rgb.of(0x2e9e44),
          Rgb.of(0xe8590c),
          // Context lines.
          Rgb.of(0xebebeb));

  /** The dark palette. */
  public static final Palette DARK =
      new Palette(
          true,
          Rgb.of(0xd4d4d4),
          Rgb.of(0x1e1e1e),
          Rgb.of(0x264f78),
          Rgb.of(0xffffff),
          Rgb.of(0x2a2d2e),
          Rgb.of(0x858585),
          Rgb.of(0x252526),
          Rgb.of(0x7a6a1e),
          Rgb.of(0x1f4a6b),
          Rgb.of(0x252526),
          Rgb.of(0xf14c4c),
          Rgb.of(0x3794ff),
          Rgb.of(0x252526),
          Rgb.of(0x858585),
          // Tab data dot.
          Rgb.of(0x4ec26a),
          Rgb.of(0xff7b3a),
          // Context lines.
          Rgb.of(0x2f2f30));
}
