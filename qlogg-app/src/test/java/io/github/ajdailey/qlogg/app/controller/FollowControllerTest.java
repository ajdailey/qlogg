package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.FollowSettings;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.testkit.Renames;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Follow, overscroll, growth, truncation, rotation, reload, stop loading and auto-refresh through
 * the controllers. The file monitor runs for real; the UI queue is drained by the test.
 */
class FollowControllerTest {
  private static final SearchOptions AUTO_REFRESH =
      new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, true, false);

  @TempDir Path temp;

  private ControllerFixture fixture;

  @AfterEach
  void close() throws InterruptedException {
    if (fixture != null) {
      fixture.close();
    }
  }

  private DocumentController open(Preferences preferences, int lines) throws Exception {
    fixture = new ControllerFixture(temp, preferences);
    return fixture.open(fixture.lines("follow.log", lines));
  }

  private void append(String text) throws Exception {
    Files.writeString(
        temp.resolve("follow.log"), text, StandardCharsets.US_ASCII, StandardOpenOption.APPEND);
  }

  private static String lines(int from, int to) {
    StringBuilder text = new StringBuilder();
    for (int i = from; i < to; i++) {
      text.append(i % 7 == 3 ? "hit " : "line ").append(i).append('\n');
    }
    return text.toString();
  }

  private BooleanSupplier lineCount(final DocumentController document, final long count) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return document.viewport().lineCount() == count && !document.state().indexing();
      }
    };
  }

  /** Waits for the shown tab to be another controller than {@code old}, then returns it. */
  private DocumentController replacement(final DocumentController old) throws Exception {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = fixture.application.activeDocument();
            return shown != null && shown != old && !shown.state().indexing();
          }
        });
    DocumentController shown = fixture.application.activeDocument();
    shown.viewportResized(10, 80);
    return shown;
  }

  @Test
  void followScrollsToAppendedLinesAndScrollingUpReleasesIt() throws Exception {
    DocumentController document = open(Preferences.DEFAULTS, 100);
    fixture.application.execute(CommandId.FOLLOW);
    assertTrue(document.following());
    assertTrue(document.state().following());
    append(lines(100, 150));
    fixture.runUntil(lineCount(document, 150));
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.viewport().topLine() == document.viewport().maxTop();
          }
        });
    document.scrollLines(-1);
    assertFalse(document.following());
    append(lines(150, 160));
    fixture.runUntil(lineCount(document, 160));
    assertEquals(139, document.viewport().topLine());
  }

  @Test
  void overscrollAtTheBottomEngagesFollowUnlessTurnedOff() throws Exception {
    DocumentController document = open(Preferences.DEFAULTS, 100);
    document.goToBottom();
    document.scrollLines(3);
    assertFalse(document.following());
    document.scrollLines(3);
    assertTrue(document.following());
    fixture.application.execute(CommandId.FOLLOW);
    fixture.application.execute(CommandId.FOLLOW_ON_OVERSCROLL);
    assertFalse(fixture.application.preferences().follow().onOverscroll());
    document.scrollLines(10);
    assertFalse(document.following());
  }

  @Test
  void followOnLoadAndTheCommandLineFollowEveryFile() throws Exception {
    DocumentController document =
        open(Preferences.DEFAULTS.withFollow(new FollowSettings(true, true, false)), 30);
    assertTrue(document.following());
    Path other = fixture.lines("other.log", 5);
    fixture.application.setFollowSettings(FollowSettings.DEFAULTS);
    fixture.application.open(List.of(new FileRequest(other, 0)), true);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = fixture.application.activeDocument();
            return shown != null && shown.state().title().equals("other.log");
          }
        });
    assertTrue(fixture.application.activeDocument().following());
  }

  @Test
  void autoRefreshSearchesAppendedLinesIncludingAGrownLastLine() throws Exception {
    DocumentController document = open(Preferences.DEFAULTS, 20);
    final SearchController search = document.search();
    search.run("hit", AUTO_REFRESH);
    fixture.runUntilEnded(search);
    assertEquals(List.of(3L, 10L, 17L), ControllerFixture.toList(search.results()));
    append("hi");
    fixture.runUntil(lineCount(document, 21));
    append("t 20\n" + lines(21, 30));
    fixture.runUntil(lineCount(document, 30));
    final List<Long> expected = new ArrayList<Long>(List.of(3L, 10L, 17L, 20L, 24L));
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return search.state().matchCount() == expected.size()
                && search.state().phase() == SearchViewState.Phase.DONE;
          }
        });
    assertEquals(expected, ControllerFixture.toList(search.results()));
  }

  @Test
  void truncationReopensTheFileWithEmptyResultsAndANotice() throws Exception {
    DocumentController document = open(Preferences.DEFAULTS, 50);
    document.search().run("hit", AUTO_REFRESH.withAutoRefresh(false));
    fixture.runUntilEnded(document.search());
    Files.writeString(
        temp.resolve("follow.log"), lines(0, 5), StandardOpenOption.TRUNCATE_EXISTING);
    DocumentController reopened = replacement(document);
    assertTrue(document.isClosed());
    fixture.runUntil(lineCount(reopened, 5));
    assertEquals(0, reopened.search().results().size());
    assertEquals("hit", reopened.search().fieldText());
    assertEquals(1, fixture.application.documents().size());
    // A check that straddles the write sees the old size and the new head, which reads replaced.
    assertTrue(
        fixture.view.notices.contains(
                "File truncated on disk: follow.log reloaded, results cleared.")
            || fixture.view.notices.contains(
                "File replaced on disk: follow.log reloaded, results cleared."),
        fixture.view.notices.toString());
  }

  @Test
  void aChangeOnDiskRunsAnAutoRefreshSearchAgain() throws Exception {
    DocumentController document = open(Preferences.DEFAULTS, 50);
    document.search().run("hit", AUTO_REFRESH);
    fixture.runUntilEnded(document.search());
    Files.writeString(
        temp.resolve("follow.log"), lines(0, 12), StandardOpenOption.TRUNCATE_EXISTING);
    final DocumentController reopened = replacement(document);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return reopened.search().results().size() == 2
                && reopened.search().state().phase() == SearchViewState.Phase.DONE;
          }
        });
    assertEquals(List.of(3L, 10L), ControllerFixture.toList(reopened.search().results()));
    assertEquals(
        List.of(UiText.NOTICE_FILE_SEARCH_RERUN.format("follow.log")),
        noticesAboutTheFile(),
        "no results-cleared notice with a rerun");
  }

  private List<String> noticesAboutTheFile() {
    List<String> found = new ArrayList<String>();
    for (String notice : fixture.view.notices) {
      if (notice.contains("on disk")) {
        found.add(notice);
      }
    }
    return found;
  }

  @Test
  void aReloadDoesNotRunAnAutoRefreshSearchAgain() throws Exception {
    DocumentController document = open(Preferences.DEFAULTS, 50);
    document.search().run("hit", AUTO_REFRESH);
    fixture.runUntilEnded(document.search());
    fixture.application.execute(CommandId.RELOAD);
    DocumentController reopened = replacement(document);
    fixture.runUntil(lineCount(reopened, 50));
    fixture.runUntilIdle();
    assertEquals(SearchViewState.Phase.IDLE, reopened.search().state().phase());
    assertEquals("hit", reopened.search().fieldText());
  }

  @Test
  void renameRotationFollowsTheName() throws Exception {
    DocumentController document = open(Preferences.DEFAULTS, 40);
    document.setFollowing(true);
    Files.move(temp.resolve("follow.log"), temp.resolve("follow.log.1"));
    Files.writeString(temp.resolve("follow.log"), lines(0, 3));
    DocumentController reopened = replacement(document);
    fixture.runUntil(lineCount(reopened, 3));
    assertTrue(reopened.following());
  }

  private static Preferences keptClosed() {
    return Preferences.DEFAULTS.withFollow(new FollowSettings(false, true, true));
  }

  /** A rename over the name is what Windows refuses while the default holds the file. */
  @Test
  void aRenameOverAKeptClosedFileReopensTheTab() throws Exception {
    DocumentController document = open(keptClosed(), 40);
    assertTrue(document.document().keepsFileClosed());
    document.setFollowing(true);
    Path next = Files.writeString(temp.resolve("follow.log.next"), lines(0, 3));
    Renames.replace(next, temp.resolve("follow.log"));
    DocumentController reopened = replacement(document);
    fixture.runUntil(lineCount(reopened, 3));
    assertTrue(reopened.document().keepsFileClosed());
    assertTrue(reopened.following());
  }

  @Test
  void aDeletedKeptClosedFileSaysItCannotBeReadAndReopensWhenRecreated() throws Exception {
    final DocumentController document = open(keptClosed(), 10);
    Files.delete(temp.resolve("follow.log"));
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.state().missing()
                && fixture.view.notices.contains(
                    "File missing on disk: follow.log cannot be read until it returns.");
          }
        });
    Files.writeString(temp.resolve("follow.log"), lines(0, 2));
    DocumentController reopened = replacement(document);
    fixture.runUntil(lineCount(reopened, 2));
  }

  @Test
  void keepFileClosedAppliesAtTheNextReloadAndSurvivesTheFollowMenuItems() throws Exception {
    DocumentController document = open(Preferences.DEFAULTS, 10);
    assertFalse(document.document().keepsFileClosed());
    fixture.application.setFollowSettings(new FollowSettings(false, true, true));
    assertFalse(document.document().keepsFileClosed());
    fixture.application.execute(CommandId.FOLLOW_ON_LOAD);
    fixture.application.execute(CommandId.FOLLOW_ON_OVERSCROLL);
    assertTrue(fixture.application.preferences().follow().keepClosed());
    fixture.application.execute(CommandId.RELOAD);
    assertTrue(replacement(document).document().keepsFileClosed());
  }

  @Test
  void aRetargetedSymbolicLinkReopensTheNewTargetUnderTheLinkName() throws Exception {
    Assumptions.assumeTrue(File.separatorChar == '/', "symbolic links need privileges on Windows");
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    Path first = fixture.lines("app-1.log", 40);
    Path second = Files.writeString(temp.resolve("app-2.log"), lines(0, 3));
    Path link = Files.createSymbolicLink(temp.resolve("app.log"), first);
    DocumentController document = fixture.open(link);
    document.setFollowing(true);
    Path next = Files.createSymbolicLink(temp.resolve("app.log.next"), second);
    Files.move(next, link, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    DocumentController reopened = replacement(document);
    fixture.runUntil(lineCount(reopened, 3));
    assertEquals("app.log", reopened.state().title());
    assertEquals(link.toAbsolutePath().normalize(), reopened.document().path());
    assertEquals(second.toRealPath(), reopened.document().realPath());
    assertTrue(reopened.following());
  }

  @Test
  void aDeletedFileKeepsItsContentAndReopensWhenRecreated() throws Exception {
    final DocumentController document = open(Preferences.DEFAULTS, 10);
    Files.delete(temp.resolve("follow.log"));
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.state().missing()
                && fixture.view.notices.contains(
                    "File missing on disk: follow.log shows its last content.");
          }
        });
    assertEquals(10, document.viewport().lineCount());
    Files.writeString(temp.resolve("follow.log"), lines(0, 2));
    DocumentController reopened = replacement(document);
    fixture.runUntil(lineCount(reopened, 2));
    assertFalse(reopened.state().missing());
  }

  @Test
  void reloadReindexesAndKeepsTheCaretLine() throws Exception {
    DocumentController document = open(Preferences.DEFAULTS, 60);
    document.goToLine(42);
    fixture.application.execute(CommandId.RELOAD);
    DocumentController reloaded = replacement(document);
    assertNotSame(document, reloaded);
    assertEquals(41, reloaded.selection().caret().line());
    assertTrue(fixture.view.notices.isEmpty(), fixture.view.notices.toString());
  }

  @Test
  void stopLoadingLeavesGrowthUnindexed() throws Exception {
    final DocumentController document = open(Preferences.DEFAULTS, 10);
    fixture.application.execute(CommandId.STOP);
    append(lines(10, 20));
    final long size = Files.size(temp.resolve("follow.log"));
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.state().fileBytes() == size;
          }
        });
    assertEquals(10, document.viewport().lineCount());
    assertTrue(document.state().loadingStopped());
  }
}
