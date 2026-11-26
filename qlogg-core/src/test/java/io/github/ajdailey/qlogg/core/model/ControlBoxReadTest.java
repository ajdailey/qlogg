package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Control chars read as boxes: two spaces in the row text with the box in {@link
 * LineBlock#controls}, a box cut by the column window, columns after a box, copy keeping the char,
 * highlight columns over the box, hidden ANSI colors and whitespace markers.
 */
class ControlBoxReadTest {
  private static final char SECOND = LineBlock.SECOND_CELL;

  @TempDir Path dir;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Workers workers;
  private LogDocument document;

  private void open(String content) throws Exception {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    MemoryBudget budget = MemoryBudget.of(1L << 28);
    DocumentServices services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(2, budget)),
            new SearchEngine(
                workers, StandardMatchers.INSTANCE, SearchSettings.defaults(2, budget)),
            new BlockCache(65536, 1 << 20),
            budget,
            StandardCharsets.ISO_8859_1);
    Path file = dir.resolve("controls.log");
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

  private LineBlock rows(long firstColumn, int columns, boolean markers) throws IOException {
    long[] lines = new long[(int) document.index().lineCount()];
    for (int i = 0; i < lines.length; i++) {
      lines[i] = i;
    }
    return document
        .newReader()
        .readRows(document.index(), 0, lines, lines.length, firstColumn, columns, null, markers);
  }

  private String copy(long line, long from, long to) throws IOException {
    StringBuilder out = new StringBuilder();
    document.newReader().appendText(document.index(), line, from, to, out);
    return out.toString();
  }

  @Test
  void aBoxTakesTwoColumnsAsSpacesWithItsCharInTheCells() throws Exception {
    open("a\u0001b\u0085c\nplain\n");
    LineBlock block = rows(0, 40, false);
    assertEquals("a  b  c", block.text(0));
    assertEquals(
        " \u0001" + (char) (1 + SECOND) + " \u0085" + (char) (0x85 + SECOND) + " ",
        block.controls(0));
    assertEquals(7, block.width(0));
    assertNull(block.controls(1));
    LineBlock main = document.newReader().read(document.index(), 0, 2, 0, 40);
    assertEquals("a  b  c", main.text(0));
    assertEquals(block.controls(0), main.controls(0));
    assertNull(main.controls(1));
  }

  @Test
  void aWindowEdgeInsideABoxKeepsTheShownHalf() throws Exception {
    open("a\u001bb\n");
    LineBlock right = rows(2, 10, false);
    assertEquals(" b", right.text(0));
    assertEquals(String.valueOf((char) (0x1b + SECOND)) + " ", right.controls(0));
    LineBlock left = rows(0, 2, false);
    assertEquals("a ", left.text(0));
    assertEquals(" \u001b", left.controls(0));
  }

  @Test
  void copyKeepsTheCharAndColumnsAfterABoxCountTwo() throws Exception {
    open("a\u0001b\u007f\n");
    assertEquals("a\u0001b\u007f", copy(0, 0, Long.MAX_VALUE));
    assertEquals("\u0001", copy(0, 1, 3));
    assertEquals("b", copy(0, 3, 4));
    assertEquals("\u007f", copy(0, 4, 6));
    StringBuilder block = new StringBuilder();
    document.newReader().appendColumns(document.index(), 0, 1, 4, block);
    assertEquals("\u0001b", block.toString());
  }

  @Test
  void aHighlightOverABoxCoversBothColumns() throws Exception {
    open("a\u0001b\n");
    HighlightRule rule =
        HighlightRule.matchOnly("\u0001b", MatchMode.LITERAL, null, Rgb.of(0xd02020));
    HighlightSpans spans =
        Highlighter.of(List.of(rule))
            .matcher()
            .spans(document.newReader().decodedLine(document.index(), 0), 0, 40);
    assertEquals(1, spans.count());
    assertEquals(1, spans.start(0));
    assertEquals(4, spans.end(0));
  }

  @Test
  void hiddenAnsiColorsDrawNoBoxAndAnotherEscapeStays() throws Exception {
    open("\u001b[31mred\u001b[0m \u001b(B\n");
    LineBlock shown = rows(0, 40, false);
    assertEquals("  [31mred  [0m   (B", shown.text(0));
    assertEquals('\u001b', shown.controls(0).charAt(0));
    document.setHideAnsi(true);
    LineBlock hidden = rows(0, 40, false);
    assertEquals("red   (B", hidden.text(0));
    assertEquals("    \u001b" + (char) (0x1b + SECOND) + "  ", hidden.controls(0));
  }

  @Test
  void markersKeepTheirCellsAndALoneCarriageReturnShowsItsMarker() throws Exception {
    open("x\ry\u0001\tz\n");
    LineBlock marked = rows(0, 40, true);
    assertEquals("x  y    z", marked.text(0));
    assertEquals(" R    T  ", marked.whitespace(0));
    assertEquals("    \u0001" + (char) (1 + SECOND) + "   ", marked.controls(0));
    LineBlock plain = rows(0, 40, false);
    assertEquals("x  y    z", plain.text(0));
    assertEquals(
        " \r" + (char) ('\r' + SECOND) + " \u0001" + (char) (1 + SECOND) + "   ",
        plain.controls(0));
  }
}
