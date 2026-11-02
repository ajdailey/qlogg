package io.github.ajdailey.qlogg.app.view;

import io.github.ajdailey.qlogg.core.search.MatchMode;

/**
 * The search bar's toggles.
 *
 * @param mode literal or regex
 * @param caseSensitive false folds case
 * @param wholeWord terms match only between non-word characters
 * @param inverse matches the lines the expression does not match
 * @param combining the text is a combined expression of quoted terms
 * @param inSelection searches only the selected lines; without a selection, every line
 * @param autoRefresh a finished search extends over lines the file gains
 * @param keepResults the next search keeps the current results as a pinned set
 */
public record SearchOptions(
    MatchMode mode,
    boolean caseSensitive,
    boolean wholeWord,
    boolean inverse,
    boolean combining,
    boolean inSelection,
    boolean autoRefresh,
    boolean keepResults) {
  /** Regex, case-sensitive, every other toggle off. */
  public static final SearchOptions DEFAULTS =
      new SearchOptions(MatchMode.REGEX, true, false, false, false, false, false, false);

  /** Checks the components. */
  public SearchOptions {
    if (mode == null) {
      throw new IllegalArgumentException("mode is required");
    }
  }

  /**
   * Returns a copy with another mode.
   *
   * @param value the mode
   * @return the copy
   */
  public SearchOptions withMode(MatchMode value) {
    return new SearchOptions(
        value, caseSensitive, wholeWord, inverse, combining, inSelection, autoRefresh, keepResults);
  }

  /**
   * Returns a copy with case sensitivity set.
   *
   * @param value true matches case
   * @return the copy
   */
  public SearchOptions withCaseSensitive(boolean value) {
    return new SearchOptions(
        mode, value, wholeWord, inverse, combining, inSelection, autoRefresh, keepResults);
  }

  /**
   * Returns a copy with whole-word matching set.
   *
   * @param value true matches whole words
   * @return the copy
   */
  public SearchOptions withWholeWord(boolean value) {
    return new SearchOptions(
        mode, caseSensitive, value, inverse, combining, inSelection, autoRefresh, keepResults);
  }

  /**
   * Returns a copy with inverse matching set.
   *
   * @param value true matches the lines the expression does not
   * @return the copy
   */
  public SearchOptions withInverse(boolean value) {
    return new SearchOptions(
        mode, caseSensitive, wholeWord, value, combining, inSelection, autoRefresh, keepResults);
  }

  /**
   * Returns a copy with combining set.
   *
   * @param value true parses quoted terms and operators
   * @return the copy
   */
  public SearchOptions withCombining(boolean value) {
    return new SearchOptions(
        mode, caseSensitive, wholeWord, inverse, value, inSelection, autoRefresh, keepResults);
  }

  /**
   * Returns a copy with the selection scope set.
   *
   * @param value true searches the selected lines
   * @return the copy
   */
  public SearchOptions withInSelection(boolean value) {
    return new SearchOptions(
        mode, caseSensitive, wholeWord, inverse, combining, value, autoRefresh, keepResults);
  }

  /**
   * Returns a copy with auto-refresh set.
   *
   * @param value true extends a finished search over new lines
   * @return the copy
   */
  public SearchOptions withAutoRefresh(boolean value) {
    return new SearchOptions(
        mode, caseSensitive, wholeWord, inverse, combining, inSelection, value, keepResults);
  }

  /**
   * Returns a copy with keep-results set.
   *
   * @param value true pins the current results when the next search runs
   * @return the copy
   */
  public SearchOptions withKeepResults(boolean value) {
    return new SearchOptions(
        mode, caseSensitive, wholeWord, inverse, combining, inSelection, autoRefresh, value);
  }
}
