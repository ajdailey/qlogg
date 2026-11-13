package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.core.search.SearchLimit;
import org.junit.jupiter.api.Test;

/** The Search All Open Files rows; expected values are the English bundle's. */
class MultiSearchTextTest {
  private static SearchViewState state(
      SearchViewState.Phase phase, SearchLimit limit, long skipped, String error) {
    return new SearchViewState(
        "hit", SearchOptions.DEFAULTS, phase, 50, 1234, limit, skipped, error);
  }

  @Test
  void eachPhaseHasItsRow() {
    assertEquals(
        "app.log: 1234 matches (searching)",
        MultiSearchText.text(
            "app.log", state(SearchViewState.Phase.RUNNING, SearchLimit.NONE, 0, null)));
    assertEquals(
        "app.log: 1234 matches (stopped)",
        MultiSearchText.text(
            "app.log", state(SearchViewState.Phase.CANCELED, SearchLimit.NONE, 0, null)));
    assertEquals(
        "app.log: 1234 matches",
        MultiSearchText.text(
            "app.log", state(SearchViewState.Phase.DONE, SearchLimit.NONE, 0, null)));
  }

  @Test
  void aFailureShowsItsError() {
    assertEquals(
        "app.log: mock error",
        MultiSearchText.text(
            "app.log", state(SearchViewState.Phase.FAILED, SearchLimit.NONE, 0, "mock error")));
    assertEquals(
        "app.log: null",
        MultiSearchText.text(
            "app.log", state(SearchViewState.Phase.FAILED, SearchLimit.NONE, 0, null)));
  }

  @Test
  void aLimitOrSkippedLinesShowTheSearchBarNotice() {
    SearchViewState memory = state(SearchViewState.Phase.DONE, SearchLimit.MEMORY, 0, null);
    assertEquals(
        "app.log: Search stopped at 1,234 matches (memory limit); narrow the search or close files",
        MultiSearchText.text("app.log", memory));
    SearchViewState skipped = state(SearchViewState.Phase.DONE, SearchLimit.NONE, 2, null);
    assertEquals("app.log: " + skipped.statusText(), MultiSearchText.text("app.log", skipped));
  }
}
