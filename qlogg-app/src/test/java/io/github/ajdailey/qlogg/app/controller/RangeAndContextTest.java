package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Context lines in the filtered view and the range filter through the controllers: rows, shading,
 * groups, counts and navigation on matches only, kept sets, growth, auto-refresh of an open range
 * and Select Range.
 */
class RangeAndContextTest {
  private static final SearchOptions LITERAL =
      new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, false, false);

  @TempDir Path temp;

  private ControllerFixture fixture;

  @BeforeEach
  void create() {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
  }

  @AfterEach
  void close() throws InterruptedException {
    fixture.close();
  }

  /** Drives the queue until the search ended and the filtered rows are built. */
  private void settle(final DocumentController document) throws InterruptedException {
    fixture.runUntilEnded(document.search());
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.filtered().settled() && fixture.view.active.get() == 0;
          }
        });
  }

  private Path write(String name, List<String> lines) throws IOException {
    Path file = temp.resolve(name);
    Files.write(file, lines, StandardCharsets.US_ASCII);
    return file;
  }

  private void append(Path file, String... lines) throws IOException {
    StringBuilder text = new StringBuilder();
    for (String line : lines) {
      text.append(line).append('\n');
    }
    Files.writeString(file, text, StandardOpenOption.APPEND);
  }

  private void waitForLines(final DocumentController document, final long count)
      throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.document().index().lineCount() == count
                && document.viewport().lineCount() == count;
          }
        });
  }

  /** Drives the queue until an auto-refresh reached the results; a refresh stays DONE. */
  private void waitForResults(final SearchController search, final List<Long> expected)
      throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !search.refreshing()
                && expected.equals(ControllerFixture.toList(search.results()));
          }
        });
  }

  @Test
  void contextRowsSurroundMatchesWhileCountsAndNavigationUseMatches() throws Exception {
    DocumentController document = fixture.open(fixture.lines("c.log", 40));
    SearchController search = document.search();
    search.run("hit", LITERAL.withContextBefore(1).withContextAfter(1));
    settle(document);
    FilteredController filtered = document.filtered();
    List<Long> expected = new ArrayList<Long>();
    for (long hit = 3; hit < 40; hit += 7) {
      expected.add(hit - 1);
      expected.add(hit);
      expected.add(hit + 1);
    }
    assertEquals(expected, ControllerFixture.rows(filtered));
    assertEquals(6, search.state().matchCount(), "the count is of matches");
    assertTrue(filtered.contextRow(2));
    assertFalse(filtered.contextRow(3));
    assertFalse(filtered.matched(2), "the gutter bullet is for matches only");
    assertTrue(filtered.matched(3));
    assertTrue(filtered.startsGroup(9));
    assertFalse(filtered.startsGroup(10));
    assertFalse(document.contextRow(2), "the main view shades nothing");
    document.goToLine(1);
    search.findNext();
    assertEquals(3, document.sourceLine(document.selection().caret().line()));
    search.findNext();
    assertEquals(10, document.sourceLine(document.selection().caret().line()));
    fixture.application.execute(CommandId.SHOW_MARKS);
    assertEquals(0, filtered.viewport().lineCount(), "marks mode lists no context");
  }

  @Test
  void aKeptSetKeepsTheCountsItRanWith() throws Exception {
    DocumentController document = fixture.open(fixture.lines("k.log", 40));
    SearchController search = document.search();
    search.run("hit", LITERAL.withKeepResults(true).withContextAfter(2));
    settle(document);
    assertEquals(17, document.filtered().viewport().lineCount());
    search.run("line 1", search.options().withContextAfter(0));
    settle(document);
    long plain = search.resultCount();
    assertEquals(plain, document.filtered().viewport().lineCount());
    search.show(0);
    settle(document);
    assertEquals(2, search.shownOptions().contextAfter());
    assertEquals(17, document.filtered().viewport().lineCount());
    search.show(-1);
    settle(document);
    assertEquals(plain, document.filtered().viewport().lineCount());
  }

  @Test
  void theLastResultsContextExtendsAsTheFileGrows() throws Exception {
    Path file = write("g.log", List.of("a", "b", "hit"));
    DocumentController document = fixture.open(file);
    document.search().run("hit", LITERAL.withContextBefore(1).withContextAfter(3));
    settle(document);
    assertEquals(List.of(1L, 2L), ControllerFixture.rows(document.filtered()));
    append(file, "c", "d", "e", "f");
    waitForLines(document, 7);
    settle(document);
    assertEquals(List.of(1L, 2L, 3L, 4L, 5L), ControllerFixture.rows(document.filtered()));
  }

  @Test
  void anOpenRangeExtendsWithAutoRefresh() throws Exception {
    Path file = write("r.log", List.of("x", "BEGIN 1", "y", "END 1", "z", "BEGIN 2", "w"));
    DocumentController document = fixture.open(file);
    SearchController search = document.search();
    SearchOptions range = LITERAL.withRange(true).withRangeEnd("END").withAutoRefresh(true);
    search.run("BEGIN", range);
    settle(document);
    assertEquals(SearchViewState.Phase.DONE, search.state().phase());
    assertEquals(List.of(1L, 2L, 3L, 5L, 6L), ControllerFixture.toList(search.results()));
    append(file, "v", "u");
    waitForResults(search, List.of(1L, 2L, 3L, 5L, 6L, 7L, 8L));
    append(file, "END 2", "t", "BEGIN 3");
    waitForResults(search, List.of(1L, 2L, 3L, 5L, 6L, 7L, 8L, 9L, 11L));
    search.run("BEGIN", range.withInverse(true).withAutoRefresh(false));
    settle(document);
    assertEquals(List.of(0L, 4L, 10L), ControllerFixture.toList(search.results()));
  }

  @Test
  void theOptionsAreRememberedAndTheEndGoesIntoTheHistory() throws Exception {
    DocumentController document = fixture.open(fixture.lines("h.log", 10));
    SearchOptions ran =
        LITERAL.withRange(true).withRangeEnd("line 5").withContextBefore(2).withContextAfter(3);
    document.search().run("hit", ran);
    settle(document);
    assertEquals(List.of("hit", "line 5"), fixture.application.preferences().searchHistory());
    assertEquals(ran, fixture.application.initialOptions());
    DocumentController next = fixture.open(fixture.lines("n.log", 10));
    assertEquals(ran, next.search().options(), "a new tab starts with the saved options");
  }

  @Test
  void aRangeNeedsAnEndExpression() throws Exception {
    DocumentController document = fixture.open(fixture.lines("e.log", 10));
    SearchController search = document.search();
    search.run("hit", LITERAL.withRange(true));
    assertEquals(SearchViewState.Phase.FAILED, search.state().phase());
    assertTrue(search.state().error().startsWith("Error in range end"), search.state().error());
    assertEquals(0, fixture.budget.reserved(MemoryShare.RESULTS));
  }

  @Test
  void selectRangeSelectsTheLinesAroundTheCaret() throws Exception {
    Path file = write("s.log", List.of("a", "BEGIN", "b", "c", "END", "d", "BEGIN", "BEGIN", "e"));
    DocumentController document = fixture.open(file);
    SearchController search = document.search();
    search.setText("BEGIN", LITERAL.withRange(true).withRangeEnd("END"));
    document.goToLine(3);
    fixture.application.setFocusedView(document.filtered());
    fixture.application.execute(CommandId.SELECT_RANGE);
    fixture.runUntilIdle();
    Selection selection = document.selection();
    assertEquals(1, selection.start().line());
    assertEquals(4, selection.end().line());
    assertEquals(1, selection.caret().line());
    document.goToLine(6);
    fixture.application.execute(CommandId.SELECT_RANGE);
    fixture.runUntilIdle();
    assertEquals(UiText.NOTICE_NO_RANGE.text(), fixture.view.lastNotice());
    assertTrue(document.selection().isEmpty(), "the selection stays");
    document.goToLine(9);
    fixture.application.execute(CommandId.SELECT_RANGE);
    fixture.runUntilIdle();
    assertEquals(7, document.selection().start().line(), "the nearest start, unclosed");
    assertEquals(8, document.selection().end().line());
  }
}
