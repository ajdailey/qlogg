package io.github.ajdailey.qlogg.app.view;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.core.search.SearchLimit;
import org.junit.jupiter.api.Test;

class SearchViewStateTest {
  private static SearchViewState done(SearchLimit limit, long skipped) {
    return new SearchViewState(
        "needle", SearchOptions.DEFAULTS, SearchViewState.Phase.DONE, 100, 3, limit, skipped, null);
  }

  @Test
  void aFinishedSearchNamesTheLinesItSkipped() {
    assertEquals("3 matches", done(SearchLimit.NONE, 0).statusText());
    assertEquals("3 matches; lines too long to search: 1", done(SearchLimit.NONE, 1).statusText());
    assertEquals(
        "Search stopped at 3 matches (result limit); lines too long to search: 1,200",
        done(SearchLimit.MAX_RESULTS, 1200).statusText());
  }
}
