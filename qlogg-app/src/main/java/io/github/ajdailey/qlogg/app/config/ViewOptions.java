package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.app.highlight.HighlightSelector;
import io.github.ajdailey.qlogg.core.model.FoldRules;
import java.util.List;

/**
 * View settings in {@code qlogg.conf}. Immutable.
 *
 * @param wrapOnOpen {@code view.wrapOnOpen}: files open with text wrap on
 * @param showWhitespace {@code view.showWhitespace}: tabs, trailing spaces and line ends are marked
 * @param hideAnsi {@code view.hideAnsi}: ANSI color sequences are removed for display and search
 * @param folds {@code fold.entryStart} and {@code fold.continuation.N}
 * @param selectors {@code highlight.selector.N.*}, in match order
 */
public record ViewOptions(
    boolean wrapOnOpen,
    boolean showWhitespace,
    boolean hideAnsi,
    FoldRules folds,
    List<HighlightSelector> selectors) {
  /** Every flag off, the default fold rules and no selectors. */
  public static final ViewOptions DEFAULTS =
      new ViewOptions(false, false, false, FoldRules.DEFAULTS, List.of());

  /** Checks and copies the components. */
  public ViewOptions {
    if (folds == null || selectors == null) {
      throw new IllegalArgumentException("folds and selectors are required");
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
    return new ViewOptions(value, showWhitespace, hideAnsi, folds, selectors);
  }

  /**
   * Returns a copy with another whitespace flag.
   *
   * @param value the flag
   * @return the copy
   */
  public ViewOptions withShowWhitespace(boolean value) {
    return new ViewOptions(wrapOnOpen, value, hideAnsi, folds, selectors);
  }

  /**
   * Returns a copy with another ANSI flag.
   *
   * @param value the flag
   * @return the copy
   */
  public ViewOptions withHideAnsi(boolean value) {
    return new ViewOptions(wrapOnOpen, showWhitespace, value, folds, selectors);
  }

  /**
   * Returns a copy with other fold rules.
   *
   * @param rules the rules
   * @return the copy
   */
  public ViewOptions withFolds(FoldRules rules) {
    return new ViewOptions(wrapOnOpen, showWhitespace, hideAnsi, rules, selectors);
  }

  /**
   * Returns a copy with other selectors.
   *
   * @param list the selectors, in match order
   * @return the copy
   */
  public ViewOptions withSelectors(List<HighlightSelector> list) {
    return new ViewOptions(wrapOnOpen, showWhitespace, hideAnsi, folds, list);
  }
}
