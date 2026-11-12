package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.search.PredefinedFilter;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewListener;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.LineRange;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
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

/** Kept result sets, option keys, history, edits with selection, limits and filters. */
class SearchFeaturesTest {
  private static final SearchOptions LITERAL =
      new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, false, false);

  @TempDir Path temp;

  private ControllerFixture fixture;
  private DocumentController document;
  private SearchController search;

  @BeforeEach
  void open() throws Exception {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    document = fixture.open(fixture.lines("s.log", 40));
    search = document.search();
  }

  @AfterEach
  void close() throws InterruptedException {
    fixture.close();
  }

  private long retained(SearchController.KeptResults kept) {
    return kept.retainedBytes();
  }

  @Test
  void keptResultsStayChargedUntilRemoved() throws Exception {
    search.run("hit", LITERAL);
    fixture.runUntilEnded(search);
    long first = ((MutableLineSet) search.results()).retainedBytes();
    assertTrue(first > 0);
    fixture.application.execute(CommandId.TOGGLE_KEEP_RESULTS);
    assertTrue(search.options().keepResults());
    search.run("line 1", search.options());
    fixture.runUntilEnded(search);
    assertEquals(1, search.kept().size());
    SearchController.KeptResults kept = search.kept().get(0);
    assertEquals("hit", kept.text());
    assertEquals(6, kept.lines().size());
    long current = ((MutableLineSet) search.results()).retainedBytes();
    assertEquals(first + current, fixture.budget.reserved(MemoryShare.RESULTS));
    search.show(0);
    assertEquals(6, document.filtered().viewport().lineCount());
    assertEquals(17, document.filtered().sourceLine(2));
    search.run("line 2", search.options());
    fixture.runUntilEnded(search);
    assertEquals(2, search.kept().size());
    assertEquals(-1, search.shown());
    search.removeKept(kept);
    assertEquals("line 1", search.kept().get(0).text());
    // A removed set is ignored, by identity.
    search.show(kept);
    assertEquals(-1, search.shown());
    search.removeKept(kept);
    assertEquals(1, search.kept().size());
    assertEquals(
        retained(search.kept().get(0)) + ((MutableLineSet) search.results()).retainedBytes(),
        fixture.budget.reserved(MemoryShare.RESULTS));
    fixture.application.closeDocument();
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return fixture.budget.reserved(MemoryShare.RESULTS) == 0;
          }
        });
  }

  @Test
  void keepResultsKeepsAFinishedSetWhileARefreshRuns() throws Exception {
    fixture.application.execute(CommandId.TOGGLE_KEEP_RESULTS);
    fixture.application.execute(CommandId.TOGGLE_AUTO_REFRESH);
    search.run("hit", search.options());
    fixture.runUntilEnded(search);
    StringBuilder more = new StringBuilder();
    for (int i = 40; i < 50; i++) {
      more.append(i % 7 == 3 ? "hit " : "line ").append(i).append('\n');
    }
    Files.writeString(temp.resolve("s.log"), more, StandardOpenOption.APPEND);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.document().index().lineCount() == 50;
          }
        });
    search.appended();
    assertTrue(search.refreshing());
    search.run("line 1", search.options());
    fixture.runUntilEnded(search);
    assertEquals(1, search.kept().size());
    LineSet kept = search.kept().get(0).lines();
    assertEquals("hit", search.kept().get(0).text());
    assertTrue(kept.size() >= 6, ControllerFixture.toList(kept).toString());
    assertEquals(
        List.of(3L, 10L, 17L, 24L, 31L, 38L), ControllerFixture.toList(kept).subList(0, 6));
    search.removeKept(0);
    assertEquals(
        ((MutableLineSet) search.results()).retainedBytes(),
        fixture.budget.reserved(MemoryShare.RESULTS));
  }

  @Test
  void optionKeysToggleAndTellTheViews() {
    final List<SearchOptions> seen = new ArrayList<SearchOptions>();
    search.addListener(
        fixture.ui.confined(
            SearchViewListener.class,
            new SearchViewListener() {
              @Override
              public void stateChanged(SearchViewState state) {}

              @Override
              public void resultsChanged() {}

              @Override
              public void optionsChanged(String text, SearchOptions options) {
                seen.add(options);
              }
            }));
    SearchOptions before = search.options();
    fixture.application.execute(CommandId.TOGGLE_CASE);
    fixture.application.execute(CommandId.TOGGLE_REGEX);
    fixture.application.execute(CommandId.TOGGLE_INVERSE);
    fixture.application.execute(CommandId.TOGGLE_COMBINING);
    fixture.application.execute(CommandId.TOGGLE_AUTO_REFRESH);
    SearchOptions after = search.options();
    assertEquals(!before.caseSensitive(), after.caseSensitive());
    assertEquals(MatchMode.LITERAL, after.mode());
    assertTrue(after.inverse() && after.combining() && after.autoRefresh());
    assertEquals(5, seen.size());
    assertEquals(after, fixture.application.initialOptions());
  }

  @Test
  void searchesFillTheBoundedHistory() throws Exception {
    fixture.application.setSearchHistory(List.of(), 2);
    search.run("a", LITERAL);
    search.run("b", LITERAL);
    search.run("a", LITERAL);
    search.run("c", LITERAL);
    fixture.runUntilEnded(search);
    assertEquals(List.of("c", "a"), fixture.application.preferences().searchHistory());
    fixture.application.execute(CommandId.CLEAR_HISTORY);
    assertEquals(List.of(), fixture.application.preferences().searchHistory());
    fixture.application.execute(CommandId.EDIT_HISTORY);
    assertEquals(1, fixture.view.historyDialogs);
  }

  @Test
  void editsWithSelectionRunWhenTheOptionIsOn() throws Exception {
    search.setOptions(SearchOptions.DEFAULTS);
    document.select(new Selection(new TextPosition(3, 0), new TextPosition(3, 3)));
    fixture.application.execute(CommandId.REPLACE_SEARCH);
    fixture.runUntil(ended("hit"));
    assertEquals(6, search.state().matchCount());
    document.select(new Selection(new TextPosition(5, 0), new TextPosition(5, 6)));
    fixture.application.execute(CommandId.ADD_TO_SEARCH);
    fixture.runUntil(ended("hit|line 5"));
    assertEquals(7, search.state().matchCount());
    fixture.application.execute(CommandId.RUN_SEARCH_ON_ADD);
    assertFalse(fixture.application.preferences().runSearchOnAdd());
    document.select(new Selection(new TextPosition(10, 4), new TextPosition(10, 6)));
    fixture.application.execute(CommandId.EXCLUDE_FROM_SEARCH);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return search.fieldText().endsWith("and not \"10\"");
          }
        });
    assertEquals("\"hit|line 5\" and not \"10\"", search.fieldText());
    assertTrue(search.options().combining());
    assertEquals("hit|line 5", search.state().text());
    search.run();
    fixture.runUntil(ended("\"hit|line 5\" and not \"10\""));
    assertEquals(6, search.state().matchCount());
  }

  private BooleanSupplier ended(final String text) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return search.state().text().equals(text)
            && search.state().phase() == SearchViewState.Phase.DONE
            && fixture.view.active.get() == 0;
      }
    };
  }

  @Test
  void searchLimitsNarrowTheScope() throws Exception {
    document.select(Selection.caretAt(new TextPosition(10, 0)));
    fixture.application.execute(CommandId.SET_SEARCH_START);
    document.select(Selection.caretAt(new TextPosition(24, 0)));
    fixture.application.execute(CommandId.SET_SEARCH_END);
    assertEquals(new LineRange(10, 25), search.scope(false));
    search.run("hit", LITERAL);
    fixture.runUntilEnded(search);
    assertEquals(List.of(10L, 17L, 24L), ControllerFixture.toList(search.results()));
    document.select(new Selection(new TextPosition(15, 0), new TextPosition(30, 0)));
    assertEquals(new LineRange(15, 25), search.scope(true));
    fixture.application.execute(CommandId.CLEAR_SEARCH_LIMITS);
    assertEquals(null, search.scope(false));
  }

  @Test
  void filtersSaveImportAndExport() throws Exception {
    search.setText("ERROR|WARN", SearchOptions.DEFAULTS);
    fixture.application.execute(CommandId.SAVE_AS_FILTER);
    assertEquals("ERROR|WARN", fixture.view.suggestedName);
    fixture.view.nameListener.nameChosen("  Problems  ");
    fixture.application.execute(CommandId.SAVE_AS_FILTER);
    fixture.view.nameListener.nameChosen("Other name");
    assertEquals(UiText.NOTICE_FILTER_EXISTS.text(), fixture.view.lastNotice());
    List<PredefinedFilter> filters = fixture.application.preferences().filters();
    assertEquals(List.of(new PredefinedFilter("Problems", "ERROR|WARN", MatchMode.REGEX)), filters);
    StringBuilder longPattern = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      longPattern.append('x');
    }
    search.setText(longPattern.toString(), SearchOptions.DEFAULTS);
    fixture.application.execute(CommandId.SAVE_AS_FILTER);
    assertEquals(64, fixture.view.suggestedName.length());
    fixture.view.nameListener.nameChosen(" ");
    assertEquals(
        longPattern.substring(0, 64), fixture.application.preferences().filters().get(1).name());
    fixture.application.setFilters(filters);
    final Path file = temp.resolve("export.conf");
    final List<List<PredefinedFilter>> done = new ArrayList<List<PredefinedFilter>>();
    final List<String> failed = new ArrayList<String>();
    SettingsFileListener<PredefinedFilter> listener =
        new SettingsFileListener<PredefinedFilter>() {
          @Override
          public void done(List<PredefinedFilter> result) {
            done.add(result);
          }

          @Override
          public void failed(String message) {
            failed.add(message);
          }
        };
    fixture.application.exportFilters(file, filters, listener);
    fixture.runUntil(sized(done, 1));
    fixture.application.importFilters(file, listener);
    fixture.runUntil(sized(done, 2));
    assertEquals(filters, done.get(1));
    Files.writeString(file, "not a filter export\n");
    fixture.application.importFilters(file, listener);
    fixture.runUntil(sized(failed, 1));
    assertTrue(failed.get(0).contains("version=1"), failed.get(0));
  }

  // Clear Results.
  @Test
  void clearResultsEmptiesTheCurrentSetAndKeepsKeptSets() throws Exception {
    CommandStates states = new CommandStates(fixture.application);
    assertFalse(states.enabled(CommandId.CLEAR_RESULTS));
    search.run("hit", LITERAL);
    fixture.runUntilEnded(search);
    fixture.application.execute(CommandId.TOGGLE_KEEP_RESULTS);
    search.run("line 1", search.options());
    fixture.runUntilEnded(search);
    assertEquals(1, search.kept().size());
    assertTrue(search.resultCount() > 0);
    assertTrue(states.enabled(CommandId.CLEAR_RESULTS));
    fixture.application.execute(CommandId.CLEAR_RESULTS);
    assertEquals(0, search.resultCount());
    assertEquals(SearchViewState.Phase.IDLE, search.state().phase());
    assertEquals(1, search.kept().size());
    assertEquals("line 1", search.fieldText());
    assertFalse(states.enabled(CommandId.CLEAR_RESULTS));
  }

  private static BooleanSupplier sized(final List<?> list, final int size) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return list.size() == size;
      }
    };
  }
}
