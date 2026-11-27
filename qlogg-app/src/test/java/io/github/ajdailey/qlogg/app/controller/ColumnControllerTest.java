package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A tab's column state through the controllers: detection, {@code view.columns.auto}, Toggle
 * Columns and the header choice remembered per file, the timestamp format a CLEF file takes, record
 * rows, search in column view (records, the CSV header, column term errors, auto-refresh over a
 * grown record) and a {@code RESULTS} refusal of the record scan.
 */
class ColumnControllerTest {
  private static final SearchOptions LITERAL =
      SearchOptions.DEFAULTS.withMode(MatchMode.LITERAL).withCombining(true);

  @TempDir Path temp;

  private ControllerFixture fixture;

  @AfterEach
  void close() throws InterruptedException {
    if (fixture != null) {
      fixture.close();
    }
  }

  private DocumentController open(Preferences preferences, String name, String text)
      throws Exception {
    if (fixture == null) {
      fixture = new ControllerFixture(temp, preferences);
    }
    Path file = temp.resolve(name);
    if (!Files.exists(file)) {
      Files.writeString(file, text, StandardCharsets.UTF_8);
    }
    return fixture.open(file);
  }

  private DocumentController reopen(DocumentController document) throws Exception {
    Path file = document.document().path();
    fixture.application.closeDocument(document);
    return fixture.open(file);
  }

  /** Waits until the tab's records cover its complete lines and its rows show them. */
  private void scanned(final DocumentController document) throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            LineIndex index = document.document().index();
            ColumnController columns = document.columns();
            return columns.recordsSettled(index)
                && columns.records().end() > 0
                && (columns.rows() == null
                    || columns.rows().records().end() == columns.records().end());
          }
        });
  }

  private List<Long> search(DocumentController document, String text, SearchOptions options)
      throws InterruptedException {
    document.search().run(text, options);
    fixture.runUntilEnded(document.search());
    return ControllerFixture.toList(document.search().results());
  }

  private static final String CSV =
      "id,level,message\n1,INFO,\"first\nsecond\"\n2,WARN,plain\n3,INFO,third\n";

  @Test
  void detectionTheToggleAndTheHeaderAreRememberedPerFile() throws Exception {
    DocumentController document = open(Preferences.DEFAULTS, "data.csv", CSV);
    CommandStates states = new CommandStates(fixture.application);
    ColumnController columns = document.columns();
    assertEquals(ColumnFormat.CSV, columns.schema().format());
    assertTrue(columns.schema().header());
    assertTrue(columns.shown());
    assertTrue(states.enabled(CommandId.TOGGLE_COLUMNS));
    fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    assertFalse(columns.shown());
    document = reopen(document);
    assertFalse(document.columns().shown());
    assertEquals(ColumnFormat.CSV, document.columns().schema().format());
    fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    assertTrue(document.columns().shown());
    fixture.application.setColumnHeader(document, false);
    assertFalse(document.columns().schema().header());
    assertEquals("1", document.columns().schema().columns().get(0).name());
    document = reopen(document);
    assertTrue(document.columns().shown());
    assertFalse(document.columns().schema().header());
    DocumentController text = open(Preferences.DEFAULTS, "plain.log", "a\nb\n");
    assertNull(text.columns().schema());
    assertFalse(states.enabled(CommandId.TOGGLE_COLUMNS));
    fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    assertFalse(text.columns().shown());
  }

  @Test
  void autoOffOpensTextViewAndToggleShowsTheDetectedFormat() throws Exception {
    Preferences preferences =
        Preferences.DEFAULTS.withView(Preferences.DEFAULTS.view().withColumnsAuto(false));
    DocumentController document = open(preferences, "auto.csv", CSV);
    assertFalse(document.columns().shown());
    assertEquals(ColumnFormat.CSV, document.columns().schema().format());
    fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    assertTrue(document.columns().shown());
  }

  @Test
  void aClefFileTakesTheClefTimeFormat() throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 5; i++) {
      text.append("{\"@t\":\"2026-11-27T10:00:0")
          .append(i)
          .append(".000Z\",\"@m\":\"event ")
          .append(i)
          .append("\"}\n");
    }
    DocumentController document = open(Preferences.DEFAULTS, "events.clef", text.toString());
    assertEquals(ColumnFormat.CLEF, document.columns().schema().format());
    assertEquals(BuiltInFormat.CLEF, document.time().format());
  }

  @Test
  void recordRowsKeepTheTopLineAcrossTheToggle() throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      text.append("<log4j:event logger=\"app\" timestamp=\"1764237600000\" level=\"INFO\">\n")
          .append("<log4j:message>event ")
          .append(i)
          .append("</log4j:message>\n</log4j:event>\n");
    }
    DocumentController document = open(Preferences.DEFAULTS, "events.xml", text.toString());
    assertEquals(ColumnFormat.LOG4J_XML, document.columns().schema().format());
    scanned(document);
    assertEquals(40, document.viewport().lineCount());
    assertEquals(30, document.sourceLine(10));
    fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    assertEquals(120, document.viewport().lineCount());
    document.viewport().scrollToLine(31);
    fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    assertEquals(30, document.sourceLine(document.viewport().topLine()));
    fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    assertEquals(30, document.viewport().topLine());
  }

  @Test
  void columnViewSearchesRecordsWithoutTheHeader() throws Exception {
    DocumentController document = open(Preferences.DEFAULTS, "search.csv", CSV);
    scanned(document);
    assertEquals(List.of(1L), search(document, "\"second\"", LITERAL));
    assertEquals(List.of(1L), search(document, "message:\"second\"", LITERAL));
    assertEquals(List.of(), search(document, "\"level\"", LITERAL));
    fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    assertEquals(List.of(2L), search(document, "\"second\"", LITERAL));
    assertEquals(List.of(0L), search(document, "\"level\"", LITERAL));
  }

  @Test
  void aColumnTermTheViewCannotTestHasItsOwnText() throws Exception {
    DocumentController document = open(Preferences.DEFAULTS, "errors.csv", CSV);
    scanned(document);
    search(document, "nope:\"x\"", LITERAL);
    assertEquals(SearchViewState.Phase.FAILED, document.search().state().phase());
    assertEquals(UiText.SEARCH_NO_COLUMN.format("nope"), document.search().state().error());
    search(document, "level:\"INFO\"", LITERAL.withRange(true).withRangeEnd("\"WARN\""));
    assertEquals(
        UiText.SEARCH_COLUMN_LINES_ONLY.format("level"), document.search().state().error());
    fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    search(document, "level:\"INFO\"", LITERAL);
    assertEquals(UiText.SEARCH_NO_COLUMN.format("level"), document.search().state().error());
  }

  @Test
  void autoRefreshTestsTheLastRecordAgainOnceItGrows() throws Exception {
    DocumentController document =
        open(Preferences.DEFAULTS, "grow.csv", "id,level,message\n1,INFO,plain\n2,INFO,\"open\n");
    scanned(document);
    final SearchController search = document.search();
    assertEquals(List.of(), search(document, "message:\"boom\"", LITERAL.withAutoRefresh(true)));
    Files.writeString(
        temp.resolve("grow.csv"),
        "boom\"\n3,INFO,boom\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.APPEND);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return search.state().matchCount() == 2
                && search.state().phase() == SearchViewState.Phase.DONE;
          }
        });
    assertEquals(List.of(2L, 4L), ControllerFixture.toList(search.results()));
  }

  @Test
  void aResultsRefusalStopsTheScanWithANotice() throws Exception {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 2000; i++) {
      text.append("<log4j:event level=\"INFO\">\n<log4j:message>m</log4j:message>\n")
          .append("</log4j:event>\n");
    }
    long held = fixture.budget.available(MemoryShare.RESULTS);
    assertTrue(fixture.budget.reserve(MemoryShare.RESULTS, held));
    try {
      final DocumentController document = open(Preferences.DEFAULTS, "full.xml", text.toString());
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return fixture.view.notices.contains(
                  UiText.NOTICE_RECORDS_MEMORY_LIMIT.format(
                      document.columns().records().end() + 1));
            }
          });
      assertTrue(document.columns().recordsSettled(document.document().index()));
    } finally {
      fixture.budget.release(MemoryShare.RESULTS, held);
    }
  }
}
