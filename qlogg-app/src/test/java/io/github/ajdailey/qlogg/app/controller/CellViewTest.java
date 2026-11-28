package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.TimeText;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.LineBlock;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.MatchMode;
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
 * What a column-view row holds through the controllers: cells of a record row, copy as shown cells,
 * raw lines and one cell, a copy over the share, Show Record, quick find in cells, the Time cell
 * with the offset, a filter tab's continuation line showing its record, and the top row held while
 * the record scan extends over appended lines.
 */
class CellViewTest {
  private static final String NL = System.lineSeparator();
  private static final SearchOptions LITERAL =
      SearchOptions.DEFAULTS.withMode(MatchMode.LITERAL).withCombining(true);
  private static final String CSV =
      "id,level,message\n1,INFO,\"first\nsecond\"\n2,WARN,plain\n3,INFO,third\n";
  private static final String EVENT =
      "<log4j:event logger=\"app\" timestamp=\"1764237600000\" level=\"INFO\">\n"
          + "<log4j:message>event %d</log4j:message>\n</log4j:event>\n";

  @TempDir Path temp;

  private ControllerFixture fixture;

  @AfterEach
  void close() throws InterruptedException {
    if (fixture != null) {
      fixture.close();
    }
  }

  private DocumentController open(String name, String text) throws Exception {
    if (fixture == null) {
      fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    }
    Path file = temp.resolve(name);
    Files.writeString(file, text, StandardCharsets.UTF_8);
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
                && columns.records().end() >= index.lineCount() - 1
                && (columns.rows() == null
                    || columns.rows().records().end() == columns.records().end());
          }
        });
  }

  /** Waits until a view holds a column-view row whose text contains {@code text}. */
  private LineBlock rowsWith(final LineViewController view, final long row, final String text)
      throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            LineBlock lines = view.lines();
            return lines.cellView() && lines.contains(row) && lines.text(row).contains(text);
          }
        });
    return view.lines();
  }

  private String copied(CommandId command) throws InterruptedException {
    fixture.view.clipboard = null;
    fixture.application.execute(command);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return fixture.view.clipboard != null;
          }
        });
    return fixture.view.clipboard;
  }

  private static Selection rows(long first, long last) {
    return new Selection(new TextPosition(first, 0), new TextPosition(last, Long.MAX_VALUE));
  }

  @Test
  void aRecordRowShowsItsCellsAndTheHeaderRowIsMarked() throws Exception {
    DocumentController document = open("cells.csv", CSV);
    scanned(document);
    LineBlock lines = rowsWith(document, 1, "first  second");
    assertTrue(lines.header(0));
    assertTrue(lines.text(1).startsWith("1  INFO  first  second "));
    assertEquals('\n', lines.controls(1).charAt(14));
    assertEquals(3, lines.cellCount(1));
    assertEquals(9, lines.cellStart(1, 2));
    assertEquals(3, lines.sourceLine(2));
    assertFalse(lines.cut(1));
  }

  @Test
  void copyTakesShownCellsRawLinesOrOneCell() throws Exception {
    DocumentController document = open("copy.csv", CSV);
    scanned(document);
    document.select(rows(1, 2));
    assertEquals("1\tINFO\tfirst\\nsecond" + NL + "2\tWARN\tplain", copied(CommandId.COPY));
    assertEquals(
        "2\t1\tINFO\tfirst\\nsecond" + NL + "4\t2\tWARN\tplain",
        copied(CommandId.COPY_WITH_LINE_NUMBERS));
    assertEquals(
        "1,INFO,\"first" + NL + "second\"" + NL + "2,WARN,plain", copied(CommandId.COPY_RAW_LINES));
    document.select(Selection.caretAt(new TextPosition(1, 10)));
    assertEquals("first\nsecond", copied(CommandId.COPY_CELL));
    document.select(Selection.caretAt(new TextPosition(1, 4)));
    assertEquals("INFO", copied(CommandId.COPY_CELL));
  }

  @Test
  void aCopyTheShareCannotHoldIsRefused() throws Exception {
    DocumentController document = open("cap.csv", CSV);
    scanned(document);
    long held = fixture.budget.available(MemoryShare.COPY);
    assertTrue(fixture.budget.reserve(MemoryShare.COPY, held));
    try {
      document.select(rows(0, 3));
      fixture.application.execute(CommandId.COPY);
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return !fixture.view.errors.isEmpty();
            }
          });
      assertNull(fixture.view.clipboard);
      assertTrue(
          fixture.view.errors.get(0).contains(UiText.COPY_TITLE.text()),
          fixture.view.errors.toString());
    } finally {
      fixture.budget.release(MemoryShare.COPY, held);
    }
  }

  @Test
  void showRecordListsEveryColumn() throws Exception {
    DocumentController document = open("show.csv", CSV);
    scanned(document);
    document.select(Selection.caretAt(new TextPosition(1, 0)));
    fixture.application.execute(CommandId.SHOW_RECORD);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !fixture.view.texts.isEmpty();
          }
        });
    assertEquals(
        UiText.SHOW_RECORD_TITLE.format(2) + ": id: 1\nlevel: INFO\nmessage: first\nsecond",
        fixture.view.texts.get(0));
  }

  @Test
  void theColumnCommandsAreEnabledInColumnViewOnly() throws Exception {
    DocumentController document = open("states.csv", CSV);
    CommandStates states = new CommandStates(fixture.application);
    assertTrue(states.enabled(CommandId.COPY_CELL));
    assertTrue(states.enabled(CommandId.COPY_RAW_LINES));
    assertTrue(states.enabled(CommandId.SHOW_RECORD));
    fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    assertFalse(document.columns().shown());
    assertFalse(states.enabled(CommandId.COPY_CELL));
    assertFalse(states.enabled(CommandId.SHOW_RECORD));
  }

  @Test
  void quickFindSearchesCellValues() throws Exception {
    final DocumentController document = open("find.csv", CSV);
    scanned(document);
    document.select(Selection.caretAt(new TextPosition(0, 0)));
    QuickFindController quickFind = fixture.application.quickFind();
    fixture.application.execute(CommandId.FIND);
    quickFind.setText("second");
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !document.selection().isEmpty();
          }
        });
    assertEquals(
        new Selection(new TextPosition(1, 39), new TextPosition(1, 45)), document.selection());
  }

  @Test
  void theTimeCellFollowsTheOffset() throws Exception {
    DocumentController document =
        open(
            "access.log",
            "10.0.0.1 - mock [27/Nov/2026:10:00:00 +0000] \"GET / HTTP/1.1\" 200 12\n"
                + "10.0.0.2 - mock [27/Nov/2026:10:00:01 +0000] \"GET / HTTP/1.1\" 200 12\n"
                + "10.0.0.3 - mock [27/Nov/2026:10:00:02 +0000] \"GET / HTTP/1.1\" 200 12\n");
    assertTrue(document.columns().shown());
    long stamp = 1_795_773_600_000_000_000L;
    String plain = TimeText.instant(stamp, 3, document.time().displayZone());
    rowsWith(document, 0, plain);
    document.time().setOffset(3_600_000_000_000L);
    String shifted = TimeText.instant(stamp + 3_600_000_000_000L, 3, document.time().displayZone());
    assertTrue(rowsWith(document, 0, shifted).text(0).contains(shifted));
  }

  @Test
  void aFilterTabRowOnAContinuationLineShowsItsRecord() throws Exception {
    final DocumentController parent = open("tab.csv", CSV);
    scanned(parent);
    fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    parent.search().run("second", LITERAL.withCombining(false));
    fixture.runUntilEnded(parent.search());
    assertEquals(List.of(2L), ControllerFixture.toList(parent.search().results()));
    fixture.application.filterToTab(parent);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = fixture.application.activeDocument();
            return shown != parent && ControllerFixture.rows(shown).equals(List.of(2L));
          }
        });
    DocumentController tab = fixture.application.activeDocument();
    tab.viewportResized(10, 80);
    if (!tab.columns().shown()) {
      fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    }
    LineBlock lines = rowsWith(tab, 0, "first  second");
    assertTrue(lines.text(0).startsWith("1  INFO  "));
    assertEquals(2, lines.sourceLine(0));
  }

  @Test
  void aColumnSearchInAFilterTabListsRecordFirstLines() throws Exception {
    final DocumentController parent = open("records.csv", CSV);
    scanned(parent);
    parent.search().run("\"second\"", LITERAL);
    fixture.runUntilEnded(parent.search());
    assertEquals(List.of(1L), ControllerFixture.toList(parent.search().results()));
    fixture.application.filterToTab(parent);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = fixture.application.activeDocument();
            return shown != parent && ControllerFixture.rows(shown).equals(List.of(1L));
          }
        });
    DocumentController tab = fixture.application.activeDocument();
    tab.viewportResized(10, 80);
    assertTrue(tab.columns().shown());
    tab.search().run("message:\"second\"", LITERAL);
    fixture.runUntilEnded(tab.search());
    assertEquals(List.of(1L), ControllerFixture.toList(tab.search().results()));
    assertTrue(rowsWith(tab, 0, "first  second").text(0).startsWith("1  INFO  "));
  }

  @Test
  void aColumnSearchInATabOfContinuationLinesFindsTheirRecords() throws Exception {
    final DocumentController parent = open("lines.csv", CSV);
    scanned(parent);
    fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    parent.search().run("second", LITERAL.withCombining(false));
    fixture.runUntilEnded(parent.search());
    assertEquals(List.of(2L), ControllerFixture.toList(parent.search().results()));
    fixture.application.filterToTab(parent);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = fixture.application.activeDocument();
            return shown != parent && ControllerFixture.rows(shown).equals(List.of(2L));
          }
        });
    DocumentController tab = fixture.application.activeDocument();
    tab.viewportResized(10, 80);
    if (!tab.columns().shown()) {
      fixture.application.execute(CommandId.TOGGLE_COLUMNS);
    }
    tab.search().run("message:\"second\"", LITERAL);
    fixture.runUntilEnded(tab.search());
    assertEquals(List.of(1L), ControllerFixture.toList(tab.search().results()));
    tab.search().run("level:WARN", LITERAL);
    fixture.runUntilEnded(tab.search());
    assertEquals(List.of(), ControllerFixture.toList(tab.search().results()));
  }

  @Test
  void theTopRowHoldsItsLineWhileTheScanExtends() throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      text.append(String.format(EVENT, i));
    }
    final DocumentController document = open("grow.xml", text.toString());
    scanned(document);
    document.viewport().scrollToLine(10);
    assertEquals(30, document.sourceLine(document.viewport().topLine()));
    StringBuilder more = new StringBuilder();
    for (int i = 40; i < 400; i++) {
      more.append(String.format(EVENT, i));
    }
    Files.writeString(
        temp.resolve("grow.xml"),
        more.toString(),
        StandardCharsets.UTF_8,
        StandardOpenOption.APPEND);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.viewport().lineCount() == 400;
          }
        });
    assertEquals(30, document.sourceLine(document.viewport().topLine()));
    // The Message cell fits the first rows read, which may cut "event 10" to "event 1".
    assertEquals(30, rowsWith(document, 10, "event 1").sourceLine(10));
  }
}
