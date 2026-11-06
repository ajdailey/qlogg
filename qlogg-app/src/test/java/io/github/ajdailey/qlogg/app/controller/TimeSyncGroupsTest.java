package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.Session;
import io.github.ajdailey.qlogg.app.config.TimeSettings;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Time sync groups and Scroll All Tabs to This Time, driven through the application. */
class TimeSyncGroupsTest {
  private static final LocalDateTime BASE = LocalDateTime.of(2026, 3, 1, 5, 0, 0);
  private static final DateTimeFormatter LAYOUT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
  private static final long SECOND = 1_000_000_000L;

  @TempDir Path temp;

  private ControllerFixture fixture;
  private TimeSyncGroups groups;

  /** Opened after each test; holds the tasks of {@link #holdCpuPool}. */
  private final CountDownLatch cpu = new CountDownLatch(1);

  /** Counts a tab's reported moves. */
  private static final class Spy implements TimeController.SyncListener {
    private int moves;

    @Override
    public void moved(DocumentController source) {
      moves++;
    }

    @Override
    public void detached(DocumentController document) {
      // Counts moves only.
    }
  }

  @BeforeEach
  void start() {
    fixture =
        new ControllerFixture(
            temp,
            Preferences.DEFAULTS.withTime(
                new TimeSettings(ZoneOffset.UTC, ZoneOffset.UTC, List.of())));
    groups = fixture.application.windowSet().timeSync();
  }

  @AfterEach
  void close() throws InterruptedException {
    cpu.countDown();
    fixture.close();
  }

  /** Writes one entry per line, {@code step} seconds apart from {@link #BASE}. */
  private Path timed(String name, int count, int step) throws IOException {
    Path file = temp.resolve(name);
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.US_ASCII)) {
      for (int i = 0; i < count; i++) {
        out.write(LAYOUT.format(BASE.plusSeconds((long) i * step)) + " INFO entry " + i + "\n");
      }
    }
    return file;
  }

  private DocumentController open(String name) throws Exception {
    return fixture.open(timed(name, 200, 1));
  }

  private static long nanos(long seconds) {
    Instant instant = BASE.plusSeconds(seconds).toInstant(ZoneOffset.UTC);
    return instant.getEpochSecond() * SECOND;
  }

  private static void caret(DocumentController document, long line) {
    document.select(Selection.caretAt(new TextPosition(line, 0)));
  }

  private static long line(DocumentController document) {
    return document.selection().caret().line();
  }

  private void reaches(final DocumentController document, final long line)
      throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return line(document) == line
                && !document.time().running()
                && fixture.view.active.get() == 0;
          }
        });
  }

  private void searched(final DocumentController document) throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !document.time().running() && fixture.view.active.get() == 0;
          }
        });
  }

  /** Joins a tab with no group to another's and waits out the move, which starts after a read. */
  private void join(DocumentController tab, DocumentController other) throws InterruptedException {
    final int before = fixture.view.activations.get();
    groups.toggle(tab, other);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return fixture.view.activations.get() > before && fixture.view.active.get() == 0;
          }
        });
  }

  private void running(final DocumentController document) throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.time().running();
          }
        });
  }

  @Test
  void aMoveReachesTheOtherMembersThroughTheirOffsets() throws Exception {
    DocumentController source = open("a.log");
    DocumentController target = open("b.log");
    source.time().setOffset(5 * SECOND);
    target.time().setOffset(15 * SECOND);
    join(source, target);
    caret(source, 100);
    // T + offset(source) - offset(k): entry 100 shows as 105, which is entry 90 of the target.
    reaches(target, 90);
    assertEquals(100, line(source));
  }

  @Test
  void aSyncMoveReportsNoMove() throws Exception {
    DocumentController tab = open("a.log");
    Spy spy = new Spy();
    tab.time().setSyncListener(spy);
    tab.time().syncTo(nanos(40));
    reaches(tab, 40);
    assertEquals(0, spy.moves);
    caret(tab, 41);
    assertEquals(1, spy.moves);
    // The same line again is no move; a click only changes the column.
    tab.select(Selection.caretAt(new TextPosition(41, 3)));
    assertEquals(1, spy.moves);
  }

  @Test
  void aMoveByTheUserCountsAndAKeptOrRestoredPositionDoesNot() throws Exception {
    DocumentController tab = open("a.log");
    Spy spy = new Spy();
    tab.time().setSyncListener(spy);
    tab.keepLine(11);
    tab.restorePosition(0, 20, 2);
    assertEquals(20, line(tab));
    assertEquals(0, spy.moves);
    tab.goToLine(31);
    assertEquals(1, spy.moves);
    tab.time().goToTime(nanos(50));
    reaches(tab, 50);
    assertEquals(2, spy.moves);
  }

  @Test
  void followScrollingReportsNoMove() throws Exception {
    DocumentController tab = open("a.log");
    Spy spy = new Spy();
    tab.time().setSyncListener(spy);
    tab.setFollowing(true);
    tab.setFollowing(false);
    assertEquals(0, spy.moves);
  }

  @Test
  void followGrowthOfAMemberStartsNoSync() throws Exception {
    DocumentController other = open("a.log");
    final DocumentController followed = open("b.log");
    join(followed, other);
    caret(followed, 50);
    reaches(other, 50);
    int before = fixture.view.activations.get();
    followed.setFollowing(true);
    StringBuilder text = new StringBuilder();
    for (int i = 200; i < 260; i++) {
      text.append(LAYOUT.format(BASE.plusSeconds(i))).append(" INFO entry ").append(i).append('\n');
    }
    Files.writeString(
        temp.resolve("b.log"), text, StandardCharsets.US_ASCII, StandardOpenOption.APPEND);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return followed.viewport().lineCount() == 260
                && followed.viewport().topLine() == followed.viewport().maxTop();
          }
        });
    fixture.runUntilIdle();
    assertEquals(before, fixture.view.activations.get());
    assertFalse(other.time().running());
    assertEquals(50, line(other));
    assertEquals(50, line(followed));
    assertTrue(followed.following());
    // The group still syncs: the growth left it intact.
    caret(followed, 120);
    reaches(other, 120);
  }

  /** Opens a file and returns before the first flush, while the view shows no lines. */
  private DocumentController opening(String name) throws Exception {
    fixture.application.open(List.of(new FileRequest(timed(name, 200, 1), 0)));
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return fixture.application.activeDocument() != null;
          }
        });
    DocumentController tab = fixture.application.activeDocument();
    tab.viewportResized(10, 80);
    return tab;
  }

  /** Occupies the CPU pool, so indexing cannot reach a line until {@link #cpu} opens. */
  private void holdCpuPool() {
    for (int i = 0; i < fixture.workers.cpuThreads(); i++) {
      fixture.workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              try {
                cpu.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
          });
    }
  }

  private void indexed(final DocumentController tab) throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return tab.pendingLine() < 0 && !tab.state().indexing();
          }
        });
  }

  @Test
  void aKeptLineThatWaitsForIndexingReportsNoMove() throws Exception {
    holdCpuPool();
    DocumentController tab = opening("a.log");
    Spy spy = new Spy();
    tab.time().setSyncListener(spy);
    tab.keepLine(11);
    assertEquals(10, tab.pendingLine());
    cpu.countDown();
    indexed(tab);
    assertEquals(10, line(tab));
    assertEquals(0, spy.moves);
    tab.goToLine(31);
    assertEquals(1, spy.moves);
  }

  @Test
  void aGoToLineThatWaitsForIndexingReportsItsMove() throws Exception {
    holdCpuPool();
    DocumentController tab = opening("a.log");
    Spy spy = new Spy();
    tab.time().setSyncListener(spy);
    tab.goToLine(11);
    assertEquals(10, tab.pendingLine());
    cpu.countDown();
    indexed(tab);
    assertEquals(10, line(tab));
    assertEquals(1, spy.moves);
  }

  @Test
  void movesWithinTheDebounceRunOneSync() throws Exception {
    DocumentController source = open("a.log");
    DocumentController target = open("b.log");
    join(source, target);
    int before = fixture.view.activations.get();
    caret(source, 10);
    caret(source, 20);
    caret(source, 30);
    reaches(target, 30);
    assertEquals(before + 1, fixture.view.activations.get());
  }

  @Test
  void aNewerMoveCancelsTheTargetsSearchWithoutANotice() throws Exception {
    DocumentController source = open("a.log");
    DocumentController target = open("b.log");
    join(source, target);
    caret(source, 10);
    running(target);
    // Its result can post but not apply: only this thread drains the queue.
    caret(source, 60);
    reaches(target, 60);
    assertFalse(fixture.view.notices.contains(UiText.NOTICE_TIME_CANCELED.text()));
  }

  @Test
  void aClickOnTheFirstLineOfAFreshTabReportsNoMove() throws Exception {
    DocumentController tab = open("a.log");
    Spy spy = new Spy();
    tab.time().setSyncListener(spy);
    caret(tab, 0);
    assertEquals(0, spy.moves);
    caret(tab, 1);
    assertEquals(1, spy.moves);
  }

  @Test
  void leavingEndsTheSyncMovesOfTheTabAndOfTheLastMember() throws Exception {
    DocumentController source = open("a.log");
    DocumentController target = open("b.log");
    join(source, target);
    // Their results can post but not apply: only this thread drains the queue.
    target.time().syncTo(nanos(40));
    source.time().syncTo(nanos(50));
    groups.leave(target);
    searched(target);
    searched(source);
    assertEquals(0, line(target));
    assertEquals(0, line(source));
  }

  @Test
  void aMoveOfTheTargetEndsItsSyncMove() throws Exception {
    DocumentController target = open("b.log");
    target.time().syncTo(nanos(40));
    caret(target, 7);
    searched(target);
    assertEquals(7, line(target));
  }

  @Test
  void escCancelsASyncMoveAndLeavesLoadingForTheSecondPress() throws Exception {
    DocumentController target = open("b.log");
    target.time().syncTo(nanos(40));
    target.execute(CommandId.STOP);
    searched(target);
    assertEquals(0, line(target));
    assertEquals(UiText.NOTICE_TIME_CANCELED.text(), fixture.view.lastNotice());
    assertFalse(target.state().loadingStopped());
  }

  @Test
  void aSyncMoveReleasesFollowAndShowsNoPositionNotice() throws Exception {
    DocumentController source = open("a.log");
    DocumentController target = fixture.open(timed("late.log", 20, 1));
    target.time().setOffset(100 * SECOND);
    join(source, target);
    caret(target, 5);
    fixture.runUntilIdle();
    target.setFollowing(true);
    int notices = fixture.view.notices.size();
    caret(source, 2);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !target.following() && !target.time().running();
          }
        });
    assertEquals(0, line(target));
    assertEquals(notices, fixture.view.notices.size());
  }

  @Test
  void aHiddenTabMovesInTheBackground() throws Exception {
    DocumentController hidden = open("a.log");
    DocumentController shown = open("b.log");
    assertEquals(shown, fixture.application.activeDocument());
    join(shown, hidden);
    caret(shown, 150);
    reaches(hidden, 150);
    long top = hidden.viewport().topLine();
    assertTrue(top <= 150 && 150 < top + hidden.viewport().rows());
    assertEquals(shown, fixture.application.activeDocument());
  }

  @Test
  void aFilteredRowShownInTheMainViewReachesTheGroup() throws Exception {
    DocumentController source = open("a.log");
    DocumentController target = open("b.log");
    join(source, target);
    source.search().run("entry 7", source.search().options());
    fixture.runUntilEnded(source.search());
    long row = source.filtered().rowOf(70);
    source.filtered().select(Selection.caretAt(new TextPosition(row, 0)));
    reaches(target, 70);
  }

  @Test
  void aGoToTimeOfAMemberReachesTheGroup() throws Exception {
    DocumentController source = open("a.log");
    DocumentController target = open("b.log");
    join(source, target);
    source.time().goToTime(nanos(77));
    reaches(target, 77);
  }

  @Test
  void joiningFollowsTheCascadesRules() throws Exception {
    DocumentController one = open("a.log");
    DocumentController two = open("b.log");
    DocumentController three = open("c.log");
    DocumentController four = open("d.log");
    caret(one, 11);
    caret(two, 22);
    caret(three, 33);
    caret(four, 44);
    // A tab outside any group moves to this tab's time.
    groups.toggle(one, two);
    reaches(two, 11);
    assertEquals(1, groups.number(one));
    assertEquals(1, groups.number(two));
    groups.toggle(three, four);
    reaches(four, 33);
    assertEquals(2, groups.number(three));
    // The cascade checks this tab's group and numbers another group's tabs.
    List<TimeSyncGroups.Entry> entries = groups.entries(one);
    assertEquals(3, entries.size());
    assertTrue(entries.get(0).checked());
    assertEquals("b.log", entries.get(0).label());
    assertFalse(entries.get(1).checked());
    assertEquals(UiText.TIME_SYNC_OTHER_GROUP.format("c.log", 2), entries.get(1).label());
    // Joining a group moves this tab to the group's time and dissolves the group of one left.
    groups.toggle(two, three);
    reaches(two, 33);
    assertEquals(0, groups.number(one));
    assertEquals(2, groups.number(two));
    // Unchecking a tab takes it out.
    groups.toggle(three, four);
    assertEquals(0, groups.number(four));
    assertEquals(2, groups.number(three));
    groups.leave(three);
    assertEquals(0, groups.number(two));
    assertEquals(0, groups.number(three));
  }

  @Test
  void aTabWithoutAFormatHasNoCascadeEntriesAndIsNotListed() throws Exception {
    DocumentController plain = fixture.open(fixture.lines("plain.log", 20));
    DocumentController timed = open("a.log");
    assertTrue(groups.entries(plain).isEmpty());
    assertTrue(groups.entries(timed).isEmpty());
  }

  @Test
  void closingDissolvesAGroupOfOneAndKeepsALargerOne() throws Exception {
    DocumentController one = open("a.log");
    DocumentController two = open("b.log");
    DocumentController three = open("c.log");
    join(one, two);
    join(one, three);
    fixture.application.closeDocument(three);
    assertEquals(1, groups.number(one));
    assertEquals(1, groups.number(two));
    fixture.application.closeDocument(two);
    assertEquals(0, groups.number(one));
    assertEquals(0, groups.number(two));
  }

  @Test
  void aReloadKeepsTheMembershipAndStartsNoSync() throws Exception {
    final DocumentController source = open("a.log");
    final DocumentController reloaded = open("b.log");
    join(source, reloaded);
    caret(source, 50);
    reaches(reloaded, 50);
    fixture.application.reload(reloaded);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return reloaded.isClosed()
                && !fixture.application.documents().get(1).state().indexing();
          }
        });
    final DocumentController replacement = fixture.application.documents().get(1);
    assertNotEquals(reloaded, replacement);
    assertEquals(0, groups.number(reloaded));
    assertEquals(1, groups.number(source));
    assertEquals(1, groups.number(replacement));
    caret(source, 60);
    reaches(replacement, 60);
    caret(replacement, 80);
    reaches(source, 80);
  }

  @Test
  void aSessionRenumbersFromOneAndARestoredGroupOfOneDissolves() throws Exception {
    DocumentController one = open("a.log");
    DocumentController two = open("b.log");
    DocumentController three = open("c.log");
    DocumentController four = open("d.log");
    join(one, two);
    join(three, four);
    groups.leave(one);
    assertEquals(2, groups.number(three));
    assertEquals(1, groups.sessionNumber(three));
    assertEquals(1, fixture.application.draft().tabs().get(3).syncGroup());
    assertEquals(0, fixture.application.draft().tabs().get(1).syncGroup());
    fixture.application.closeAll();

    final List<Session.Tab> tabs = new ArrayList<Session.Tab>();
    int[] numbers = {5, 5, 7, 5};
    for (int i = 0; i < numbers.length; i++) {
      String path = temp.resolve("restored-" + i + ".log").toString();
      timed("restored-" + i + ".log", 20, 1);
      tabs.add(new Session.Tab(path, "", false, new long[0], List.of(), 0, numbers[i]));
    }
    fixture.application.restoreSession(new Session(tabs, 0));
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return fixture.application.documents().size() == tabs.size();
          }
        });
    List<DocumentController> restored = fixture.application.documents();
    assertEquals(1, groups.number(restored.get(0)));
    assertEquals(1, groups.number(restored.get(1)));
    assertEquals(0, groups.number(restored.get(2)));
    assertEquals(1, groups.number(restored.get(3)));
    // The waiting tab is out of the way: a later group forms without it.
    groups.toggle(restored.get(2), restored.get(3));
    fixture.application.closeDocument(restored.get(2));
    assertEquals(1, groups.number(restored.get(0)));
  }

  @Test
  void scrollAllTabsGoesToTheCaretTimeInEveryOtherTab() throws Exception {
    DocumentController late = open("a.log");
    DocumentController plain = fixture.open(fixture.lines("plain.log", 200));
    DocumentController source = open("c.log");
    late.time().setOffset(10 * SECOND);
    late.setFollowing(true);
    caret(plain, 3);
    caret(source, 90);
    fixture.application.execute(CommandId.SCROLL_TABS_TO_TIME);
    reaches(late, 80);
    assertFalse(late.following());
    assertEquals(90, line(source));
    assertEquals(3, line(plain));
    assertEquals(0, groups.number(source));
  }

  @Test
  void scrollAllTabsNeedsAFormatAndACaretTime() throws Exception {
    Path file = temp.resolve("head.log");
    Files.writeString(
        file,
        "starting up\n"
            + LAYOUT.format(BASE)
            + " one\n"
            + LAYOUT.format(BASE.plusSeconds(1))
            + " two\n"
            + LAYOUT.format(BASE.plusSeconds(2))
            + " three\n",
        StandardCharsets.US_ASCII);
    final DocumentController document = fixture.open(file);
    fixture.application.execute(CommandId.SCROLL_TABS_TO_TIME);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return UiText.NOTICE_TIME_NO_CARET.text().equals(fixture.view.lastNotice());
          }
        });
    CommandStates states = new CommandStates(fixture.application);
    assertTrue(states.enabled(CommandId.SCROLL_TABS_TO_TIME));
    fixture.open(fixture.lines("plain.log", 20));
    assertFalse(states.enabled(CommandId.SCROLL_TABS_TO_TIME));
    fixture.application.execute(CommandId.SCROLL_TABS_TO_TIME);
    assertEquals(UiText.NOTICE_TIME_NO_FORMAT.text(), fixture.view.lastNotice());
    assertEquals(0, line(document));
  }
}
