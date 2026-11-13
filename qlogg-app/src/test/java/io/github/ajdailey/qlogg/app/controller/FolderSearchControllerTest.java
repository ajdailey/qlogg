package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.FolderSearchSettings;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.UiState;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.FolderRequest;
import io.github.ajdailey.qlogg.app.view.FolderSearchState;
import io.github.ajdailey.qlogg.app.view.ManualUiDispatcher;
import io.github.ajdailey.qlogg.app.view.NotificationService;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.folder.FileHits;
import io.github.ajdailey.qlogg.core.folder.NamePattern;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
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
 * Search > Search in Folder against a fake {@link FolderSearchHost}: the request from the saved
 * settings, the result model, a listed line handed to the host's open, the failures before the
 * search thread, Stop, exclude, the kept sets with Pin, Clear and Clear All, the drop of the oldest
 * unpinned set when the share refuses a run, and the step order. {@link FolderSearchOpenTest}
 * covers the same controller through a window.
 */
class FolderSearchControllerTest {
  private static final SearchOptions LITERAL =
      new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, false, false);

  @TempDir Path temp;

  /** A window with no tab: records the settings, the opens and the notices. */
  private static final class FakeHost implements FolderSearchHost, NotificationService {
    final MemoryBudget budget = MemoryBudget.of(1L << 28);
    final ManualUiDispatcher ui = new ManualUiDispatcher();
    final Workers workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                throw new AssertionError("worker failed", error);
              }
            });
    final DocumentServices services = ApplicationControllerTest.services(workers, budget);
    final List<String> opened = new ArrayList<String>();
    final List<String> notices = new ArrayList<String>();
    final List<ApplicationListener> listeners = new ArrayList<ApplicationListener>();
    Preferences preferences;

    FakeHost(Preferences preferences) {
      this.preferences = preferences;
    }

    @Override
    public Preferences preferences() {
      return preferences;
    }

    @Override
    public void setPreferences(Preferences changed) {
      preferences = changed;
    }

    @Override
    public DocumentController activeDocument() {
      return null;
    }

    @Override
    public List<DocumentController> documents() {
      return List.of();
    }

    @Override
    public void activate(DocumentController document) {
      throw new AssertionError("no tab to show");
    }

    @Override
    public void openFile(Path path, long line) {
      opened.add(path.getFileName() + ":" + line);
    }

    @Override
    public void addListener(ApplicationListener listener) {
      listeners.add(listener);
    }

    @Override
    public DocumentServices services() {
      return services;
    }

    @Override
    public void showError(String title, String message) {
      notices.add(message);
    }

    @Override
    public void showInfo(String title, String message) {
      notices.add(message);
    }

    @Override
    public void showNotice(String message) {
      notices.add(message);
    }

    FolderSearchController search() {
      return new FolderSearchController(this, ui, this);
    }

    void runUntilEnded(final FolderSearchController search) throws InterruptedException {
      assertTrue(
          ui.runUntil(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return search.state().phase() != FolderSearchState.Phase.RUNNING;
                }
              },
              ControllerFixture.BOUND));
    }

    void runAndWait(FolderSearchController search, FolderRequest request)
        throws InterruptedException {
      search.run(request);
      runUntilEnded(search);
    }

    /** Clears the search, then closes the workers within the bound. */
    void close(FolderSearchController search) {
      search.clear();
      assertTrue(workers.close(ControllerFixture.BOUND));
    }
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

  private static FolderRequest hits(Path dir) {
    return new FolderRequest(dir.toString(), NamePattern.DEFAULT, false, "hit", LITERAL, "");
  }

  @Test
  void aRunRemembersTheFolderNamesAndSubfoldersForTheNextStart() throws Exception {
    Path dir = logs();
    FolderSearchSettings saved = new FolderSearchSettings(dir.toString(), "*.txt", true, "");
    FakeHost host = new FakeHost(Preferences.DEFAULTS);
    FolderSearchController search = host.search();
    try {
      assertEquals(FolderRequest.EMPTY, search.request());
      host.runAndWait(search, new FolderRequest(dir.toString(), "*.txt", true, "hit", LITERAL, ""));
      assertEquals(saved, host.preferences().state().folderSearch());
      FolderRequest request = new FolderSearchController(host, host.ui, host).request();
      assertEquals(dir.toString(), request.folder());
      assertEquals("*.txt", request.names());
      assertTrue(request.recursive());
    } finally {
      host.close(search);
    }
  }

  @Test
  void listsMatchingLinesPerFileAndHandsAListedLineToTheHost() throws Exception {
    Path dir = logs();
    FakeHost host = new FakeHost(Preferences.DEFAULTS);
    FolderSearchController search = host.search();
    try {
      host.runAndWait(search, hits(dir));
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
      search.open(app, 3);
      assertEquals(List.of("app.log:15"), host.opened, "a file still opening is not opened again");
      assertEquals(1, host.listeners.size());
      assertSame(app, search.stepFile());
      assertEquals(3, search.stepHit());
    } finally {
      host.close(search);
    }
  }

  @Test
  void problemsBeforeTheSearchThreadEndItAsFailed() throws Exception {
    Path dir = logs();
    FakeHost host = new FakeHost(Preferences.DEFAULTS);
    FolderSearchController search = host.search();
    try {
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
      host.runAndWait(
          search,
          new FolderRequest(
              dir.resolve("missing").toString(), NamePattern.DEFAULT, false, "hit", LITERAL, ""));
      assertEquals(FolderSearchState.Phase.FAILED, search.state().phase());
      assertTrue(
          search.state().message().startsWith(UiText.FOLDER_FAILED.format("")),
          search.state().message());
    } finally {
      host.close(search);
    }
  }

  @Test
  void stopEndsTheRunAndClearAllReturnsTheResults() throws Exception {
    Path dir = logs();
    Files.writeString(dir.resolve("runaway.log"), "a".repeat(60) + "\n");
    FakeHost host = new FakeHost(Preferences.DEFAULTS);
    final FolderSearchController search = host.search();
    try {
      host.runAndWait(search, hits(dir));
      assertTrue(host.budget.reserved(MemoryShare.RESULTS) > 0);
      search.clear();
      assertEquals(0, host.budget.reserved(MemoryShare.RESULTS));
      assertEquals(0, search.files().size());
      search.run(
          new FolderRequest(
              dir.toString(), "runaway.log", false, "(.*a){25}x", SearchOptions.DEFAULTS, ""));
      assertTrue(
          host.ui.runUntil(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  FolderSearchState state = search.state();
                  return state.progress().file() != null && state.progress().filePercent() >= 50;
                }
              },
              ControllerFixture.BOUND));
      search.cancel();
      host.runUntilEnded(search);
      assertEquals(FolderSearchState.Phase.CANCELED, search.state().phase());
      assertEquals(0, search.files().size());
      search.clear();
      assertEquals(0, host.budget.reserved(MemoryShare.RESULTS));
    } finally {
      host.close(search);
    }
  }

  @Test
  void excludeLeavesOutFilesAndIsRememberedWithTheOtherFields() throws Exception {
    Path dir = logs();
    FakeHost host = new FakeHost(Preferences.DEFAULTS);
    FolderSearchController search = host.search();
    try {
      host.runAndWait(
          search, new FolderRequest(dir.toString(), "*", false, "hit", LITERAL, "*.txt"));
      assertEquals(2, search.files().size());
      assertEquals("*.txt", host.preferences().state().folderSearch().exclude());
      search.run(new FolderRequest(dir.toString(), "*", false, "hit", LITERAL, "["));
      assertTrue(
          search.state().message().startsWith(UiText.FOLDER_BAD_NAMES.format("")),
          search.state().message());
    } finally {
      host.close(search);
    }
    FakeHost restored =
        new FakeHost(
            Preferences.DEFAULTS.withState(
                UiState.DEFAULTS.withFolderSearch(
                    new FolderSearchSettings(dir.toString(), "*", false, "*.txt; *.1"))));
    FolderSearchController next = restored.search();
    try {
      assertEquals("*.txt; *.1", next.request().exclude());
    } finally {
      restored.close(next);
    }
  }

  @Test
  void tenUnpinnedSetsAreKeptNewestFirstAndPinClearAndClearAllApply() throws Exception {
    Path dir = logs();
    FakeHost host = new FakeHost(Preferences.DEFAULTS);
    FolderSearchController search = host.search();
    try {
      host.runAndWait(search, hits(dir));
      FolderResultSet oldest = search.shown();
      search.togglePin(oldest);
      assertTrue(oldest.pinned());
      List<FolderResultSet> runs = new ArrayList<FolderResultSet>();
      for (int i = 0; i < FolderSearchController.KEPT_SETS + 2; i++) {
        host.runAndWait(search, hits(dir));
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
      assertTrue(host.budget.reserved(MemoryShare.RESULTS) > 0);
      search.clear();
      assertEquals(List.of(), search.sets());
      assertEquals(FolderSearchState.Phase.IDLE, search.state().phase());
      assertEquals(0, host.budget.reserved(MemoryShare.RESULTS));
    } finally {
      host.close(search);
    }
  }

  @Test
  void showIgnoresASetNotKeptOrWhoseResultsAreClosed() throws Exception {
    Path dir = logs();
    FakeHost host = new FakeHost(Preferences.DEFAULTS);
    FolderSearchController search = host.search();
    try {
      host.runAndWait(search, hits(dir));
      FolderResultSet dropped = search.shown();
      host.runAndWait(search, hits(dir));
      FolderResultSet reclaimed = search.shown();
      host.runAndWait(search, hits(dir));
      FolderResultSet newest = search.shown();
      search.drop(dropped);
      search.show(dropped);
      assertSame(newest, search.shown());
      // As a refused run's thread closes it, before the UI thread drops it.
      reclaimed.results().close();
      search.show(reclaimed);
      assertSame(newest, search.shown());
    } finally {
      host.close(search);
    }
  }

  @Test
  void aRunTheShareRefusesDropsTheOldestUnpinnedSetWithOneNotice() throws Exception {
    Path dir = Files.createDirectory(temp.resolve("many"));
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 3000; i++) {
      text.append("hit ").append(i).append('\n');
    }
    Files.writeString(dir.resolve("app.log"), text.toString(), StandardCharsets.US_ASCII);
    FakeHost host = new FakeHost(Preferences.DEFAULTS);
    FolderSearchController search = host.search();
    try {
      host.runAndWait(search, hits(dir));
      FolderResultSet pinned = search.shown();
      long one = host.budget.reserved(MemoryShare.RESULTS);
      search.togglePin(pinned);
      host.runAndWait(search, hits(dir));
      FolderResultSet older = search.shown();
      host.runAndWait(search, hits(dir));
      FolderResultSet newer = search.shown();
      long filler = host.budget.available(MemoryShare.RESULTS) - one / 2;
      assertTrue(host.budget.reserve(MemoryShare.RESULTS, filler));
      try {
        host.runAndWait(search, hits(dir));
        FolderResultSet latest = search.shown();
        assertFalse(latest.limited());
        assertEquals(3000, latest.files().get(0).size());
        assertEquals(List.of(latest, newer, pinned), search.sets());
        assertEquals(0, older.files().size());
        int dropped = 0;
        for (String notice : host.notices) {
          if (notice.equals(UiText.NOTICE_FOLDER_SET_DROPPED.text())) {
            dropped++;
          }
        }
        assertEquals(1, dropped);
      } finally {
        host.budget.release(MemoryShare.RESULTS, filler);
      }
    } finally {
      host.close(search);
    }
  }

  @Test
  void stepsGoThroughTheShownSetAcrossFilesAndWrapWithANotice() throws Exception {
    Path dir = logs();
    FakeHost host = new FakeHost(Preferences.DEFAULTS);
    FolderSearchController search = host.search();
    try {
      search.step(true);
      assertEquals(List.of(UiText.NOTICE_FOLDER_NO_LINES.text()), host.notices);
      host.runAndWait(search, hits(dir));
      List<FileHits> files = search.files();
      FileHits app = files.get(0);
      FileHits old = files.get(1);
      int[][] expected = {{0, 0}, {0, 1}, {0, 2}, {0, 3}, {1, 0}};
      for (int[] step : expected) {
        search.step(true);
        assertSame(files.get(step[0]), search.stepFile());
        assertEquals(step[1], search.stepHit());
      }
      assertEquals(List.of("app.log:5", "app.log.1:2"), host.opened);
      search.step(true);
      assertSame(app, search.stepFile());
      assertEquals(0, search.stepHit());
      assertEquals(UiText.NOTICE_FOLDER_WRAPPED_FIRST.text(), host.notices.get(1));
      search.step(false);
      assertSame(old, search.stepFile());
      assertEquals(UiText.NOTICE_FOLDER_WRAPPED_LAST.text(), host.notices.get(2));
      assertEquals(3, host.notices.size());
      search.step(false);
      assertSame(app, search.stepFile());
      assertEquals(3, search.stepHit());
      search.stepFrom(old);
      search.step(false);
      assertSame(app, search.stepFile());
      assertEquals(3, search.stepHit());
    } finally {
      host.close(search);
    }
  }
}
