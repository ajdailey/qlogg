package io.github.ajdailey.qlogg.app.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchHistoryTest {
  @Test
  void addPutsTheSearchFirstOnceAndBoundsTheList() {
    List<String> history = List.of();
    history = SearchHistory.add(history, "a", 3);
    history = SearchHistory.add(history, "b", 3);
    history = SearchHistory.add(history, "c", 3);
    assertEquals(List.of("c", "b", "a"), history);
    history = SearchHistory.add(history, "a", 3);
    assertEquals(List.of("a", "c", "b"), history);
    history = SearchHistory.add(history, "d", 3);
    assertEquals(List.of("d", "a", "c"), history);
    assertEquals(List.of("d", "a"), SearchHistory.add(history, "", 2));
    String tooLong = "x".repeat(SearchHistory.MAX_ENTRY_CHARS + 1);
    assertEquals(history, SearchHistory.add(history, tooLong, 3));
  }

  @Test
  void trimDropsDuplicatesEmptyAndLongEntries() {
    String tooLong = "x".repeat(SearchHistory.MAX_ENTRY_CHARS + 1);
    assertEquals(
        List.of("a", "b"), SearchHistory.trim(List.of("a", "", "a", tooLong, "b", "c"), 2));
  }

  @Test
  void completionsFollowTheCaseRule() {
    List<String> history = List.of("Error 42", "error code", "err", "warning");
    assertEquals(List.of("error code"), SearchHistory.completions(history, "err", true));
    assertEquals(
        List.of("Error 42", "error code"), SearchHistory.completions(history, "ERR", false));
    assertEquals(List.of(), SearchHistory.completions(history, "ERR", true));
    assertEquals(List.of(), SearchHistory.completions(history, "", false));
  }
}
