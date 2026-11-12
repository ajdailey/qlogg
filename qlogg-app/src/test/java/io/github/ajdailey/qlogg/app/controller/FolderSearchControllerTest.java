package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Search > Search in Folder: the request taken from the shown tab, the result model, opening a
 * listed line in a new tab or the tab that shows the file, the failures before the search thread,
 * Stop, and the results' reservation returned by a new run.
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
    FolderSearchSettings saved = new FolderSearchSettings(dir.toString(), "*.txt", true);
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      FolderSearchController search = fixture.application.folderSearch();
      search.run(new FolderRequest(dir.toString(), "*.txt", true, "hit", LITERAL));
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
      search.run(new FolderRequest(dir.toString(), NamePattern.DEFAULT, false, "hit", LITERAL));
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
      search.run(new FolderRequest(dir.toString(), NamePattern.DEFAULT, false, "", LITERAL));
      assertEquals(FolderSearchState.Phase.FAILED, search.state().phase());
      assertEquals(UiText.FOLDER_NO_TEXT.text(), search.state().message());
      search.run(new FolderRequest(" ", NamePattern.DEFAULT, false, "hit", LITERAL));
      assertEquals(UiText.FOLDER_NO_FOLDER.text(), search.state().message());
      search.run(new FolderRequest(dir.toString(), "[", false, "hit", LITERAL));
      assertTrue(
          search.state().message().startsWith(UiText.FOLDER_BAD_NAMES.format("")),
          search.state().message());
      search.run(
          new FolderRequest(
              dir.toString(), NamePattern.DEFAULT, false, "\"hit", LITERAL.withCombining(true)));
      assertTrue(
          search.state().message().startsWith(UiText.SEARCH_EXPRESSION_ERROR.text().split("%")[0]),
          search.state().message());
      search.run(
          new FolderRequest(
              dir.resolve("missing").toString(), NamePattern.DEFAULT, false, "hit", LITERAL));
      runUntilEnded(fixture, search);
      assertEquals(FolderSearchState.Phase.FAILED, search.state().phase());
      assertTrue(
          search.state().message().startsWith(UiText.FOLDER_FAILED.format("")),
          search.state().message());
    }
  }

  @Test
  void stopEndsTheRunAndANewRunReturnsTheLastResults() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      Path dir = logs();
      Files.writeString(dir.resolve("runaway.log"), "a".repeat(60) + "\n");
      final FolderSearchController search = fixture.application.folderSearch();
      search.run(new FolderRequest(dir.toString(), NamePattern.DEFAULT, false, "hit", LITERAL));
      runUntilEnded(fixture, search);
      assertTrue(fixture.budget.reserved(MemoryShare.RESULTS) > 0);
      search.clear();
      assertEquals(0, fixture.budget.reserved(MemoryShare.RESULTS));
      assertEquals(0, search.files().size());
      search.run(
          new FolderRequest(
              dir.toString(), "runaway.log", false, "(.*a){25}x", SearchOptions.DEFAULTS));
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
}
