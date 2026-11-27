package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
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
 * Filter tabs: rows in root lines, growth along a chain, marks, closing, deriving again after a
 * reload or a change on disk, and Locate Source Line.
 */
class FilterTabTest {
  private static final SearchOptions LITERAL =
      new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, true, false);

  @TempDir Path temp;

  private ControllerFixture fixture;

  @AfterEach
  void close() throws InterruptedException {
    if (fixture != null) {
      fixture.close();
    }
  }

  private DocumentController open(int lines) throws Exception {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    return fixture.open(fixture.lines("tab.log", lines));
  }

  private DerivedTabs tabs() {
    return fixture.application.windowSet().derivedTabs();
  }

  /** Runs a search in a tab, makes a filter tab of it and waits for its rows. */
  private DocumentController derive(final DocumentController parent, String text) throws Exception {
    parent.search().run(text, LITERAL);
    fixture.runUntilEnded(parent.search());
    final List<Long> matches = ControllerFixture.toList(parent.search().results());
    fixture.application.filterToTab(parent);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = fixture.application.activeDocument();
            return shown != parent
                && !shown.ownsDocument()
                && ControllerFixture.rows(shown).equals(matches);
          }
        });
    DocumentController tab = fixture.application.activeDocument();
    tab.viewportResized(10, 80);
    return tab;
  }

  /** Lines of the file holding every term, the last line included when it has no line feed. */
  private List<Long> matching(String... terms) throws Exception {
    String text = Files.readString(temp.resolve("tab.log"), StandardCharsets.US_ASCII);
    String[] lines = text.split("\n", -1);
    int count = text.endsWith("\n") ? lines.length - 1 : lines.length;
    List<Long> found = new ArrayList<Long>();
    for (int i = 0; i < count; i++) {
      boolean all = true;
      for (String term : terms) {
        all &= lines[i].contains(term);
      }
      if (all) {
        found.add((long) i);
      }
    }
    return found;
  }

  private void waitForRows(final DocumentController tab, final List<Long> rows) throws Exception {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return ControllerFixture.rows(tab).equals(rows);
          }
        });
  }

  private void append(String text) throws Exception {
    Files.writeString(
        temp.resolve("tab.log"), text, StandardCharsets.US_ASCII, StandardOpenOption.APPEND);
  }

  /** Waits for a tab to be replaced in the window and returns its replacement. */
  private DocumentController replacement(final int index, final DocumentController old)
      throws Exception {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            List<DocumentController> open = fixture.application.documents();
            return open.size() > index && open.get(index) != old && old.isClosed();
          }
        });
    return fixture.application.documents().get(index);
  }

  @Test
  void rowsAreTheMatchingRootLines() throws Exception {
    DocumentController root = open(100);
    DocumentController tab = derive(root, "hit");
    assertFalse(tab.ownsDocument());
    assertSame(root.document(), tab.document());
    assertEquals(17, tab.sourceLine(2));
    assertEquals(-1, tab.sourceLine(tab.viewport().lineCount()));
    assertEquals(0, tab.rowOf(3));
    assertEquals(1, tab.rowOf(4));
    assertEquals(4, tab.endOfRow(0));
    tab.selectRows(1, 1);
    tab.toggleFold();
    assertFalse(tab.folded(10));
    tab.search().run("1", LITERAL);
    fixture.runUntilEnded(tab.search());
    assertEquals(matching("hit", "1"), ControllerFixture.toList(tab.search().results()));
  }

  @Test
  void aFilterTabNeedsASearchThatRanToItsEnd() throws Exception {
    DocumentController root = open(20);
    fixture.application.filterToTab(root);
    assertEquals(1, fixture.application.documents().size());
    assertTrue(fixture.view.notices.contains(UiText.NOTICE_FILTER_TAB_NOT_DONE.text()));
  }

  @Test
  void markJumpsStepOverMarksThatAreNotRows() throws Exception {
    DocumentController root = open(100);
    DocumentController tab = derive(root, "hit");
    MutableLineSet marks = root.document().marks();
    for (long line : new long[] {5, 17, 20, 21, 31, 90}) {
      marks.add(line);
    }
    tab.select(Selection.caretAt(new TextPosition(0, 0)));
    tab.execute(CommandId.NEXT_MARK);
    assertEquals(17, tab.sourceLine(tab.selection().caret().line()));
    tab.execute(CommandId.NEXT_MARK);
    assertEquals(31, tab.sourceLine(tab.selection().caret().line()));
    tab.execute(CommandId.NEXT_MARK);
    assertEquals(31, tab.sourceLine(tab.selection().caret().line()));
    assertTrue(fixture.view.notices.contains(UiText.NOTICE_NO_NEXT_MARK.text()));
    tab.execute(CommandId.PREVIOUS_MARK);
    assertEquals(17, tab.sourceLine(tab.selection().caret().line()));
    MarksListController.Snapshot listed =
        MarksListController.read(
            tab.document(), tab.rowMap().members(), fixture.budget, new CancellationToken());
    assertEquals(2, listed.total());
    assertEquals(17, listed.rows().get(0).line());
    assertEquals(31, listed.rows().get(1).line());
    fixture.budget.release(MemoryShare.RESULTS, listed.charged());
  }

  @Test
  void everyLevelGrowsWithTheFileAndRetestsACompletedLastLine() throws Exception {
    DocumentController root = open(100);
    DocumentController first = derive(root, "hit");
    DocumentController second = derive(first, "5");
    StringBuilder more = new StringBuilder();
    for (int i = 100; i < 300; i++) {
      more.append(i % 7 == 3 ? "hit " : "line ").append(i).append('\n');
    }
    append(more + "hit 3");
    waitForRows(first, matching("hit"));
    waitForRows(second, matching("hit", "5"));
    append("05\n");
    waitForRows(first, matching("hit"));
    waitForRows(second, matching("hit", "5"));
    assertTrue(matching("hit", "5").contains(300L));
  }

  @Test
  void closingAMiddleTabReparentsItsChildAndClosingTheRootClosesEveryLevel() throws Exception {
    DocumentController root = open(100);
    DocumentController first = derive(root, "hit");
    DocumentController second = derive(first, "5");
    fixture.application.closeDocument(first);
    assertTrue(first.isClosed());
    assertSame(root, tabs().derivation(second).parent());
    append("hit 105\nline 106\n");
    waitForRows(second, matching("hit", "5"));
    fixture.application.closeDocument(root);
    assertTrue(second.isClosed());
    assertEquals(List.of(), fixture.application.documents());
  }

  @Test
  void aTruncatedFileDerivesEveryLevelAgainWithOneNotice() throws Exception {
    DocumentController root = open(100);
    DocumentController first = derive(root, "hit");
    DocumentController second = derive(first, "5");
    int notices = fixture.view.notices.size();
    StringBuilder shorter = new StringBuilder();
    for (int i = 0; i < 60; i++) {
      shorter.append(i % 5 == 0 ? "hit " : "line ").append(i).append('\n');
    }
    Files.writeString(
        temp.resolve("tab.log"),
        shorter.toString(),
        StandardCharsets.US_ASCII,
        StandardOpenOption.TRUNCATE_EXISTING);
    DocumentController newRoot = replacement(0, root);
    DocumentController newFirst = replacement(1, first);
    DocumentController newSecond = replacement(2, second);
    waitForRows(newFirst, matching("hit"));
    waitForRows(newSecond, matching("hit", "5"));
    assertSame(newRoot, tabs().derivation(newFirst).parent());
    assertSame(newFirst, tabs().derivation(newSecond).parent());
    assertSame(newRoot.document(), newSecond.document());
    assertEquals(notices + 1, fixture.view.notices.size());
  }

  @Test
  void aReloadAndAReplacedFileDeriveAgain() throws Exception {
    Assumptions.assumeTrue(File.separatorChar == '/', "symbolic links need privileges on Windows");
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    Path target = fixture.lines("tab-1.log", 100);
    Path link = Files.createSymbolicLink(temp.resolve("tab.log"), target);
    DocumentController root = fixture.open(link);
    DocumentController first = derive(root, "hit");
    fixture.application.reload(first);
    DocumentController reloaded = replacement(0, root);
    DocumentController again = replacement(1, first);
    waitForRows(again, matching("hit"));
    assertSame(reloaded, tabs().derivation(again).parent());
    Path second = Files.writeString(temp.resolve("tab-2.log"), "hit a\nline b\nhit c\n");
    Path next = Files.createSymbolicLink(temp.resolve("tab.log.next"), second);
    Files.move(next, link, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    replacement(0, reloaded);
    waitForRows(replacement(1, again), List.of(0L, 2L));
  }

  @Test
  void locateSourceLineShowsTheImmediateParentInItsWindow() throws Exception {
    DocumentController root = open(100);
    DocumentController first = derive(root, "hit");
    final DocumentController second = derive(first, "5");
    final List<FakeView> views = new ArrayList<FakeView>();
    WindowSet set = fixture.application.windowSet();
    set.setFactory(
        new WindowSet.Factory() {
          @Override
          public ApplicationController create(
              WindowSet owner, WindowGeometry.Request at, boolean maximized) {
            FakeView view = new FakeView();
            views.add(view);
            return new ApplicationController(owner, view);
          }
        });
    fixture.application.moveToNewWindow(first, null, null);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return set.windows().size() == 2 && !fixture.application.documents().contains(first);
          }
        });
    ApplicationController other = set.windows().get(1);
    fixture.application.activate(second);
    second.selectRows(1, 1);
    long line = second.sourceLine(1);
    int raised = views.get(0).raised;
    assertTrue(fixture.application.locateSource(second));
    assertSame(first, other.activeDocument());
    assertEquals(line, first.sourceLine(first.selection().caret().line()));
    assertTrue(first.canGoBack());
    assertEquals(raised + 1, views.get(0).raised);
    assertTrue(other.locateSource(first));
    assertSame(root, fixture.application.activeDocument());
    assertEquals(line, root.sourceLine(root.selection().caret().line()));
    assertFalse(fixture.application.locateSource(root));
  }
}
