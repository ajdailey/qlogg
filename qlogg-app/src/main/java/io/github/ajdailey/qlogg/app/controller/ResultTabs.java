package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import java.util.List;

/**
 * Builds the filtered view's result-set tab text: one tab for the current search, then one per
 * {@link SearchController.KeptResults}. Tab text and tool tips come back with every {@code &}
 * doubled, because a tab takes a single one as a mnemonic and SWT drops one from a tool tip.
 */
public final class ResultTabs {
  /** Longest search text a tab shows before it is cut. */
  static final int MAX_TAB_CHARS = 32;

  private ResultTabs() {}

  /**
   * Builds the current search's tab text.
   *
   * @param state the search's state
   * @param resultCount the current search's matching lines
   * @return the text; the empty-search label while no search text is set
   */
  public static String current(SearchViewState state, long resultCount) {
    String text =
        state.text().isEmpty()
            ? UiText.RESULTS_CURRENT_EMPTY.text()
            : UiText.RESULTS_TAB.format(shorten(label(state.text(), state.options())), resultCount);
    return text.replace("&", "&&");
  }

  /**
   * Builds a kept set's tab text.
   *
   * @param set the kept set
   * @return the text
   */
  public static String kept(SearchController.KeptResults set) {
    return UiText.RESULTS_TAB
        .format(shorten(label(set.text(), set.options())), set.lineCount())
        .replace("&", "&&");
  }

  /**
   * Builds a tab's tool tip: the search text in full, with a range search's end after it.
   *
   * @param text the search text
   * @param options the search's toggles
   * @return the tool tip
   */
  public static String tip(String text, SearchOptions options) {
    return label(text, options).replace("&", "&&");
  }

  /**
   * Builds the filtered view's accessible name: the plain name while no search text is set and the
   * current search is shown, else the name with the shown set's line count.
   *
   * @param state the current search's state
   * @param shown the shown kept set's index, or a negative number for the current search
   * @param resultCount the current search's matching lines
   * @param kept the kept sets
   * @return the name; not mnemonic-escaped
   */
  public static String accessibleName(
      SearchViewState state, int shown, long resultCount, List<SearchController.KeptResults> kept) {
    if (state.text().isEmpty() && shown < 0) {
      return UiText.ACCESSIBLE_FILTERED_VIEW.text();
    }
    return UiText.ACCESSIBLE_FILTERED_VIEW_COUNT.format(
        shown < 0 ? resultCount : kept.get(shown).lineCount());
  }

  /** A search's text, with a range search's end after it. */
  private static String label(String text, SearchOptions options) {
    return options.range() ? UiText.RESULTS_RANGE.format(text, options.rangeEnd()) : text;
  }

  private static String shorten(String text) {
    return text.length() > MAX_TAB_CHARS ? text.substring(0, MAX_TAB_CHARS - 1) + "..." : text;
  }
}
