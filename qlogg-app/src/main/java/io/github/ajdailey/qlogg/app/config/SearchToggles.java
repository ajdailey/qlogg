package io.github.ajdailey.qlogg.app.config;

/**
 * The search toggles a new tab starts with, as {@code searchOption.*} keeps them. In Selection is
 * never remembered.
 *
 * @param regex regex rather than literal text
 * @param caseSensitive match case
 * @param wholeWord whole words only
 * @param inverse lines that do not match
 * @param combining combined expressions
 * @param autoRefresh extend a finished search over new lines
 * @param keepResults keep the current results when the next search runs
 */
public record SearchToggles(
    boolean regex,
    boolean caseSensitive,
    boolean wholeWord,
    boolean inverse,
    boolean combining,
    boolean autoRefresh,
    boolean keepResults) {
  /** Regex, case-sensitive, every other toggle off. */
  public static final SearchToggles DEFAULTS =
      new SearchToggles(true, true, false, false, false, false, false);
}
