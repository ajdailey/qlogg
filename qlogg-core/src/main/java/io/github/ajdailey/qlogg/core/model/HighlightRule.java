package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.search.MatchMode;

/**
 * One highlighter: a pattern and the colors it paints. Immutable.
 *
 * @param pattern the text or regular expression, at most {@link #MAX_PATTERN_CHARS}; an empty one
 *     matches nothing
 * @param mode {@code REGEX}, or {@code LITERAL} for text matched as typed
 * @param caseSensitive false folds case ({@code CASE_INSENSITIVE | UNICODE_CASE})
 * @param wholeLine true colors the whole line of a match; false colors the matched text, or only
 *     the captured groups when the pattern has groups
 * @param foreground text color, or null to keep the text color
 * @param background background color, or null for none
 * @param alpha opacity of the background, 0 (none) to 255 (opaque)
 * @param variance 0 for one color, else up to {@link #MAX_VARIANCE}: distinct matched strings of a
 *     match-only rule get colors up to that many percent darker or lighter
 * @param enabled false skips the rule
 */
public record HighlightRule(
    String pattern,
    MatchMode mode,
    boolean caseSensitive,
    boolean wholeLine,
    Rgb foreground,
    Rgb background,
    int alpha,
    int variance,
    boolean enabled) {
  /** Longest pattern. */
  public static final int MAX_PATTERN_CHARS = 4096;

  /** Largest {@link #variance}. */
  public static final int MAX_VARIANCE = 50;

  /** Checks the components. */
  public HighlightRule {
    if (pattern == null || mode == null) {
      throw new IllegalArgumentException("pattern and mode are required");
    }
    if (pattern.length() > MAX_PATTERN_CHARS) {
      throw new IllegalArgumentException("pattern is too long");
    }
    if (alpha < 0 || alpha > 255) {
      throw new IllegalArgumentException("alpha must be 0-255");
    }
    if (variance < 0 || variance > MAX_VARIANCE) {
      throw new IllegalArgumentException("variance out of range");
    }
  }

  /**
   * Creates an enabled, case-sensitive match-only rule with an opaque background and no variance.
   *
   * @param pattern the pattern
   * @param mode how the pattern is read
   * @param foreground text color, or null
   * @param background background color, or null
   * @return the rule
   */
  public static HighlightRule matchOnly(
      String pattern, MatchMode mode, Rgb foreground, Rgb background) {
    return new HighlightRule(pattern, mode, true, false, foreground, background, 255, 0, true);
  }

  /**
   * Returns a copy with the whole-line option set.
   *
   * @param value true colors the whole line
   * @return the copy
   */
  public HighlightRule withWholeLine(boolean value) {
    return new HighlightRule(
        pattern, mode, caseSensitive, value, foreground, background, alpha, variance, enabled);
  }

  /**
   * Returns a copy with case folding set.
   *
   * @param value true matches case
   * @return the copy
   */
  public HighlightRule withCaseSensitive(boolean value) {
    return new HighlightRule(
        pattern, mode, value, wholeLine, foreground, background, alpha, variance, enabled);
  }

  /**
   * Returns a copy with another opacity and variance.
   *
   * @param opacity 0 to 255
   * @param percent 0 to {@link #MAX_VARIANCE}
   * @return the copy
   */
  public HighlightRule withAlphaAndVariance(int opacity, int percent) {
    return new HighlightRule(
        pattern, mode, caseSensitive, wholeLine, foreground, background, opacity, percent, enabled);
  }

  /**
   * Returns a copy enabled or disabled.
   *
   * @param value true applies the rule
   * @return the copy
   */
  public HighlightRule withEnabled(boolean value) {
    return new HighlightRule(
        pattern, mode, caseSensitive, wholeLine, foreground, background, alpha, variance, value);
  }
}
