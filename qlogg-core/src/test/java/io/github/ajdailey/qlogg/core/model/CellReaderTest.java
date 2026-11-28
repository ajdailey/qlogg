package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.columns.TimeCell;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.RecordLines;
import io.github.ajdailey.qlogg.core.search.RecordScan;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.SortedLines;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Column-view rows: cells of every format, the unparsed span, extra CSV fields, the Time cell with
 * offset and zone, record rows over a scan in progress and after growth, header rows, the cut
 * marker, frozen cells, cell highlights with and without a named column, tokens, the row cache and
 * cells fitted to records ({@link ColumnFit}).
 */
class CellReaderTest {
  private static final TimeContext UTC = new TimeContext(ZoneOffset.UTC, 2026, 11);
  private static final TimeCell PLAIN = new TimeCell(ZoneOffset.UTC, 0);
  private static final LineSet NONE = new SortedLines(new long[0]);
  private static final long HOUR = 3_600_000_000_000L;
  private static final String EVENT =
      "<log4j:event logger=\"mock.Run\" timestamp=\"1793534400000\" level=\"INFO\""
          + " thread=\"main\">\n<log4j:message>mock %s</log4j:message>\n</log4j:event>\n";
  private static final Rgb RED = new Rgb(200, 0, 0);

  @TempDir Path dir;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Workers workers;
  private LogDocument document;

  private void open(String content) throws Exception {
    if (document != null) {
      assertTrue(document.close(LogDocument.CLOSE_BOUND));
    } else {
      workers =
          new Workers(
              2,
              new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable error) {
                  failures.add(error);
                }
              });
    }
    MemoryBudget budget = MemoryBudget.of(1L << 28);
    DocumentServices services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(2, budget)),
            new SearchEngine(
                workers, StandardMatchers.INSTANCE, SearchSettings.defaults(2, budget)),
            new BlockCache(65536, 1 << 20),
            budget,
            StandardCharsets.UTF_8);
    Path file = dir.resolve("cells" + System.nanoTime() + ".log");
    Files.writeString(file, content, StandardCharsets.UTF_8);
    document = LogDocument.open(file, null, services);
    final CountDownLatch done = new CountDownLatch(1);
    document.addListener(
        new DocumentListener() {
          @Override
          public void indexProgressed(IndexProgress progress) {}

          @Override
          public void indexFinished(OperationOutcome outcome, IOException failure) {
            done.countDown();
          }

          @Override
          public void fileChanged(FileChange change) {}

          @Override
          public void closed() {}
        });
    document.startIndexing();
    assertTrue(done.await(30, TimeUnit.SECONDS));
  }

  @AfterEach
  void close() {
    if (document != null) {
      assertTrue(document.close(LogDocument.CLOSE_BOUND));
    }
    if (workers != null) {
      assertTrue(workers.close(Workers.CLOSE_BOUND));
    }
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private static ColumnRead view(ColumnLayout layout) {
    return new ColumnRead(layout, UTC, PLAIN, null, NONE);
  }

  private static ColumnLayout layout(ColumnSchema schema, int width, int... columns) {
    int[] widths = new int[columns.length];
    java.util.Arrays.fill(widths, width);
    return new ColumnLayout(schema, columns, widths, 0);
  }

  private LineBlock rows(ColumnRead view, long[] lines, Highlighter colors) throws IOException {
    return new CellReader(document.newReader())
        .readRows(document.index(), 0, lines, lines.length, 0, 200, view, colors, false);
  }

  private LineBlock all(ColumnRead view) throws IOException {
    long[] lines = new long[(int) document.index().lineCount()];
    for (int i = 0; i < lines.length; i++) {
      lines[i] = i;
    }
    return rows(view, lines, null);
  }

  @Test
  void csvCellsAreCutToTheirWidthAndExtraFieldsJoinTheLastColumn() throws Exception {
    open("a,b,c\n1,\"x,y\",3\n4,5,6,7\nlonger value,2,3\n");
    ColumnSchema schema =
        ColumnSchema.delimited(ColumnFormat.CSV, ',', List.of("a", "b", "c"), true);
    LineBlock block = all(view(layout(schema, 6, 0, 1, 2)));
    assertTrue(block.cellView());
    assertEquals("a     b     c     ", block.text(0));
    assertTrue(block.header(0));
    assertEquals("1     x,y   3     ", block.text(1));
    assertEquals("4     5     6,7   ", block.text(2));
    assertEquals("longe 2     3     ", block.text(3));
    assertEquals(3, block.cellCount(1));
    assertEquals(6, block.cellStart(1, 1));
    assertEquals(18, block.width(1));
    assertFalse(block.header(1));
  }

  @Test
  void cellsFitTheWidestValueOrNameUpToTheirLimit() throws Exception {
    String wide = "x".repeat(250);
    open("a,Message,c\n1,short,\t\n" + "12345,\"" + wide + "\",3\n");
    ColumnSchema schema =
        ColumnSchema.delimited(ColumnFormat.CSV, ',', List.of("a", "Message", "c"), true);
    ColumnLayout waiting =
        new ColumnLayout(
            schema,
            new int[] {0, 1, 2},
            new int[] {16, 16, 16},
            0,
            new boolean[] {true, true, false});
    assertTrue(waiting.unfitted());
    long[] lines = {0, 1, 2};
    int[] widths =
        ColumnFit.widths(
            new CellReader(document.newReader()), document.index(), lines, 3, view(waiting));
    // a: "12345" and the gap; Message: cut at 200; c: a tab to the next stop and the gap.
    assertEquals(6, widths[0]);
    assertEquals(ColumnLayout.FIT_TEXT_WIDTH, widths[1]);
    assertEquals(9, widths[2]);
    ColumnLayout fitted = waiting.fitted(widths);
    assertFalse(fitted.unfitted());
    assertEquals(16, fitted.width(2));
    assertEquals(
        0,
        ColumnFit.widths(
                new CellReader(document.newReader()),
                document.index(),
                new long[] {0},
                1,
                view(waiting))
            .length);
  }

  @Test
  void tsvClfClefAndTimestampCellsShowTheirValues() throws Exception {
    open("x\ty\n1\t2\n");
    ColumnSchema tsv = ColumnSchema.delimited(ColumnFormat.TSV, '\t', List.of("x", "y"), true);
    assertEquals("2   ", all(view(layout(tsv, 4, 1))).text(1));

    open("10.0.0.1 - mock [01/Nov/2026:12:00:00 +0000] \"GET /mock HTTP/1.1\" 200 1234\n");
    ColumnSchema clf = ColumnSchema.fixed(ColumnFormat.CLF);
    assertEquals(
        "10.0.0.1  mock      2026-11-01 12:00:00.000 GET /mock HTTP/1.1      200       ",
        all(view(
                new ColumnLayout(
                    clf, new int[] {0, 2, 3, 4, 5}, new int[] {10, 10, 24, 24, 10}, 0)))
            .text(0));

    open("{\"@t\":\"2026-11-01T12:00:00.1234Z\",\"@mt\":\"Run {Name}\",\"Name\":\"mock\"}\n");
    ColumnSchema clef = ColumnSchema.fixed(ColumnFormat.CLEF);
    assertEquals(
        "2026-11-01 12:00:00.1234 Information  Run mock   ",
        all(view(new ColumnLayout(clef, new int[] {0, 1, 2}, new int[] {25, 13, 11}, 0))).text(0));

    open("2026-11-01 12:00:00.123 INFO text\n\tat mock.Run\n");
    ColumnSchema time = ColumnSchema.timestamp(BuiltInFormat.ISO);
    LineBlock block = all(view(layout(time, 24, 0, 1)));
    assertEquals("2026-11-01 12:00:00.123 INFO text", block.text(0).stripTrailing());
    assertTrue(block.unparsed(1));
    assertEquals("        at mock.Run", block.text(1).stripTrailing());
    assertEquals(1, block.cellCount(1));
  }

  @Test
  void theTimeCellShowsTheInstantInTheDisplayZoneWithTheOffset() throws Exception {
    open("10.0.0.1 - mock [01/Nov/2026:12:00:00 +0000] \"GET /mock HTTP/1.1\" 200 1234\n");
    ColumnSchema clf = ColumnSchema.fixed(ColumnFormat.CLF);
    ColumnLayout layout = layout(clf, 30, 3);
    ColumnRead shifted =
        new ColumnRead(layout, UTC, new TimeCell(ZoneId.of("UTC+02:00"), HOUR), null, NONE);
    assertEquals("2026-11-01 15:00:00.000", all(shifted).text(0).stripTrailing());

    open("{\"@t\":\"2026-11-01T12:00:00.1234567Z\",\"@m\":\"mock\"}\n");
    ColumnLayout clef = layout(ColumnSchema.fixed(ColumnFormat.CLEF), 30, 0);
    assertEquals("2026-11-01 12:00:00.1234567", all(view(clef)).text(0).stripTrailing());
  }

  @Test
  void aRecordReadsAsOneRowFromItsFirstLineDuringAndAfterTheScan() throws Exception {
    open(String.format(EVENT, "one") + String.format(EVENT, "two"));
    ColumnSchema schema = ColumnSchema.fixed(ColumnFormat.LOG4J_XML);
    ColumnLayout layout = layout(schema, 12, 1, 4);
    RecordLines scanning = RecordLines.starts(new SortedLines(new long[] {0}), 3);
    ColumnRead partial = new ColumnRead(layout, UTC, PLAIN, scanning, NONE);
    LineBlock during = rows(partial, new long[] {0, 3, 4}, null);
    assertEquals("INFO        mock one    ", during.text(0));
    assertFalse(during.text(1).contains("two"));
    assertTrue(during.unparsed(2));

    RecordLines scanned = RecordLines.starts(new SortedLines(new long[] {0, 3}), 6);
    ColumnRead whole = new ColumnRead(layout, UTC, PLAIN, scanned, NONE);
    LineBlock after = rows(whole, new long[] {0, 3}, null);
    assertEquals("INFO        mock two    ", after.text(1));
    assertEquals(3, after.sourceLine(1));

    LineBlock continuation = rows(whole, new long[] {4}, null);
    assertEquals("INFO        mock two    ", continuation.text(0));
    assertEquals(4, continuation.sourceLine(0));
  }

  @Test
  void aRecordAtTheLineBoundShowsTheCutMarker() throws Exception {
    StringBuilder text = new StringBuilder("<log4j:event timestamp=\"1\" level=\"WARN\">\n");
    for (int i = 1; i < RecordScan.MAX_RECORD_LINES; i++) {
      text.append("x\n");
    }
    text.append(String.format(EVENT, "next"));
    open(text.toString());
    ColumnLayout layout = layout(ColumnSchema.fixed(ColumnFormat.LOG4J_XML), 12, 1);
    RecordLines records =
        RecordLines.starts(new SortedLines(new long[] {0, RecordScan.MAX_RECORD_LINES}), 10_003);
    LineBlock block =
        rows(
            new ColumnRead(layout, UTC, PLAIN, records, NONE),
            new long[] {0, RecordScan.MAX_RECORD_LINES},
            null);
    assertTrue(block.cut(0));
    assertFalse(block.cut(1));
  }

  @Test
  void repeatedHeaderLinesAreHeaderRows() throws Exception {
    open("a,b\n1,2\na,b\n3,4\n");
    ColumnSchema schema = ColumnSchema.delimited(ColumnFormat.CSV, ',', List.of("a", "b"), true);
    ColumnRead view =
        new ColumnRead(
            layout(schema, 4, 0, 1), UTC, PLAIN, null, new SortedLines(new long[] {0, 2}));
    LineBlock block = all(view);
    assertTrue(block.header(0));
    assertFalse(block.header(1));
    assertTrue(block.header(2));
    assertEquals("a   b   ", block.text(2));
  }

  @Test
  void frozenCellsStayAndTheRestScroll() throws Exception {
    open("1,22,333\n");
    ColumnSchema schema =
        ColumnSchema.delimited(ColumnFormat.CSV, ',', List.of("a", "b", "c"), false);
    ColumnLayout layout = new ColumnLayout(schema, new int[] {0, 1, 2}, new int[] {3, 5, 5}, 1);
    LineBlock block =
        new CellReader(document.newReader())
            .readRows(document.index(), 0, new long[] {0}, 1, 5, 5, view(layout), null, false);
    assertEquals(3, block.frozenColumns());
    assertEquals("1  333  ", block.text(0));
  }

  @Test
  void spanRulesColorInsideCellsAndAColumnRuleOnlyItsColumn() throws Exception {
    open("ab,b,xb\n");
    ColumnSchema schema =
        ColumnSchema.delimited(ColumnFormat.CSV, ',', List.of("one", "two", "three"), true);
    ColumnRead view = view(layout(schema, 5, 0, 1, 2));
    Highlighter any =
        Highlighter.of(List.of(HighlightRule.matchOnly("b", MatchMode.LITERAL, RED, null)));
    HighlightSpans spans = rows(view, new long[] {0}, any).spans(0);
    assertEquals(3, spans.count());
    assertEquals(1, spans.start(0));
    assertEquals(5, spans.start(1));
    assertEquals(11, spans.start(2));

    Highlighter named =
        Highlighter.of(
            List.of(
                HighlightRule.matchOnly("b", MatchMode.LITERAL, RED, null).withColumn("Three")));
    HighlightSpans only = rows(view, new long[] {0}, named).spans(0);
    assertEquals(1, only.count());
    assertEquals(11, only.start(0));

    Highlighter missing =
        Highlighter.of(
            List.of(HighlightRule.matchOnly("b", MatchMode.LITERAL, RED, null).withColumn("four")));
    assertTrue(rows(view, new long[] {0}, missing).spans(0).isEmpty());
  }

  @Test
  void wholeLineRulesTestTheRawRowOrTheNamedColumn() throws Exception {
    open("ERROR,x\nINFO,ERROR\n");
    ColumnSchema schema = ColumnSchema.delimited(ColumnFormat.CSV, ',', List.of("l", "m"), true);
    ColumnRead view = view(layout(schema, 8, 0, 1));
    HighlightRule line =
        new HighlightRule("ERROR", MatchMode.LITERAL, true, true, RED, null, 255, 0, true);
    LineBlock raw = rows(view, new long[] {0, 1}, Highlighter.of(List.of(line)));
    assertTrue(raw.spans(0).lineForeground() != 0);
    assertTrue(raw.spans(1).lineForeground() != 0);
    LineBlock named = rows(view, new long[] {0, 1}, Highlighter.of(List.of(line.withColumn("l"))));
    assertTrue(named.spans(0).lineForeground() != 0);
    assertEquals(0, named.spans(1).lineForeground());
  }

  @Test
  void cellTokensMoveWithTheirCell() throws Exception {
    open("x,ERROR\n");
    ColumnSchema schema = ColumnSchema.delimited(ColumnFormat.CSV, ',', List.of("a", "b"), true);
    LineBlock block =
        new CellReader(document.newReader())
            .readRows(
                document.index(),
                0,
                new long[] {0},
                1,
                0,
                40,
                view(layout(schema, 4, 0, 1)),
                null,
                true);
    TokenSpans tokens = block.tokens(0);
    assertEquals(1, tokens.count());
    assertEquals(4, tokens.start(0));
    assertEquals(7, tokens.end(0));
  }

  @Test
  void levelCellsTakeTheirLevelsColorWhateverTheCase() throws Exception {
    String[] levels = {"Verbose", "Debug", "Information", "Warning", "Error", "Fatal", "Other"};
    TokenKind[] kinds = {
      TokenKind.COMMENT,
      TokenKind.KEYWORD2,
      TokenKind.TYPE,
      TokenKind.ESCAPE,
      TokenKind.INVALID,
      TokenKind.INVALID,
      null
    };
    StringBuilder clef = new StringBuilder();
    for (String level : levels) {
      clef.append("{\"@t\":\"2026-11-01T12:00:00Z\",\"@l\":\"").append(level).append("\"}\n");
    }
    open(clef.toString());
    assertLevels(ColumnSchema.fixed(ColumnFormat.CLEF), kinds, levels.length);
    String[] log4j = {"TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL", "OTHER"};
    StringBuilder xml = new StringBuilder();
    for (String level : log4j) {
      xml.append("<log4j:event logger=\"mock\" timestamp=\"1764237600000\" level=\"")
          .append(level)
          .append("\"></log4j:event>\n");
    }
    open(xml.toString());
    assertLevels(ColumnSchema.fixed(ColumnFormat.LOG4J_XML), kinds, log4j.length);
  }

  /** Reads the Level cell alone and checks each row's one span, or none. */
  private void assertLevels(ColumnSchema schema, TokenKind[] kinds, int rows) throws Exception {
    long[] lines = new long[rows];
    for (int i = 0; i < rows; i++) {
      lines[i] = i;
    }
    LineBlock block =
        new CellReader(document.newReader())
            .readRows(
                document.index(),
                0,
                lines,
                rows,
                0,
                40,
                view(new ColumnLayout(schema, new int[] {1}, new int[] {14}, 0)),
                null,
                true);
    for (int i = 0; i < rows; i++) {
      TokenSpans tokens = block.tokens(i);
      if (kinds[i] == null) {
        assertEquals(0, tokens.count(), block.text(i));
      } else {
        assertEquals(1, tokens.count(), block.text(i));
        assertEquals(kinds[i], tokens.kind(0), block.text(i));
        assertEquals(0, tokens.start(0));
        assertEquals(block.text(i).strip().length(), tokens.end(0));
      }
    }
  }

  @Test
  void theCacheKeepsARowUntilItsRecordOrTheReadChanges() throws Exception {
    open("1,2\n3,4\n");
    ColumnSchema schema = ColumnSchema.delimited(ColumnFormat.CSV, ',', List.of("a", "b"), true);
    ColumnRead view = view(layout(schema, 4, 0, 1));
    CellReader reader = new CellReader(document.newReader());
    LineBlock first =
        reader.readRows(document.index(), 0, new long[] {0, 1}, 2, 0, 40, view, null, false);
    LineBlock again =
        reader.readRows(document.index(), 0, new long[] {0, 1}, 2, 0, 40, view, null, false);
    assertSame(first.text(1), again.text(1));
    LineBlock moved =
        reader.readRows(document.index(), 0, new long[] {0, 1}, 2, 1, 40, view, null, false);
    assertEquals(first.text(1).substring(1), moved.text(1));
  }

  @Test
  void aRecordReadReturnsTheRawTextAndValues() throws Exception {
    open(String.format(EVENT, "one"));
    ColumnLayout layout = layout(ColumnSchema.fixed(ColumnFormat.LOG4J_XML), 12, 4);
    RecordLines records = RecordLines.starts(new SortedLines(new long[] {0}), 3);
    CellRecord record =
        new CellReader(document.newReader())
            .record(document.index(), 2, new ColumnRead(layout, UTC, PLAIN, records, NONE));
    assertEquals(0, record.first());
    assertEquals(3, record.end());
    assertEquals(String.format(EVENT, "one").stripTrailing(), record.text().toString());
    assertEquals("mock one", record.value(4).toString());
    assertEquals("2026-11-01 12:00:00.000", record.value(0).toString());
  }
}
