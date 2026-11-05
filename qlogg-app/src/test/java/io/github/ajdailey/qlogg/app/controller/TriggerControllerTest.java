package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.highlight.HighlightSettings;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.MarkComments;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.model.RuleTrigger;
import io.github.ajdailey.qlogg.core.model.Triggers;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Keyword triggers on lines appended to open documents: the file monitor runs for real and the UI
 * queue is drained by the test.
 */
class TriggerControllerTest {
  private static final SearchOptions AUTO_REFRESH =
      new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, true, false);
  private static final RuleTrigger STOP = new RuleTrigger(true, false, "", false);
  private static final RuleTrigger QUIET = new RuleTrigger(false, false, "", true);

  @TempDir Path temp;

  private ControllerFixture fixture;

  @AfterEach
  void close() throws InterruptedException {
    if (fixture != null) {
      fixture.close();
    }
  }

  private static RuleTrigger mark(String template) {
    return new RuleTrigger(false, true, template, false);
  }

  private static HighlightRule rule(String pattern, RuleTrigger trigger) {
    return HighlightRule.matchOnly(pattern, MatchMode.LITERAL, null, Rgb.of(0xffe680))
        .withTrigger(trigger);
  }

  /** Starts the fixture with active sets, each a list of rules. */
  private void start(List<List<HighlightRule>> sets) {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    List<HighlightSet> made = new ArrayList<HighlightSet>();
    List<String> ids = new ArrayList<String>();
    for (int i = 0; i < sets.size(); i++) {
      made.add(new HighlightSet("set-" + i, "Set " + i, sets.get(i)));
      ids.add("set-" + i);
    }
    HighlightSettings settings =
        fixture.application.highlightSettings().withSets(made).withActive(ids);
    fixture.application.setHighlightSettings(settings);
  }

  private DocumentController open(String name, String text) throws Exception {
    Path file = temp.resolve(name);
    Files.writeString(file, text, StandardCharsets.US_ASCII);
    return fixture.open(file);
  }

  private void append(String name, String text) throws Exception {
    Files.writeString(
        temp.resolve(name), text, StandardCharsets.US_ASCII, StandardOpenOption.APPEND);
  }

  private void awaitScanned(final DocumentController document, final long line)
      throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.triggers().scannedTo() == line
                && !document.triggers().scanning()
                && fixture.view.active.get() == 0;
          }
        });
  }

  private void awaitResults(final DocumentController document, final long count)
      throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.search().resultCount() == count
                && document.search().state().phase() != SearchViewState.Phase.RUNNING;
          }
        });
  }

  private DocumentController replacementOf(final DocumentController old)
      throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController first = fixture.application.documents().get(0);
            return first != old && !first.state().indexing() && first.triggers().scannedTo() >= 0;
          }
        });
    return fixture.application.documents().get(0);
  }

  @Test
  void stopFollowTurnsFollowOffAndNamesTheRuleForTheShownTab() throws Exception {
    start(List.of(List.of(rule("FATAL", STOP))));
    DocumentController document = open("app.log", "FATAL at open\nline 1\n");
    document.setFollowing(true);
    append("app.log", "line 2\n");
    awaitScanned(document, 3);
    assertTrue(document.following(), "a line no rule matches keeps follow");
    append("app.log", "FATAL 3\n");
    awaitScanned(document, 4);
    assertFalse(document.following());
    assertEquals("Follow stopped by highlighter FATAL at line 4", fixture.view.lastNotice());
    assertEquals(TabDataState.NONE, fixture.application.tabData().state(document));
  }

  @Test
  void stopFollowOnABackgroundTabOutranksNewMatches() throws Exception {
    start(List.of(List.of(rule("FATAL", STOP))));
    DocumentController background = open("a.log", "hit 0\n");
    background.setFollowing(true);
    background.search().run("hit", AUTO_REFRESH);
    fixture.runUntilEnded(background.search());
    open("b.log", "b\n");
    append("a.log", "hit 1\n");
    final TabDataModel model = fixture.application.tabData();
    final DocumentController watched = background;
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return model.state(watched) == TabDataState.NEW_MATCHES;
          }
        });
    append("a.log", "FATAL hit 2\n");
    awaitScanned(background, 3);
    assertFalse(background.following());
    assertEquals(TabDataState.FOLLOW_STOPPED, model.state(background));
    append("a.log", "hit 3\n");
    awaitScanned(background, 4);
    awaitResults(background, 4);
    assertEquals(TabDataState.FOLLOW_STOPPED, model.state(background), "nothing outranks it");
    assertTrue(TabDataState.FOLLOW_STOPPED.outranks(TabDataState.NEW_MATCHES));
  }

  @Test
  void setMarkExpandsTheTemplateOnAppendedLinesOnly() throws Exception {
    start(List.of(List.of(rule("ERROR", mark("%N:%L {.*code=(\\d+).*}{$1}")))));
    DocumentController document = open("app.log", "ERROR at open code=1\nline 1\n");
    assertEquals(0, document.marks().size(), "the first index does not fire");
    append("app.log", "ERROR code=42 disk\nline 3\n");
    awaitScanned(document, 4);
    assertEquals(List.of(2L), ControllerFixture.toList(document.marks()));
    assertEquals("app.log:3 42", document.document().markComments().get(2));
  }

  @Test
  void aReloadOrAReopenDoesNotFire() throws Exception {
    start(List.of(List.of(rule("ERROR", mark("x")))));
    DocumentController document = open("app.log", "line 0\n");
    append("app.log", "ERROR 1\n");
    awaitScanned(document, 2);
    assertEquals(1, document.marks().size());
    fixture.application.reload(document);
    DocumentController reloaded = replacementOf(document);
    assertNotSame(document, reloaded);
    assertEquals(2, reloaded.triggers().scannedTo());
    assertEquals(List.of(1L), ControllerFixture.toList(reloaded.marks()), "copied, not fired");
    reloaded.removeMarks(new long[] {1});
    Files.writeString(temp.resolve("app.log"), "ERROR a\n", StandardCharsets.US_ASCII);
    DocumentController reopened = replacementOf(reloaded);
    assertEquals(1, reopened.triggers().scannedTo());
    assertEquals(0, reopened.marks().size(), "a truncation reopen does not fire");
  }

  @Test
  void rulesOfSeveralSetsFireTogetherWhateverTheirColorOrder() throws Exception {
    start(
        List.of(
            List.of(rule("disk", mark("disk %L")), rule("ERROR", QUIET)),
            List.of(rule("ERROR", STOP), rule("disk", mark("second")))));
    DocumentController document = open("app.log", "line 0\n");
    document.setFollowing(true);
    append("app.log", "ERROR disk 1\n");
    awaitScanned(document, 2);
    assertFalse(document.following());
    assertEquals("disk 2", document.document().markComments().get(1), "the top set's comment");
    assertTrue(document.triggers().isQuiet(1));
  }

  @Test
  void aGrowthOfQuietLinesLeavesTheDotUnchanged() throws Exception {
    start(List.of(List.of(rule("heartbeat", QUIET))));
    DocumentController background = open("a.log", "line 0\n");
    background.search().run("beat", AUTO_REFRESH);
    fixture.runUntilEnded(background.search());
    open("b.log", "b\n");
    TabDataModel model = fixture.application.tabData();
    append("a.log", "heartbeat 1\nheartbeat 2\n");
    awaitScanned(background, 3);
    awaitResults(background, 2);
    assertEquals(TabDataState.NONE, model.state(background), "quiet lines and quiet matches");
    append("a.log", "line 3\n");
    awaitScanned(background, 4);
    assertEquals(TabDataState.NEW_LINES, model.state(background));
    append("a.log", "beat 4\n");
    awaitScanned(background, 5);
    final DocumentController watched = background;
    final TabDataModel tabs = model;
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return tabs.state(watched) == TabDataState.NEW_MATCHES;
          }
        });
  }

  @Test
  void aLargeAppendFiresOncePerLineAcrossBatches() throws Exception {
    start(List.of(List.of(rule("hit", mark("")))));
    DocumentController document = open("big.log", "start\n");
    final List<long[]> ranges = new ArrayList<long[]>();
    document
        .triggers()
        .addListener(
            new TriggerListener() {
              @Override
              public void linesScanned(long from, long to, long loudLines) {
                ranges.add(new long[] {from, to});
              }
            });
    int count = 3 * Triggers.BATCH_LINES + 17;
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < count; i++) {
      text.append("hit ").append(i).append('\n');
    }
    append("big.log", text.toString());
    awaitScanned(document, count + 1);
    assertEquals(count, document.marks().size());
    long next = 1;
    for (long[] range : ranges) {
      assertEquals(next, range[0], "batches are contiguous and do not overlap");
      assertTrue(range[1] - range[0] <= Triggers.BATCH_LINES);
      next = range[1];
    }
    assertEquals(count + 1, next);
    assertTrue(ranges.size() > 3, "more than one batch: " + ranges.size());
  }

  @Test
  void aRefusedCommentShowsOneNoticePerGrowth() throws Exception {
    start(List.of(List.of(rule("ERROR", mark("c %L")))));
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < MarkComments.MAX_COMMENTS; i++) {
      text.append("line ").append(i).append('\n');
    }
    DocumentController document = open("app.log", text.toString());
    for (int i = 0; i < MarkComments.MAX_COMMENTS; i++) {
      document.setMarkComment(i, "mine");
    }
    int before = fixture.view.notices.size();
    append("app.log", "ERROR a\nERROR b\nERROR c\n");
    awaitScanned(document, MarkComments.MAX_COMMENTS + 3);
    List<String> added = fixture.view.notices.subList(before, fixture.view.notices.size());
    assertEquals(1, added.size(), added.toString());
    assertTrue(added.get(0).startsWith("app.log: highlighter marks or comments not kept"));
    assertTrue(document.marks().contains(MarkComments.MAX_COMMENTS));
    assertNull(document.document().markComments().get(MarkComments.MAX_COMMENTS));
  }

  @Test
  void growthBeforeARuleActsIsNotScannedLater() throws Exception {
    start(List.of(List.of(rule("ERROR", RuleTrigger.NONE))));
    DocumentController document = open("app.log", "line 0\n");
    append("app.log", "ERROR 1\n");
    awaitScanned(document, 2);
    start2(rule("ERROR", mark("")));
    append("app.log", "ERROR 2\n");
    awaitScanned(document, 3);
    assertEquals(List.of(2L), ControllerFixture.toList(document.marks()));
  }

  private void start2(HighlightRule replacement) {
    HighlightSettings settings = fixture.application.highlightSettings();
    fixture.application.setHighlightSettings(
        settings.withSets(List.of(settings.sets().get(0).withRules(List.of(replacement)))));
  }
}
