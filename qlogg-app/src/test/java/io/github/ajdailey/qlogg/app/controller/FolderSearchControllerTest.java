package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.FolderSearchSettings;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.UiState;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.FolderRequest;
import io.github.ajdailey.qlogg.app.view.FolderSearchState;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.folder.FileHits;
import io.github.ajdailey.qlogg.core.folder.NamePattern;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Search > Search in Folder: the request taken from the shown tab, the result model, opening a
 * listed line in a new tab or the tab that shows the file, the failures before the search thread,
 * Stop, exclude, the kept sets with Pin, Clear and Clear All, the drop of the oldest unpinned set
 * when the share refuses a run, and the step keys.
 */
class FolderSearchControllerTest {
  private static final SearchOptions LITERAL =
      new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, false, false);

  @TempDir Path temp;

  private static void runUntilEnded(ControllerFixture fixture, final FolderSearchController search)
      throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return search.state().phase() != FolderSearchState.Phase.RUNNING;
          }
        });
  }

  private Path logs() throws Exception {
    Path dir = Files.createDirectory(temp.resolve("logs"));
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      text.append(i % 10 == 4 ? "hit " : "line ").append(i).append('\n');
    }
    Files.writeString(dir.resolve("app.log"), text.toString(), StandardCharsets.US_ASCII);
    Files.writeString(dir.resolve("app.log.1"), "line\nhit old\n", StandardCharsets.US_ASCII);
    Files.writeString(dir.resolve("notes.txt"), "hit\n", StandardCharsets.US_ASCII);
    return dir;
  }

  @Test
  void theWindowStartsFromTheShownTabsSearchAndFolder() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      FolderSearchController search = fixture.application.folderSearch();
      assertEquals(FolderRequest.EMPTY, search.request());
      DocumentController document = fixture.open(fixture.lines("one.log", 10));
      document.search().setFieldText("hit");
      fixture.application.execute(CommandId.SEARCH_FOLDER);
      assertEquals(1, fixture.view.folderSearchShown);
      FolderRequest request = search.request();
      assertEquals(document.document().path().getParent().toString(), request.folder());
      assertEquals(NamePattern.DEFAULT, request.names());
      assertEquals("hit", request.text());
      assertEquals(document.search().options(), request.options());
    }
  }

  @Test
  void aRunRemembersTheFolderNamesAndSubfoldersForTheNextStart() throws Exception {
    Path dir = logs();
    FolderSearchSettings saved = new FolderSearchSettings(dir.toString(), "*.txt", true, "");
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      FolderSearchController search = fixture.application.folderSearch();
      search.run(new FolderRequest(dir.toString(), "*.txt", true, "hit", LITERAL, ""));
      runUntilEnded(fixture, search);
      assertEquals(saved, fixture.application.preferences().state().folderSearch());
    }
    Preferences restored = Preferences.DEFAULTS.withState(UiState.DEFAULTS.withFolderSearch(saved));
    try (ControllerFixture fixture = new ControllerFixture(temp, restored)) {
      FolderRequest request = fixture.application.folderSearch().request();
      assertEquals(dir.toString(), request.folder());
      assertEquals("*.txt", request.names());
      assertTrue(request.recursive());
    }
  }

  @Test
  void listsMatchingLinesPerFileAndOpensOneAtItsLine() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      Path dir = logs();
      final FolderSearchController search = fixture.application.folderSearch();
      search.run(new FolderRequest(dir.toString(), NamePattern.DEFAULT, false, "hit", LITERAL, ""));
      runUntilEnded(fixture, search);
      assertEquals(FolderSearchState.Phase.DONE, search.state().phase());
      List<FileHits> files = search.files();
      assertEquals(2, files.size());
      FileHits app = files.get(0);
      assertEquals(dir.toRealPath().resolve("app.log"), app.path());
      assertEquals(4, app.matches());
      assertEquals(14, app.line(1));
      assertEquals("hit 14", app.text(1));
      assertEquals(1, files.get(1).matches());
      assertEquals(5, search.matches());
      assertEquals(2, search.state().progress().filesDone());
      assertEquals(2, search.state().progress().filesTotal());

      search.open(app, 1);
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              DocumentController shown = fixture.application.activeDocument();
              return shown != null
                  && shown.state().title().equals("app.log")
                  && shown.selection().caret().line() == 14;
            }
          });
      search.open(app, 3);
      final DocumentController shown = fixture.application.activeDocument();
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return shown.selection().caret().line() == 34;
            }
          });
      assertEquals(1, fixture.application.documents().size(), "the open tab is reused");
    }
  }

  @Test
  void problemsBeforeTheSearchThreadEndItAsFailed() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      Path dir = logs();
      FolderSearchController search = fixture.application.folderSearch();
      search.run(new FolderRequest(dir.toString(), NamePattern.DEFAULT, false, "", LITERAL, ""));
      assertEquals(FolderSearchState.Phase.FAILED, search.state().phase());
      assertEquals(UiText.FOLDER_NO_TEXT.text(), search.state().message());
      search.run(new FolderRequest(" ", NamePattern.DEFAULT, false, "hit", LITERAL, ""));
      assertEquals(UiText.FOLDER_NO_FOLDER.text(), search.state().message());
      search.run(new FolderRequest(dir.toString(), "[", false, "hit", LITERAL, ""));
      assertTrue(
          search.state().message().startsWith(UiText.FOLDER_BAD_NAMES.format("")),
          search.state().message());
      search.run(
          new FolderRequest(
              dir.toString(),
              NamePattern.DEFAULT,
              false,
              "\"hit",
              LITERAL.withCombining(true),
              ""));
      assertTrue(
          search.state().message().startsWith(UiText.SEARCH_EXPRESSION_ERROR.text().split("%")[0]),
          search.state().message());
      search.run(
          new FolderRequest(
              dir.resolve("missing").toString(), NamePattern.DEFAULT, false, "hit", LITERAL, ""));
      runUntilEnded(fixture, search);
      assertEquals(FolderSearchState.Phase.FAILED, search.state().phase());
      assertTrue(
          search.state().message().startsWith(UiText.FOLDER_FAILED.format("")),
          search.state().message());
    }
  }

  @Test
  void stopEndsTheRunAndClearAllReturnsTheResults() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      Path dir = logs();
      Files.writeString(dir.resolve("runaway.log"), "a".repeat(60) + "\n");
      final FolderSearchController search = fixture.application.folderSearch();
      search.run(new FolderRequest(dir.toString(), NamePattern.DEFAULT, false, "hit", LITERAL, ""));
      runUntilEnded(fixture, search);
      assertTrue(fixture.budget.reserved(MemoryShare.RESULTS) > 0);
      search.clear();
      assertEquals(0, fixture.budget.reserved(MemoryShare.RESULTS));
      assertEquals(0, search.files().size());
      search.run(
          new FolderRequest(
              dir.toString(), "runaway.log", false, "(.*a){25}x", SearchOptions.DEFAULTS, ""));
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              FolderSearchState state = search.state();
              return state.progress().file() != null && state.progress().filePercent() >= 50;
            }
          });
      search.cancel();
      runUntilEnded(fixture, search);
      assertEquals(FolderSearchState.Phase.CANCELED, search.state().phase());
      assertEquals(0, search.files().size());
      search.clear();
      assertEquals(0, fixture.budget.reserved(MemoryShare.RESULTS));
    }
  }

  // Exclude, kept sets and step keys.

  private static void runAndWait(
      ControllerFixture fixture, FolderSearchController search, FolderRequest request)
      throws InterruptedException {
    search.run(request);
    runUntilEnded(fixture, search);
  }

  private static FolderRequest hits(Path dir) {
    return new FolderRequest(dir.toString(), NamePattern.DEFAULT, false, "hit", LITERAL, "");
  }

  @Test
  void excludeLeavesOutFilesAndIsRememberedWithTheOtherFields() throws Exception {
    Path dir = logs();
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      FolderSearchController search = fixture.application.folderSearch();
      runAndWait(
          fixture, search, new FolderRequest(dir.toString(), "*", false, "hit", LITERAL, "*.txt"));
      assertEquals(2, search.files().size());
      assertEquals("*.txt", fixture.application.preferences().state().folderSearch().exclude());
      search.run(new FolderRequest(dir.toString(), "*", false, "hit", LITERAL, "["));
      assertTrue(
          search.state().message().startsWith(UiText.FOLDER_BAD_NAMES.format("")),
          search.state().message());
      runAndWait(
          fixture,
          search,
          new FolderRequest(dir.toString(), "*", false, "hit", LITERAL, "*.txt; *.1"));
    }
    try (ControllerFixture fixture =
        new ControllerFixture(
            temp,
            Preferences.DEFAULTS.withState(
                UiState.DEFAULTS.withFolderSearch(
                    new FolderSearchSettings(dir.toString(), "*", false, "*.txt; *.1"))))) {
      assertEquals("*.txt; *.1", fixture.application.folderSearch().request().exclude());
    }
  }

  @Test
  void tenUnpinnedSetsAreKeptNewestFirstAndPinClearAndClearAllApply() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      Path dir = logs();
      FolderSearchController search = fixture.application.folderSearch();
      runAndWait(fixture, search, hits(dir));
      FolderResultSet oldest = search.shown();
      search.togglePin(oldest);
      assertTrue(oldest.pinned());
      List<FolderResultSet> runs = new ArrayList<FolderResultSet>();
      for (int i = 0; i < FolderSearchController.KEPT_SETS + 2; i++) {
        runAndWait(fixture, search, hits(dir));
        runs.add(search.shown());
      }
      List<FolderResultSet> sets = search.sets();
      assertEquals(FolderSearchController.KEPT_SETS + 1, sets.size());
      assertSame(runs.get(runs.size() - 1), sets.get(0), "newest first");
      assertSame(runs.get(2), sets.get(FolderSearchController.KEPT_SETS - 1));
      assertSame(oldest, sets.get(FolderSearchController.KEPT_SETS), "the pin keeps it");
      assertEquals(0, runs.get(0).files().size(), "a dropped set returns its results");

      search.show(oldest);
      assertEquals(5, search.matches());
      search.drop(oldest);
      assertSame(sets.get(0), search.shown(), "the newest set is shown after a Clear");
      assertEquals(FolderSearchController.KEPT_SETS, search.sets().size());
      assertTrue(fixture.budget.reserved(MemoryShare.RESULTS) > 0);
      search.clear();
      assertEquals(List.of(), search.sets());
      assertEquals(FolderSearchState.Phase.IDLE, search.state().phase());
      assertEquals(0, fixture.budget.reserved(MemoryShare.RESULTS));
    }
  }

  @Test
  void showIgnoresASetNotKeptOrWhoseResultsAreClosed() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      Path dir = logs();
      FolderSearchController search = fixture.application.folderSearch();
      runAndWait(fixture, search, hits(dir));
      FolderResultSet dropped = search.shown();
      runAndWait(fixture, search, hits(dir));
      FolderResultSet reclaimed = search.shown();
      runAndWait(fixture, search, hits(dir));
      FolderResultSet newest = search.shown();
      search.drop(dropped);
      search.show(dropped);
      assertSame(newest, search.shown());
      // As a refused run's thread closes it, before the UI thread drops it.
      reclaimed.results().close();
      search.show(reclaimed);
      assertSame(newest, search.shown());
    }
  }

  @Test
  void aRunTheShareRefusesDropsTheOldestUnpinnedSetWithOneNotice() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      Path dir = Files.createDirectory(temp.resolve("many"));
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < 3000; i++) {
        text.append("hit ").append(i).append('\n');
      }
      Files.writeString(dir.resolve("app.log"), text.toString(), StandardCharsets.US_ASCII);
      FolderSearchController search = fixture.application.folderSearch();
      runAndWait(fixture, search, hits(dir));
      FolderResultSet pinned = search.shown();
      long one = fixture.budget.reserved(MemoryShare.RESULTS);
      search.togglePin(pinned);
      runAndWait(fixture, search, hits(dir));
      FolderResultSet older = search.shown();
      runAndWait(fixture, search, hits(dir));
      FolderResultSet newer = search.shown();
      long filler = fixture.budget.available(MemoryShare.RESULTS) - one / 2;
      assertTrue(fixture.budget.reserve(MemoryShare.RESULTS, filler));
      try {
        runAndWait(fixture, search, hits(dir));
        FolderResultSet latest = search.shown();
        assertFalse(latest.limited());
        assertEquals(3000, latest.files().get(0).size());
        assertEquals(List.of(latest, newer, pinned), search.sets());
        assertEquals(0, older.files().size());
        int dropped = 0;
        for (String notice : fixture.view.notices) {
          if (notice.equals(UiText.NOTICE_FOLDER_SET_DROPPED.text())) {
            dropped++;
          }
        }
        assertEquals(1, dropped);
      } finally {
        fixture.budget.release(MemoryShare.RESULTS, filler);
      }
    }
  }

  @Test
  void stepKeysGoThroughTheShownSetAcrossFilesAndWrapWithANotice() throws Exception {
    try (final ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      fixture.application.execute(CommandId.NEXT_FOLDER_MATCH);
      assertEquals(UiText.NOTICE_FOLDER_NO_LINES.text(), fixture.view.lastNotice());
      Path dir = logs();
      FolderSearchController search = fixture.application.folderSearch();
      runAndWait(fixture, search, hits(dir));
      List<FileHits> files = search.files();
      FileHits app = files.get(0);
      FileHits old = files.get(1);
      int[][] expected = {{0, 0}, {0, 1}, {0, 2}, {0, 3}, {1, 0}};
      for (int[] step : expected) {
        fixture.application.execute(CommandId.NEXT_FOLDER_MATCH);
        assertSame(files.get(step[0]), search.stepFile());
        assertEquals(step[1], search.stepHit());
        final FileHits file = files.get(step[0]);
        final long line = file.line(step[1]);
        fixture.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = fixture.application.activeDocument();
                return shown != null
                    && file.path().equals(shown.document().path())
                    && shown.selection().caret().line() == line;
              }
            });
      }
      int notices = fixture.view.notices.size();
      fixture.application.execute(CommandId.NEXT_FOLDER_MATCH);
      assertSame(app, search.stepFile());
      assertEquals(0, search.stepHit());
      assertEquals(UiText.NOTICE_FOLDER_WRAPPED_FIRST.text(), fixture.view.lastNotice());
      fixture.application.execute(CommandId.PREVIOUS_FOLDER_MATCH);
      assertSame(old, search.stepFile());
      assertEquals(UiText.NOTICE_FOLDER_WRAPPED_LAST.text(), fixture.view.lastNotice());
      assertEquals(notices + 2, fixture.view.notices.size());
      fixture.application.execute(CommandId.PREVIOUS_FOLDER_MATCH);
      assertSame(app, search.stepFile());
      assertEquals(3, search.stepHit());

      search.stepFrom(old);
      fixture.application.execute(CommandId.PREVIOUS_FOLDER_MATCH);
      assertSame(app, search.stepFile());
      assertEquals(3, search.stepHit());
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              DocumentController shown = fixture.application.activeDocument();
              return shown != null
                  && shown.state().title().equals("app.log")
                  && shown.selection().caret().line() == 34;
            }
          });
    }
  }

  @Test
  void stepsIntoAFileStillOpeningOpenItOnceAtTheLastLine() throws Exception {
    try (final ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      Path dir = logs();
      FolderSearchController search = fixture.application.folderSearch();
      runAndWait(fixture, search, hits(dir));
      fixture.application.execute(CommandId.NEXT_FOLDER_MATCH);
      fixture.application.execute(CommandId.NEXT_FOLDER_MATCH);
      fixture.application.execute(CommandId.NEXT_FOLDER_MATCH);
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              DocumentController shown = fixture.application.activeDocument();
              return shown != null && shown.selection().caret().line() == 24;
            }
          });
      fixture.runUntilIdle();
      assertEquals(1, fixture.application.documents().size());
      assertEquals(24, fixture.application.activeDocument().selection().caret().line());
    }
  }

  @Test
  void anOpenAfterAFailedOpenOfTheSameFileOpensIt() throws Exception {
    Path dir = Files.createDirectory(temp.resolve("logs"));
    Path log = dir.resolve("app.log");
    Files.writeString(log, "line\nhit 1\nline\nhit 3\n", StandardCharsets.US_ASCII);
    try (final ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      final FolderSearchController search = fixture.application.folderSearch();
      search.run(new FolderRequest(dir.toString(), NamePattern.DEFAULT, false, "hit", LITERAL, ""));
      runUntilEnded(fixture, search);
      FileHits app = search.files().get(0);
      byte[] saved = Files.readAllBytes(log);
      // Rotated away between the search and the open: the open fails.
      Files.delete(log);
      search.open(app, 0);
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return !fixture.view.errors.isEmpty();
            }
          });
      // Back again, and opened within OPEN_WAIT_NANOS of the failed open.
      Files.write(log, saved);
      search.open(app, 1);
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              DocumentController shown = fixture.application.activeDocument();
              return shown != null && shown.selection().caret().line() == 3;
            }
          });
      assertEquals(1, fixture.application.documents().size());
    }
  }
}
