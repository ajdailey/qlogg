package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.app.highlight.HighlightSelector;
import io.github.ajdailey.qlogg.app.theme.ColorSchemes;
import io.github.ajdailey.qlogg.core.model.FoldRules;
import io.github.ajdailey.qlogg.core.time.SpreadMode;
import java.util.List;

/**
 * View settings in {@code qlogg.conf}. Immutable.
 *
 * @param wrapOnOpen {@code view.wrapOnOpen}: files open with text wrap on
 * @param showWhitespace {@code view.showWhitespace}: tabs, trailing spaces and line ends are marked
 * @param hideAnsi {@code view.hideAnsi}: ANSI color sequences are removed for display and search
 * @param folds {@code fold.entryStart} and {@code fold.continuation.N}
 * @param selectors {@code highlight.selector.N.*}, in match order
 * @param timeSpread {@code view.timeSpread}: the overview bar's time-spread layer
 * @param colors {@code view.colors}: the log views' scheme id, or {@link ColorSchemes#FOLLOW_THEME}
 */
public record ViewOptions(
    boolean wrapOnOpen,
    boolean showWhitespace,
    boolean hideAnsi,
    FoldRules folds,
    List<HighlightSelector> selectors,
    // Time spread.
    SpreadMode timeSpread,
    // View colors.
    String colors) {
  /** Every flag off, the default fold rules, no selectors and the theme's colors. */
  public static final ViewOptions DEFAULTS =
      new ViewOptions(
          false,
          false,
          false,
          FoldRules.DEFAULTS,
          List.of(),
          SpreadMode.OFF,
          ColorSchemes.FOLLOW_THEME);

  /** Checks and copies the components. */
  public ViewOptions {
    if (folds == null || selectors == null || timeSpread == null || colors == null) {
      throw new IllegalArgumentException("folds, selectors, timeSpread and colors are required");
    }
    if (selectors.size() > HighlightSelector.MAX_SELECTORS) {
      throw new IllegalArgumentException("too many selectors");
    }
    selectors = List.copyOf(selectors);
  }

  /**
   * Returns a copy with another wrap-on-open flag.
   *
   * @param value the flag
   * @return the copy
   */
  public ViewOptions withWrapOnOpen(boolean value) {
    return new ViewOptions(value, showWhitespace, hideAnsi, folds, selectors, timeSpread, colors);
  }

  /**
   * Returns a copy with another whitespace flag.
   *
   * @param value the flag
   * @return the copy
   */
  public ViewOptions withShowWhitespace(boolean value) {
    return new ViewOptions(wrapOnOpen, value, hideAnsi, folds, selectors, timeSpread, colors);
  }

  /**
   * Returns a copy with another ANSI flag.
   *
   * @param value the flag
   * @return the copy
   */
  public ViewOptions withHideAnsi(boolean value) {
    return new ViewOptions(wrapOnOpen, showWhitespace, value, folds, selectors, timeSpread, colors);
  }

  /**
   * Returns a copy with other fold rules.
   *
   * @param rules the rules
   * @return the copy
   */
  public ViewOptions withFolds(FoldRules rules) {
    return new ViewOptions(
        wrapOnOpen, showWhitespace, hideAnsi, rules, selectors, timeSpread, colors);
  }

  /**
   * Returns a copy with other selectors.
   *
   * @param list the selectors, in match order
   * @return the copy
   */
  public ViewOptions withSelectors(List<HighlightSelector> list) {
    return new ViewOptions(wrapOnOpen, showWhitespace, hideAnsi, folds, list, timeSpread, colors);
  }

  // Time spread.

  /**
   * Returns a copy with another time-spread mode.
   *
   * @param mode the mode
   * @return the copy
   */
  public ViewOptions withTimeSpread(SpreadMode mode) {
    return new ViewOptions(wrapOnOpen, showWhitespace, hideAnsi, folds, selectors, mode, colors);
  }

  // View colors.

  /**
   * Returns a copy with another color scheme.
   *
   * @param id a scheme id, or {@link ColorSchemes#FOLLOW_THEME}
   * @return the copy
   */
  public ViewOptions withColors(String id) {
    return new ViewOptions(wrapOnOpen, showWhitespace, hideAnsi, folds, selectors, timeSpread, id);
  }
}
