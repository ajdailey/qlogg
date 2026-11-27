package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.Session;
import io.github.ajdailey.qlogg.app.view.FolderRequest;
import io.github.ajdailey.qlogg.app.view.FolderSearchState;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.StatusText;
import io.github.ajdailey.qlogg.core.folder.FileHits;
import io.github.ajdailey.qlogg.core.folder.NamePattern;
import io.github.ajdailey.qlogg.core.model.LineBlock;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A rotated set in a window: open with {@code --rotated}, title, tooltip, status member and
 * boundaries, Include Rotated Files both ways, a roll-over that renumbers lines, session marks
 * across a rotation, Recently Closed, Search in Folder, and a rotated CSV set's repeated header.
 */
class RotatedSetTest {
  private static final long NOW = System.currentTimeMillis();
  private static final SearchOptions LITERAL =
      new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, false, false);

  @TempDir Path temp;

  private ControllerFixture fixture;

  @AfterEach
  void close() throws InterruptedException {
    if (fixture != null) {
      fixture.close();
    }
  }

  /** Writes a file modified {@code age} seconds ago. */
  private Path write(String name, String text, int age) throws Exception {
    Path file = Files.createDirectories(temp.resolve("logs")).resolve(name);
    Files.writeString(file, text, StandardCharsets.US_ASCII);
    Files.setLastModifiedTime(file, FileTime.fromMillis(NOW - age * 1000L));
    return file;
  }

  /** {@code app.log.2} (lines 0-2), {@code app.log.1} (3-4), {@code app.log} (5-8). */
  private Path series() throws Exception {
    write("app.log.2", "two a\ntwo b\ntwo c\n", 300);
    write("app.log.1", "one a\nhit one b\n", 200);
    return write("app.log", "zero a\nzero b\nzero c\nzero d\n", 0);
  }

  private DocumentController shown(final String title) throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController document = fixture.application.activeDocument();
            return document != null
                && document.state().title().equals(title)
                && !document.state().indexing();
          }
        });
    DocumentController document = fixture.application.activeDocument();
    document.viewportResized(10, 80);
    document.filtered().viewportResized(5, 80);
    return document;
  }

  private DocumentController openSet(Path file, String title) throws Exception {
    if (fixture == null) {
      fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    }
    fixture.application.open(List.of(new FileRequest(file, 0, true)));
    return shown(title);
  }

  /** Waits for the shown tab to be another controller than {@code old} and indexed. */
  private DocumentController replacement(final DocumentController old) throws Exception {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = fixture.application.activeDocument();
            return shown != null && shown != old && !shown.state().indexing();
          }
        });
    return fixture.application.activeDocument();
  }

  @Test
  void aSetShowsItsFilesInTheTitleTooltipStatusAndBoundaries() throws Exception {
    Path base = series();
    write("app.log.3.gz", "compressed", 400);
    DocumentController set = openSet(base, "app.log +2");
    assertEquals(9, set.document().index().lineCount());
    assertTrue(set.following(), "a set opens at the end");
    String[] tip = set.state().members().split("\n");
    assertEquals(4, tip.length);
    assertTrue(tip[0].startsWith("app.log.2  18 B  "), tip[0]);
    assertTrue(tip[2].startsWith("app.log  "), tip[2]);
    assertEquals("app.log.3.gz  (compressed, not shown)", tip[3]);
    assertTrue(fixture.view.notices.contains("app.log: 1 compressed files not shown"));
    assertTrue(StatusText.notes(set.state()).contains("1 compressed files not shown"));
    set.setFollowing(false);
    set.select(Selection.caretAt(new TextPosition(4, 0)));
    assertEquals("app.log.1:2", set.caretMember());
    assertFalse(set.startsGroup(0));
    assertTrue(set.startsGroup(3));
    assertFalse(set.startsGroup(4));
    assertTrue(set.startsGroup(5));
    assertTrue(new CommandStates(fixture.application).checked(CommandId.INCLUDE_ROTATED));
  }

  @Test
  void aPlainOpenOfTheActiveFileShowsTheSetsTab() throws Exception {
    Path base = series();
    openSet(base, "app.log +2");
    DocumentController set = fixture.application.activeDocument();
    Path other = write("other.txt", "other\n", 0);
    fixture.application.open(List.of(new FileRequest(base, 0), new FileRequest(other, 0)));
    shown("other.txt");
    assertEquals(2, fixture.application.documents().size());
    assertTrue(fixture.application.documents().contains(set));
  }

  @Test
  void includeRotatedFilesMovesMarksBothWays() throws Exception {
    Path base = series();
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    DocumentController plain = fixture.open(base);
    plain.document().marks().add(1);
    plain.setMarkComment(1, "mock note");
    plain.select(Selection.caretAt(new TextPosition(2, 0)));
    assertFalse(new CommandStates(fixture.application).checked(CommandId.INCLUDE_ROTATED));
    fixture.application.execute(CommandId.INCLUDE_ROTATED);
    final DocumentController set = replacement(plain);
    assertEquals("app.log +2", set.state().title());
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return set.marks().size() == 1 && set.selection().caret().line() == 7;
          }
        });
    assertEquals(List.of(6L), ControllerFixture.toList(set.marks()));
    assertEquals("mock note", set.markComment(6));
    set.document().marks().add(0);
    fixture.application.execute(CommandId.INCLUDE_ROTATED);
    final DocumentController alone = replacement(set);
    assertEquals("app.log", alone.state().title());
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return alone.marks().size() == 1;
          }
        });
    assertEquals(List.of(1L), ControllerFixture.toList(alone.marks()));
    assertEquals("mock note", alone.markComment(1));
  }

  @Test
  void aRollOverRenumbersLinesAndCarriesMarks() throws Exception {
    Path base = series();
    DocumentController set = openSet(base, "app.log +2");
    set.document().marks().add(1);
    set.document().marks().add(6);
    Files.delete(base.resolveSibling("app.log.2"));
    final DocumentController rolled = replacement(set);
    assertEquals("app.log +1", rolled.state().title());
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return rolled.marks().size() == 1;
          }
        });
    assertEquals(List.of(3L), ControllerFixture.toList(rolled.marks()));
    String notice = fixture.view.lastNotice();
    assertNotNull(notice);
    assertTrue(notice.contains("app.log.2 left the set"), notice);
    assertTrue(notice.contains("1 marks left the set"), notice);
  }

  @Test
  void sessionMarksFollowTheirFileAcrossARotation() throws Exception {
    Path base = series();
    DocumentController set = openSet(base, "app.log +2");
    set.document().marks().add(4);
    set.document().markComments().put(4, "mock note");
    set.document().marks().add(7);
    set.document().marks().add(1);
    SetSessions.Saved saved;
    assertTrue(set.document().enter());
    try {
      saved = SetSessions.save(set.document(), 100, 100);
    } finally {
      set.document().exit();
    }
    assertEquals(3, saved.members().size());
    fixture.application.execute(CommandId.CLOSE);
    fixture.runUntilIdle();
    // A rotation between the sessions: app.log.2 goes, every other file moves up one name.
    Files.delete(base.resolveSibling("app.log.2"));
    Files.move(base.resolveSibling("app.log.1"), base.resolveSibling("app.log.2"));
    Files.move(base, base.resolveSibling("app.log.1"));
    write("app.log", "new a\n", 0);
    Session.Tab tab =
        new Session.Tab(
            base.toString(), "", false, new long[0], List.of(), 0, 0, true, saved.members());
    fixture.application.restoreSession(new Session(List.of(tab), 0));
    final DocumentController restored = shown("app.log +2");
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return restored.marks().size() == 2
                && fixture.view.notices.contains(
                    "app.log +2: 1 marks not restored; their files left the set");
          }
        });
    assertEquals(List.of(1L, 4L), ControllerFixture.toList(restored.marks()));
    assertEquals("mock note", restored.markComment(1));
  }

  @Test
  void recentlyClosedReopensTheSet() throws Exception {
    Path base = series();
    openSet(base, "app.log +2");
    fixture.application.execute(CommandId.CLOSE);
    fixture.runUntilIdle();
    assertEquals(List.of(base.toString()), fixture.application.preferences().state().closedSets());
    fixture.application.execute(CommandId.REOPEN_CLOSED);
    shown("app.log +2");
    assertTrue(fixture.application.preferences().state().closedSets().isEmpty());
  }

  @Test
  void aFolderSearchLineInAMemberShowsTheSetTab() throws Exception {
    Path base = series();
    DocumentController set = openSet(base, "app.log +2");
    set.setFollowing(false);
    final FolderSearchController search = fixture.application.folderSearch();
    search.run(
        new FolderRequest(
            base.getParent().toString(), NamePattern.DEFAULT, false, "hit", LITERAL, ""));
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return search.state().phase() != FolderSearchState.Phase.RUNNING;
          }
        });
    FileHits hits = null;
    for (FileHits file : search.files()) {
      if (file.path().getFileName().toString().equals("app.log.1")) {
        hits = file;
      }
    }
    assertNotNull(hits, search.files().toString());
    assertEquals(1, hits.line(0));
    search.open(hits, 0);
    assertEquals(1, fixture.application.documents().size());
    assertEquals(4, set.selection().caret().line());
  }

  @Test
  void aRotatedCsvSetShowsTheRepeatedHeaderAndDoesNotJoinAcrossFiles() throws Exception {
    write("app.csv.1", "id,level,message\n1,INFO,\"open\n", 200);
    Path base = write("app.csv", "id,level,message\n2,WARN,plain\n", 0);
    final DocumentController set = openSet(base, "app.csv +1");
    set.setFollowing(false);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            LineBlock lines = set.lines();
            return lines.cellView()
                && lines.contains(3)
                && lines.text(3).contains("plain")
                && lines.header(2);
          }
        });
    LineBlock lines = set.lines();
    assertTrue(lines.header(0));
    assertEquals(1, lines.sourceLine(1));
    assertEquals(2, lines.sourceLine(2));
    assertEquals(3, lines.sourceLine(3), "the quoted LF does not take the next file's lines");
    assertTrue(lines.text(3).startsWith("2"), lines.text(3));
  }
}
