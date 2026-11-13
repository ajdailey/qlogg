package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.search.SearchLimit;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The filtered view's result-set tab text; expected values are the English bundle's. */
class ResultTabsTest {
  private static final SearchOptions RANGE =
      SearchOptions.DEFAULTS.withRange(true).withRangeEnd("mock end");

  private static SearchViewState state(String text, SearchOptions options) {
    return new SearchViewState(
        text, options, SearchViewState.Phase.DONE, 100, 0, SearchLimit.NONE, 0, null);
  }

  private static SearchController.KeptResults kept(String text, SearchOptions options, int lines) {
    MutableLineSet set = new MutableLineSet();
    for (int i = 0; i < lines; i++) {
      set.add(i);
    }
    return new SearchController.KeptResults(text, options, set, SearchLimit.NONE);
  }

  @Test
  void theCurrentTabShowsTheTextAndCount() {
    assertEquals(
        "flagword (1,234)", ResultTabs.current(state("flagword", SearchOptions.DEFAULTS), 1234));
  }

  @Test
  void theCurrentTabWithoutTextShowsTheEmptyLabel() {
    assertEquals("Results", ResultTabs.current(state("", SearchOptions.DEFAULTS), 7));
  }

  @Test
  void aRangeSearchShowsItsEnd() {
    assertEquals("from .. mock end (2)", ResultTabs.current(state("from", RANGE), 2));
    assertEquals("from .. mock end", ResultTabs.tip("from", RANGE));
  }

  @Test
  void textPast32CharactersIsCutTo31AndAnEllipsis() {
    String exact = "a".repeat(32);
    assertEquals(exact + " (1)", ResultTabs.current(state(exact, SearchOptions.DEFAULTS), 1));
    String longer = "b".repeat(33);
    assertEquals(
        "b".repeat(31) + "... (1)", ResultTabs.current(state(longer, SearchOptions.DEFAULTS), 1));
    assertEquals(longer, ResultTabs.tip(longer, SearchOptions.DEFAULTS));
  }

  @Test
  void theRangeLabelIsCutAfterTheEndIsAdded() {
    String text = "c".repeat(25);
    assertEquals(text + " .. mo... (3)", ResultTabs.kept(kept(text, RANGE, 3)));
  }

  @Test
  void ampersandsAreDoubledInTextAndTips() {
    assertEquals("a&&b (0)", ResultTabs.current(state("a&b", SearchOptions.DEFAULTS), 0));
    assertEquals("a&&b (2)", ResultTabs.kept(kept("a&b", SearchOptions.DEFAULTS, 2)));
    assertEquals("a&&b", ResultTabs.tip("a&b", SearchOptions.DEFAULTS));
  }

  @Test
  void theAccessibleNameCountsTheShownSet() {
    List<SearchController.KeptResults> sets = List.of(kept("x", SearchOptions.DEFAULTS, 5));
    assertEquals(
        "Filtered results",
        ResultTabs.accessibleName(state("", SearchOptions.DEFAULTS), -1, 9, sets));
    assertEquals(
        "Filtered results, 9 matches",
        ResultTabs.accessibleName(state("x", SearchOptions.DEFAULTS), -1, 9, sets));
    assertEquals(
        "Filtered results, 5 matches",
        ResultTabs.accessibleName(state("", SearchOptions.DEFAULTS), 0, 9, sets));
    assertEquals(
        "Filtered results, 1,234 matches",
        ResultTabs.accessibleName(state("a&b", SearchOptions.DEFAULTS), -1, 1234, sets));
  }
}
