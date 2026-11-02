package io.github.ajdailey.qlogg.app.highlight;

import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.Rgb;

/**
 * How the last search's matched text is colored in both views.
 *
 * @param enabled false leaves matched text uncolored
 * @param color the background, or null for the palette's match color
 * @param variance 0, or up to {@link HighlightRule#MAX_VARIANCE}: distinct matched strings get
 *     colors that far apart in brightness
 */
public record MatchHighlight(boolean enabled, Rgb color, int variance) {
  /** On, palette color, no variation. */
  public static final MatchHighlight DEFAULTS = new MatchHighlight(true, null, 0);

  /** Checks the components. */
  public MatchHighlight {
    if (variance < 0 || variance > HighlightRule.MAX_VARIANCE) {
      throw new IllegalArgumentException("variance out of range");
    }
  }

  /**
   * Returns a copy switched on or off.
   *
   * @param value true colors matched text
   * @return the copy
   */
  public MatchHighlight withEnabled(boolean value) {
    return new MatchHighlight(value, color, variance);
  }
}
