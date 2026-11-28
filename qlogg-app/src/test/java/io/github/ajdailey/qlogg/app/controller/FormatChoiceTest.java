package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The View > Columns format items and First Line Is Header through the controllers: each item
 * applied, checked and remembered across a reopen, Text remembered as {@code none}, Timestamp on a
 * plain log, the header on and off, Toggle Columns after a choice, a filter tab and a rotated set,
 * and the busy indicator over the record scan a choice starts.
 */
class FormatChoiceTest {
  private static final List<CommandId> ITEMS =
      List.of(
          CommandId.COLUMNS_TEXT,
          CommandId.COLUMNS_CSV,
          CommandId.COLUMNS_TSV,
          CommandId.COLUMNS_CLF,
          CommandId.COLUMNS_CLEF,
          CommandId.COLUMNS_LOG4J_XML,
          CommandId.COLUMNS_TIME);

  /** ISO stamped lines with commas and tabs, matching no detection rule. */
  private static final String STAMPED =
      "2026-03-01 05:00:00.000 INFO a,b\tc\n"
          + "2026-03-01 05:00:01.000 WARN d,e\tf\n"
          + "    at mock.Frame(Mock.java:1)\n"
          + "2026-03-01 05:00:02.000 INFO g,h\ti\n";

  private static final String CSV = "id,level,message\n1,INFO,\"first\nsecond\"\n2,WARN,plain\n";

  @TempDir Path temp;

  private ControllerFixture fixture;
  private CommandStates states;

  @BeforeEach
  void start() {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    states = new CommandStates(fixture.application);
  }

  @AfterEach
  void close() throws InterruptedException {
    fixture.close();
  }

  private DocumentController open(String name, String text) throws Exception {
    Path file = temp.resolve(name);
    Files.writeString(file, text, StandardCharsets.UTF_8);
    return fixture.open(file);
  }

  private DocumentController reopen(DocumentController document) throws Exception {
    Path file = document.document().path();
    fixture.application.closeDocument(document);
    return fixture.open(file);
  }

  /** Returns the checked format item, failing unless exactly one is checked. */
  private CommandId checkedItem() {
    List<CommandId> checked = new ArrayList<CommandId>();
    for (CommandId item : ITEMS) {
      if (states.checked(item)) {
        checked.add(item);
      }
    }
    assertEquals(1, checked.size(), checked.toString());
    return checked.get(0);
  }

  private void scanned(final DocumentController document) throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            ColumnController columns = document.columns();
            return columns.recordsSettled(document.document().index())
                && columns.records().end() > 0
                && (columns.rows() == null
                    || columns.rows().records().end() == columns.records().end())
                && fixture.view.active.get() == 0;
          }
        });
  }

  @Test
  void eachItemAppliesIsCheckedAndIsRememberedAcrossAReopen() throws Exception {
    DocumentController document = open("app.log", STAMPED);
    assertNull(document.columns().schema());
    assertEquals(CommandId.COLUMNS_TEXT, checkedItem());
    for (CommandId item : ITEMS) {
      assertTrue(states.enabled(item), item.name());
    }
    for (CommandId item : ITEMS.subList(1, ITEMS.size())) {
      ColumnFormat format = FormatCommands.format(item);
      fixture.application.execute(item);
      assertEquals(format, document.columns().schema().format(), item.name());
      assertTrue(document.columns().shown());
      assertEquals(item, checkedItem());
      document = reopen(document);
      assertEquals(format, document.columns().schema().format(), item.name());
      assertTrue(document.columns().shown());
      assertEquals(item, checkedItem());
    }
  }

  @Test
  void textIsRememberedAsNoneAndKeepsTheDetectedFormatForTheToggle() throws Exception {
    DocumentController document = open("data.csv", CSV);
    assertEquals(CommandId.COLUMNS_CSV, checkedItem());
    fixture.application.execute(CommandId.COLUMNS_TEXT);
    assertFalse(document.columns().shown());
    assertEquals(CommandId.COLUMNS_TEXT, checkedItem());
    document = reopen(document);
    assertFalse(document.columns().shown());
    assertEquals(CommandId.COLUMNS_TEXT, checkedItem());
    fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    assertEquals(ColumnFormat.CSV, document.columns().schema().format());
    assertEquals(CommandId.COLUMNS_CSV, checkedItem());
  }

  @Test
  void timestampSplitsAPlainLogAndNeedsATimestampFormat() throws Exception {
    DocumentController document = open("stamped.log", STAMPED);
    assertEquals(BuiltInFormat.ISO, document.time().format());
    fixture.application.execute(CommandId.COLUMNS_TIME);
    ColumnSchema schema = document.columns().schema();
    assertEquals(ColumnFormat.TIME, schema.format());
    assertEquals("Time", schema.columns().get(0).name());
    assertEquals("Text", schema.columns().get(1).name());
    assertEquals(BuiltInFormat.ISO, schema.timeFormat());
    DocumentController plain = open("plain.log", "one\ntwo\n");
    assertNull(plain.time().format());
    assertFalse(states.enabled(CommandId.COLUMNS_TIME));
    fixture.application.execute(CommandId.COLUMNS_TIME);
    assertNull(plain.columns().schema());
    fixture.application.setTimeFormat(plain, BuiltInFormat.ISO);
    assertTrue(states.enabled(CommandId.COLUMNS_TIME));
  }

  @Test
  void firstLineIsHeaderNamesTheColumnsAndHidesLineZero() throws Exception {
    DocumentController document = open("header.csv", CSV);
    scanned(document);
    assertTrue(states.enabled(CommandId.FIRST_LINE_HEADER));
    assertTrue(states.checked(CommandId.FIRST_LINE_HEADER));
    assertEquals("id", document.columns().schema().columns().get(0).name());
    assertEquals(1, document.sourceLine(0));
    fixture.application.execute(CommandId.FIRST_LINE_HEADER);
    scanned(document);
    assertFalse(states.checked(CommandId.FIRST_LINE_HEADER));
    assertEquals("1", document.columns().schema().columns().get(0).name());
    assertEquals(0, document.sourceLine(0));
    assertEquals(1, document.sourceLine(1));
    assertEquals(3, document.sourceLine(2));
    document = reopen(document);
    scanned(document);
    assertFalse(document.columns().schema().header());
    assertEquals(0, document.sourceLine(0));
    fixture.application.execute(CommandId.FIRST_LINE_HEADER);
    assertTrue(document.columns().schema().header());
    document = reopen(document);
    scanned(document);
    assertTrue(states.checked(CommandId.FIRST_LINE_HEADER));
    assertEquals(1, document.sourceLine(0));
    fixture.application.execute(CommandId.COLUMNS_CLF);
    assertFalse(states.enabled(CommandId.FIRST_LINE_HEADER));
    assertFalse(states.checked(CommandId.FIRST_LINE_HEADER));
  }

  @Test
  void toggleColumnsSwitchesBetweenTextAndTheLastChoice() throws Exception {
    DocumentController document = open("toggle.log", STAMPED);
    fixture.application.execute(CommandId.COLUMNS_TIME);
    fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    assertFalse(document.columns().shown());
    assertEquals(CommandId.COLUMNS_TEXT, checkedItem());
    fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    assertEquals(ColumnFormat.TIME, document.columns().schema().format());
    assertEquals(CommandId.COLUMNS_TIME, checkedItem());
    fixture.application.execute(CommandId.COLUMNS_TSV);
    fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    assertEquals(CommandId.COLUMNS_TSV, checkedItem());
    document = reopen(document);
    assertEquals(CommandId.COLUMNS_TSV, checkedItem());
  }

  @Test
  void aChoiceStartsTheRecordScanUnderTheBusyIndicator() throws Exception {
    DocumentController document = open("quoted.txt", CSV);
    assertNull(document.columns().schema());
    fixture.runUntilIdle();
    fixture.application.execute(CommandId.COLUMNS_CSV);
    assertTrue(fixture.view.active.get() > 0, "busy while the records are read");
    scanned(document);
    assertEquals(1, document.sourceLine(0));
    assertEquals(3, document.sourceLine(1), "line 2 is in the record of line 1");
  }

  @Test
  void aFilterTabsChoiceActsOnItsFileAndReadsTheParentsRecords() throws Exception {
    final DocumentController parent = open("filtered.txt", CSV);
    parent
        .search()
        .run("INFO", SearchOptions.DEFAULTS.withMode(MatchMode.LITERAL).withCombining(true));
    fixture.runUntilEnded(parent.search());
    fixture.application.filterToTab(parent);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = fixture.application.activeDocument();
            return shown != parent && !shown.ownsDocument();
          }
        });
    DocumentController tab = fixture.application.activeDocument();
    fixture.application.execute(CommandId.COLUMNS_CSV);
    assertTrue(tab.columns().shown());
    assertEquals(CommandId.COLUMNS_CSV, checkedItem());
    assertEquals(ColumnFormat.CSV, parent.columns().schema().format());
    assertFalse(parent.columns().shown(), "the parent keeps its view");
    scanned(parent);
    assertTrue(tab.columns().recordsSettled(tab.document().index()));
    fixture.application.closeDocument(tab);
    DocumentController reopened = reopen(parent);
    assertTrue(reopened.columns().shown());
    assertEquals(ColumnFormat.CSV, reopened.columns().schema().format());
  }

  @Test
  void aRotatedSetRemembersTheChoiceForItsFile() throws Exception {
    Path logs = Files.createDirectories(temp.resolve("logs"));
    Path older = logs.resolve("app.log.1");
    Files.writeString(older, STAMPED, StandardCharsets.US_ASCII);
    Files.setLastModifiedTime(older, FileTime.fromMillis(System.currentTimeMillis() - 200_000));
    Path base = logs.resolve("app.log");
    Files.writeString(base, STAMPED, StandardCharsets.US_ASCII);
    DocumentController set = openSet(base);
    fixture.application.execute(CommandId.COLUMNS_TIME);
    assertEquals(ColumnFormat.TIME, set.columns().schema().format());
    assertEquals(CommandId.COLUMNS_TIME, checkedItem());
    fixture.application.closeDocument(set);
    set = openSet(base);
    assertTrue(set.columns().shown());
    assertEquals(ColumnFormat.TIME, set.columns().schema().format());
  }

  private DocumentController openSet(Path base) throws Exception {
    fixture.application.open(List.of(new FileRequest(base, 0, true)));
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = fixture.application.activeDocument();
            return shown != null
                && shown.state().title().equals("app.log +1")
                && !shown.state().indexing();
          }
        });
    return fixture.application.activeDocument();
  }
}
