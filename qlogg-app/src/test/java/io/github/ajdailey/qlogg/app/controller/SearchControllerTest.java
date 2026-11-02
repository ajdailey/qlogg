package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.view.ManualUiDispatcher;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewListener;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.SearchLimit;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchControllerTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final SearchOptions LITERAL =
      new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, false, false);

  @TempDir Path temp;

  private final MemoryBudget budget = MemoryBudget.of(1L << 28);
  private FakeView view;
  private ManualUiDispatcher ui;
  private Workers workers;
  private ApplicationController application;

  @BeforeEach
  void create() {
    view = new FakeView();
    ui = new ManualUiDispatcher();
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                throw new AssertionError("worker failed", error);
              }
            });
    application =
        new ApplicationController(
            workers,
            ui,
            view,
            ApplicationControllerTest.services(workers, budget),
            ApplicationControllerTest.config(temp),
            Preferences.DEFAULTS);
  }

  @AfterEach
  void shutDown() throws InterruptedException {
    ApplicationControllerTest.shutDown(application, ui, view, workers);
  }

  /** Writes {@code count} lines; every seventh, from line 3 (0-based), says {@code hit}. */
  private Path lines(String name, int count) throws IOException {
    Path file = temp.resolve(name);
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.US_ASCII)) {
      for (int i = 0; i < count; i++) {
        out.write((i % 7 == 3 ? "hit " : "line ") + i + "\n");
      }
    }
    return file;
  }

  private DocumentController open(Path file) throws InterruptedException {
    application.open(List.of(new FileRequest(file, 0)));
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController document = application.activeDocument();
                return document != null && !document.state().indexing();
              }
            },
            BOUND));
    return application.activeDocument();
  }

  private void runUntilEnded(final SearchController search) throws InterruptedException {
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                SearchViewState.Phase phase = search.state().phase();
                return phase != SearchViewState.Phase.RUNNING && view.active.get() == 0;
              }
            },
            BOUND));
  }

  private static List<Long> toList(LineSet set) {
    List<Long> lines = new ArrayList<Long>();
    for (long i = 0; i < set.size(); i++) {
      lines.add(set.select(i));
    }
    return lines;
  }

  @Test
  void searchPublishesTheCountAndHoldsTheBusyCursor() throws Exception {
    DocumentController document = open(lines("a.log", 100));
    SearchController search = document.search();
    final List<SearchViewState> states = new ArrayList<SearchViewState>();
    final int[] resultEvents = new int[1];
    search.addListener(
        new SearchViewListener() {
          @Override
          public void stateChanged(SearchViewState state) {
            states.add(state);
          }

          @Override
          public void resultsChanged() {
            resultEvents[0]++;
          }
        });
    int before = view.activations.get();
    search.run("hit", LITERAL);
    assertEquals(SearchViewState.Phase.RUNNING, search.state().phase());
    assertEquals(before + 1, view.activations.get());
    runUntilEnded(search);
    SearchViewState done = search.state();
    assertEquals(SearchViewState.Phase.DONE, done.phase());
    assertEquals(SearchLimit.NONE, done.limit());
    assertEquals(14, done.matchCount());
    assertEquals(100, done.progressPercent());
    assertEquals("14 matches", done.statusText());
    assertEquals(14, search.results().size());
    assertEquals(3, search.results().select(0));
    assertEquals(SearchViewState.Phase.RUNNING, states.get(0).phase());
    assertTrue(resultEvents[0] >= 2);
    assertEquals(0, view.active.get());
  }

  @Test
  void findNextAndPreviousWalkTheMatchesFromTheCaret() throws Exception {
    DocumentController document = open(lines("b.log", 40));
    document.viewportResized(10, 80);
    SearchController search = document.search();
    search.run("hit", LITERAL);
    runUntilEnded(search);
    assertEquals(List.of(3L, 10L, 17L, 24L, 31L, 38L), toList(search.results()));
    application.execute(CommandId.FIND_NEXT);
    assertEquals(3, document.selection().caret().line());
    application.execute(CommandId.FIND_NEXT);
    assertEquals(10, document.selection().caret().line());
    document.select(Selection.caretAt(new TextPosition(30, 0)));
    application.execute(CommandId.FIND_NEXT);
    assertEquals(31, document.selection().caret().line());
    application.execute(CommandId.FIND_NEXT);
    application.execute(CommandId.FIND_NEXT);
    assertEquals(38, document.selection().caret().line());
    application.execute(CommandId.FIND_PREVIOUS);
    assertEquals(31, document.selection().caret().line());
    document.select(Selection.caretAt(new TextPosition(3, 0)));
    application.execute(CommandId.FIND_PREVIOUS);
    assertEquals(3, document.selection().caret().line());
    document.select(Selection.caretAt(new TextPosition(0, 0)));
    application.execute(CommandId.FIND_PREVIOUS);
    assertEquals(0, document.selection().caret().line());
  }

  @Test
  void selectionScopeSearchesTheSelectedLinesOnly() throws Exception {
    DocumentController document = open(lines("c.log", 40));
    SearchController search = document.search();
    document.select(new Selection(new TextPosition(12, 4), new TextPosition(26, 0)));
    search.run(
        "hit", new SearchOptions(MatchMode.LITERAL, true, false, false, false, true, false, false));
    runUntilEnded(search);
    assertEquals(List.of(17L, 24L), toList(search.results()));
    document.select(Selection.caretAt(new TextPosition(5, 0)));
    search.run(
        "hit", new SearchOptions(MatchMode.LITERAL, true, false, false, false, true, false, false));
    runUntilEnded(search);
    assertEquals(6, search.results().size());
  }

  @Test
  void aNewerSearchDropsTheOlderGenerationsReports() throws Exception {
    DocumentController document = open(lines("d.log", 200));
    final SearchController search = document.search();
    final List<SearchViewState> states = new ArrayList<SearchViewState>();
    search.addListener(
        new SearchViewListener() {
          @Override
          public void stateChanged(SearchViewState state) {
            states.add(state);
          }

          @Override
          public void resultsChanged() {
            // Counted through the state.
          }
        });
    search.run("hit", LITERAL);
    long first = search.generation();
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return view.active.get() == 0;
              }
            },
            BOUND));
    search.run("line 1", LITERAL);
    assertTrue(search.generation() > first);
    runUntilEnded(search);
    SearchViewState done = search.state();
    assertEquals("line 1", done.text());
    assertEquals(SearchViewState.Phase.DONE, done.phase());
    int expected = 0;
    for (int i = 0; i < 200; i++) {
      if (i % 7 != 3 && ("line " + i).contains("line 1")) {
        expected++;
      }
    }
    assertEquals(expected, done.matchCount());
    for (SearchViewState state : states) {
      assertFalse(
          state.text().equals("hit") && state.phase() == SearchViewState.Phase.DONE,
          "the first search's end was applied");
    }
  }

  @Test
  void syntaxErrorsFailWithTheColumn() throws Exception {
    DocumentController document = open(lines("e.log", 10));
    SearchController search = document.search();
    search.run(
        "\"a\" and",
        new SearchOptions(MatchMode.LITERAL, true, false, false, true, false, false, false));
    assertEquals(SearchViewState.Phase.FAILED, search.state().phase());
    assertEquals(
        "Error in expression at column 8: expected a quoted pattern, not or (",
        search.state().statusText());
    assertEquals(0, view.active.get());
    search.run("(unclosed", SearchOptions.DEFAULTS);
    runUntilEnded(search);
    assertEquals(SearchViewState.Phase.FAILED, search.state().phase());
    assertTrue(
        search.state().statusText().startsWith("Invalid regular expression at column "),
        search.state().statusText());
  }

  @Test
  void cancelStopsACatastrophicRegex() throws Exception {
    Path file = temp.resolve("evil.log");
    Files.write(file, ("a".repeat(60) + "\n").getBytes(StandardCharsets.US_ASCII));
    DocumentController document = open(file);
    SearchController search = document.search();
    search.run("(.*a){25}x", SearchOptions.DEFAULTS);
    search.cancel();
    runUntilEnded(search);
    assertEquals(SearchViewState.Phase.CANCELED, search.state().phase());
    assertEquals("Search stopped: 0 matches", search.state().statusText());
  }

  @Test
  void clearAndEmptyTextEmptyTheResults() throws Exception {
    DocumentController document = open(lines("f.log", 30));
    SearchController search = document.search();
    search.run("hit", LITERAL);
    runUntilEnded(search);
    assertTrue(search.results().size() > 0);
    assertTrue(budget.reserved(MemoryShare.RESULTS) > 0);
    search.clear();
    assertEquals(0, search.results().size());
    assertEquals(SearchViewState.Phase.IDLE, search.state().phase());
    assertEquals(0, budget.reserved(MemoryShare.RESULTS));
    search.run("hit", LITERAL);
    runUntilEnded(search);
    search.run("", LITERAL);
    assertEquals(0, search.results().size());
    assertEquals("", search.state().statusText());
  }

  @Test
  void closingTheDocumentEndsTheSearchAndReturnsItsReservation() throws Exception {
    Path file = temp.resolve("evil.log");
    Files.write(file, ("hit\n" + "a".repeat(60) + "\n").getBytes(StandardCharsets.US_ASCII));
    DocumentController document = open(file);
    document.search().run("hit|(.*a){25}x", SearchOptions.DEFAULTS);
    assertEquals(1, view.active.get());
    application.closeDocument();
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return view.active.get() == 0 && budget.reserved(MemoryShare.RESULTS) == 0;
              }
            },
            BOUND));
  }

  @Test
  void focusSearchCommandReachesTheView() {
    application.execute(CommandId.FOCUS_SEARCH);
    assertEquals(1, view.searchFocused);
  }

  @Test
  void spaceSwitchesBetweenTheMainAndFilteredView() {
    application.execute(CommandId.RELEASE_FOCUS);
    assertEquals(0, view.searchFocused);
    assertEquals("filtered", view.focusedView);
  }
}
