package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.Session;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.search.FilterPatterns;
import io.github.ajdailey.qlogg.app.search.PredefinedFilter;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The search bar's column in a column-view tab: the terms it scopes, the result text, the error
 * texts, the prefix Save as Filter writes, a filter tab's session key, the save form, and the CSV
 * header left out of the rows.
 */
class ColumnSearchBarTest {
  private static final String NL = System.lineSeparator();
  private static final SearchOptions LITERAL = SearchOptions.DEFAULTS.withMode(MatchMode.LITERAL);
  private static final String CSV =
      "id,level,message\n1,INFO,start\n2,WARN,INFO seen\n3,ERROR,stop\n";

  @TempDir Path temp;

  private ControllerFixture fixture;

  @AfterEach
  void close() throws InterruptedException {
    if (fixture != null) {
      fixture.close();
    }
  }

  private DocumentController open() throws Exception {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    Path file = temp.resolve("levels.csv");
    Files.writeString(file, CSV, StandardCharsets.UTF_8);
    final DocumentController document = fixture.open(file);
    document.viewportResized(10, 80);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            LineIndex index = document.document().index();
            return document.columns().shown()
                && document.columns().recordsSettled(index)
                && document.columns().records().end() >= index.lineCount() - 1;
          }
        });
    return document;
  }

  private List<Long> search(DocumentController document, String text, SearchOptions options)
      throws Exception {
    document.search().run(text, options);
    fixture.runUntilEnded(document.search());
    return ControllerFixture.toList(document.search().results());
  }

  @Test
  void theColumnScopesEveryTermWithoutItsOwnColumn() throws Exception {
    DocumentController document = open();
    assertEquals(List.of(1L, 2L), search(document, "INFO", LITERAL));
    assertEquals(List.of(1L), search(document, "INFO", LITERAL.withColumn("level")));
    assertEquals(
        List.of(1L, 3L),
        search(
            document,
            "\"INFO\" or message:\"stop\"",
            LITERAL.withCombining(true).withColumn("Level")));
    assertEquals("[level] INFO", ResultTabs.label("INFO", LITERAL.withColumn("level")));
  }

  @Test
  void aColumnTheViewLacksFailsWithTheColumnText() throws Exception {
    DocumentController document = open();
    search(document, "INFO", LITERAL.withColumn("Thread"));
    SearchViewState state = document.search().state();
    assertEquals(SearchViewState.Phase.FAILED, state.phase());
    assertEquals(UiText.SEARCH_NO_COLUMN.format("Thread"), state.error());
    fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    search(document, "level:\"INFO\"", LITERAL.withCombining(true));
    assertEquals(UiText.SEARCH_NO_COLUMN.format("level"), document.search().state().error());
  }

  @Test
  void saveAsFilterWritesTheColumnAsAPrefix() throws Exception {
    DocumentController document = open();
    document.search().setFieldText("IN\"FO");
    document.search().setOptions(LITERAL.withColumn("level"));
    fixture.application.execute(CommandId.SAVE_AS_FILTER);
    fixture.view.nameListener.nameChosen("mock filter");
    List<PredefinedFilter> filters = fixture.application.preferences().filters();
    PredefinedFilter saved = filters.get(filters.size() - 1);
    assertEquals("level:\"IN\\\"FO\"", saved.pattern());
    assertEquals(MatchMode.LITERAL, saved.mode());
    assertTrue(FilterPatterns.needsCombining(List.of(saved)));
    assertEquals("level:\"IN\\\"FO\"", FilterPatterns.compose(List.of(saved), false));
    PredefinedFilter regex = new PredefinedFilter("mock", "a.b", MatchMode.LITERAL);
    assertEquals(
        "\"a\\.b\" or level:\"IN\\\"FO\"", FilterPatterns.compose(List.of(regex, saved), false));
  }

  @Test
  void aFilterTabKeepsTheColumnInItsSession() throws Exception {
    final DocumentController root = open();
    fixture.application.restoreSession(Session.EMPTY);
    search(root, "INFO", LITERAL.withColumn("level"));
    fixture.application.filterToTab(root);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return fixture.application.activeDocument() != root;
          }
        });
    assertEquals(
        UiText.FILTER_TAB_TITLE.format("levels.csv", "[level] INFO"),
        fixture.application.tabTitle(fixture.application.activeDocument()));
    fixture.close();
    List<Session> saved = ApplicationControllerTest.config(temp).sessions().loadWindows();
    assertEquals("level", saved.get(0).tabs().get(1).derived().column());
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    fixture.application.restoreSession(saved.get(0));
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            List<DocumentController> open = fixture.application.documents();
            return open.size() == 2 && ControllerFixture.rows(open.get(1)).equals(List.of(1L));
          }
        });
  }

  @Test
  void theSaveChooserTakesTheForm() throws Exception {
    DocumentController document = open();
    document.select(new Selection(new TextPosition(0, 0), new TextPosition(1, Long.MAX_VALUE)));
    final Path tsv = temp.resolve("cells.tsv");
    fixture.view.saveTarget = tsv;
    fixture.view.saveOriginal = false;
    fixture.application.execute(CommandId.SAVE_SELECTION);
    assertEquals("levels.csv.tsv", fixture.view.columnSaveName);
    assertEquals("1\tINFO\tstart" + NL + "2\tWARN\tINFO seen", written(tsv));
    final Path lines = temp.resolve("lines.txt");
    fixture.view.saveTarget = lines;
    fixture.view.saveOriginal = true;
    fixture.application.execute(CommandId.SAVE_SELECTION);
    assertEquals("1,INFO,start" + NL + "2,WARN,INFO seen", written(lines));
  }

  private String written(final Path file) throws Exception {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            try {
              return Files.exists(file) && Files.size(file) > 0;
            } catch (java.io.IOException e) {
              return false;
            }
          }
        });
    fixture.runUntilIdle();
    return Files.readString(file, StandardCharsets.UTF_8).strip();
  }

  @Test
  void columnViewLeavesTheHeaderOutAndTextViewShowsIt() throws Exception {
    DocumentController document = open();
    assertEquals(List.of(1L, 2L, 3L), ControllerFixture.rows(document));
    fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return ControllerFixture.rows(document).equals(List.of(0L, 1L, 2L, 3L));
          }
        });
  }
}
