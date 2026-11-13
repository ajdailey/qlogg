package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.app.view.FolderRequest;
import io.github.ajdailey.qlogg.app.view.FolderSearchState;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.folder.FileHits;
import io.github.ajdailey.qlogg.core.folder.FolderProgress;
import io.github.ajdailey.qlogg.core.folder.FolderResults;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Search in Folder window's text; expected values are the English bundle's. */
class FolderSearchTextTest {
  private static FolderSearchState state(FolderSearchState.Phase phase, FolderProgress progress) {
    return new FolderSearchState(phase, progress, "mock failure");
  }

  @Test
  void theSummaryFollowsThePhase() {
    assertEquals(
        "Choose a folder, file names and search text",
        FolderSearchText.summary(FolderSearchState.IDLE, "", List.<FileHits>of(), false));
    assertEquals(
        "Listing files...",
        FolderSearchText.summary(
            state(FolderSearchState.Phase.RUNNING, FolderProgress.START), "hit", 0, 0, 0, false));
    FolderProgress progress =
        new FolderProgress(2, 5, Path.of("logs", "app.log"), 50, 250, 1000, 1234);
    assertEquals(
        "Searching app.log: file 3 of 5 (25%), 1,234 matches",
        FolderSearchText.summary(
            state(FolderSearchState.Phase.RUNNING, progress), "hit", 0, 0, 0, false));
    assertEquals(
        "\"hit\": 1,234 matches in 2 of 3 files",
        FolderSearchText.summary(
            state(FolderSearchState.Phase.DONE, progress), "hit", 1234, 2, 3, false));
    assertEquals(
        "\"hit\": stopped at 1,234 matches (memory limit); narrow the search or the file names",
        FolderSearchText.summary(
            state(FolderSearchState.Phase.DONE, progress), "hit", 1234, 2, 3, true));
    assertEquals(
        "\"hit\": stopped, 1,234 matches in 2 files",
        FolderSearchText.summary(
            state(FolderSearchState.Phase.CANCELED, progress), "hit", 1234, 2, 3, false));
    assertEquals(
        "mock failure",
        FolderSearchText.summary(
            state(FolderSearchState.Phase.FAILED, progress), "hit", 0, 0, 0, false));
  }

  @Test
  void theRunningFileNumberStopsAtTheTotal() {
    FolderProgress last = new FolderProgress(5, 5, null, 0, 1000, 1000, 0);
    assertEquals(
        "Searching : file 5 of 5 (100%), 0 matches",
        FolderSearchText.summary(
            state(FolderSearchState.Phase.RUNNING, last), "hit", 0, 0, 0, false));
  }

  @Test
  void aFileStatusNamesWhyItWasNotSearched() {
    assertEquals("Searched", FolderSearchText.status(FileHits.Status.SEARCHED, true, 3, null));
    assertEquals(
        "1,000 listed (memory limit)",
        FolderSearchText.status(FileHits.Status.SEARCHED, false, 1000, null));
    assertEquals(
        "Unreadable: mock reason",
        FolderSearchText.status(FileHits.Status.UNREADABLE, true, 0, "mock reason"));
    assertEquals("Not text", FolderSearchText.status(FileHits.Status.NOT_TEXT, true, 0, null));
    assertEquals(
        "Access denied", FolderSearchText.status(FileHits.Status.ACCESS_DENIED, true, 0, null));
    assertEquals("Failed: null", FolderSearchText.status(FileHits.Status.FAILED, false, 0, null));
  }

  @Test
  void aSetLineNamesTheFolderAndMarksRunningAndPinned() {
    assertEquals(
        "\"hit\" in logs: 1,234 matches",
        FolderSearchText.set("hit", "/var/logs/", 1234, false, false));
    assertEquals(
        "\"hit\" in logs: 0 matches (searching) (pinned)",
        FolderSearchText.set("hit", "C:\\logs", 0, true, true));
  }

  @Test
  void aKeptSetLineComesFromTheSet() {
    FolderRequest request =
        new FolderRequest("/mock/logs", "*.log", false, "hit", SearchOptions.DEFAULTS, "");
    FolderResultSet set = new FolderResultSet(request, new FolderResults(MemoryBudget.of(1 << 20)));
    assertEquals("\"hit\" in logs: 0 matches (searching)", FolderSearchText.set(set));
    set.setState(FolderSearchState.IDLE);
    set.setPinned(true);
    assertEquals("\"hit\" in logs: 0 matches (pinned)", FolderSearchText.set(set));
  }

  @Test
  void theLastNameIsTakenFromTheTextAsTyped() {
    assertEquals("logs", FolderSearchText.lastName("  /var/logs//  "));
    assertEquals("logs", FolderSearchText.lastName("C:\\data\\logs\\"));
    assertEquals("logs", FolderSearchText.lastName("logs"));
    assertEquals("/", FolderSearchText.lastName("/"));
    assertEquals("", FolderSearchText.lastName(""));
  }
}
