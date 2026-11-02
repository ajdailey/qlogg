package io.github.ajdailey.qlogg.app.search;

import io.github.ajdailey.qlogg.core.search.MatchMode;

/**
 * Add, exclude and replace search with selected text ({@code Shift+A}, {@code Shift+E}, {@code
 * Shift+R}). The selection is escaped so it matches as typed: regex metacharacters in regex mode,
 * double quotes inside a combining term. Adding to a literal search, and excluding, turn combining
 * on; existing text that is not yet an expression of quoted terms is quoted as one term.
 */
public final class SearchEdits {
  /**
   * The search text after an edit.
   *
   * @param text the new text
   * @param combining whether the text is a combined expression
   */
  public record Edit(String text, boolean combining) {}

  private SearchEdits() {}

  /**
   * Replaces the search with the selection.
   *
   * @param selection the selected text
   * @param mode the search mode
   * @param combining the combining toggle
   * @return the edit
   */
  public static Edit replace(String selection, MatchMode mode, boolean combining) {
    String term = escaped(selection, mode);
    return new Edit(combining ? FilterPatterns.quote(term) : term, combining);
  }

  /**
   * Adds the selection as an alternative: {@code text|sel} for a regex, {@code text or "sel"} with
   * combining.
   *
   * @param text the current search text
   * @param selection the selected text
   * @param mode the search mode
   * @param combining the combining toggle
   * @return the edit
   */
  public static Edit add(String text, String selection, MatchMode mode, boolean combining) {
    if (text.isEmpty()) {
      return replace(selection, mode, combining);
    }
    String term = escaped(selection, mode);
    if (combining) {
      return new Edit(asExpression(text) + " or " + FilterPatterns.quote(term), true);
    }
    if (mode == MatchMode.REGEX) {
      return new Edit(text + "|" + term, false);
    }
    return new Edit(FilterPatterns.quote(text) + " or " + FilterPatterns.quote(term), true);
  }

  /**
   * Excludes lines holding the selection: {@code "text" and not "sel"}, with combining on.
   *
   * @param text the current search text
   * @param selection the selected text
   * @param mode the search mode
   * @param combining the combining toggle
   * @return the edit
   */
  public static Edit exclude(String text, String selection, MatchMode mode, boolean combining) {
    String term = FilterPatterns.quote(escaped(selection, mode));
    if (text.isEmpty()) {
      return new Edit("not " + term, true);
    }
    String kept =
        combining && text.indexOf('"') >= 0 ? "(" + text + ")" : FilterPatterns.quote(text);
    return new Edit(kept + " and not " + term, true);
  }

  /** A combining text without a double quote is one term, so it is quoted before an operator. */
  private static String asExpression(String text) {
    return text.indexOf('"') < 0 ? FilterPatterns.quote(text) : text;
  }

  private static String escaped(String selection, MatchMode mode) {
    return mode == MatchMode.REGEX ? FilterPatterns.escapeRegex(selection) : selection;
  }
}
