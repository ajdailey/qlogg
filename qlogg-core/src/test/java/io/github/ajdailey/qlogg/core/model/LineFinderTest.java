package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** Quick find over a real document, and the filtered view's row reads. */
class LineFinderTest {
  @TempDir Path dir;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Workers workers;
  private LogDocument document;
  private LineReader reader;
  private LineIndex index;

  @BeforeEach
  void open() throws Exception {
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
    Path file = dir.resolve("find.log");
    Files.writeString(
        file, "alpha beta\n\tbeta x beta\r\nnone\ngamma\nbeta", StandardCharsets.UTF_8);
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
    reader = document.newReader();
    index = document.index();
  }

  @AfterEach
  void close() {
    assertTrue(document.close(LogDocument.CLOSE_BOUND));
    assertTrue(workers.close(Workers.CLOSE_BOUND));
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private LineFinder.Match find(
      RowCursor rows, String pattern, long line, long column, boolean forward) throws IOException {
    return LineFinder.find(
        reader,
        index,
        rows,
        Pattern.compile(pattern),
        line,
        column,
        forward,
        new CancellationToken());
  }

  @Test
  void forwardWalksMatchesAcrossLinesInColumns() throws IOException {
    RowCursor all = RowCursor.all(index.lineCount());
    assertEquals(new LineFinder.Match(0, 6, 10), find(all, "beta", 0, 0, true));
    assertEquals(new LineFinder.Match(1, 8, 12), find(all, "beta", 0, 10, true));
    assertEquals(new LineFinder.Match(1, 15, 19), find(all, "beta", 1, 12, true));
    assertEquals(new LineFinder.Match(4, 0, 4), find(all, "beta", 1, 19, true));
    assertNull(find(all, "beta", 4, 4, true));
    assertNull(find(all, "zeta", -1, 0, true));
    assertEquals(new LineFinder.Match(1, 13, 14), find(all, "x|$", 1, 0, true));
    assertNull(find(all, "\\r", -1, 0, true));
  }

  @Test
  void backwardFindsTheLastMatchBeforeThePosition() throws IOException {
    RowCursor all = RowCursor.all(index.lineCount());
    assertEquals(new LineFinder.Match(1, 15, 19), find(all, "beta", 4, 0, false));
    assertEquals(new LineFinder.Match(1, 8, 12), find(all, "beta", 1, 15, false));
    assertEquals(new LineFinder.Match(0, 6, 10), find(all, "beta", 1, 8, false));
    assertNull(find(all, "beta", 0, 6, false));
    assertEquals(new LineFinder.Match(4, 0, 4), find(all, "beta", -1, 0, false));
  }

  @Test
  void filteredRowsLimitTheLinesVisited() throws IOException {
    MutableLineSet rows = new MutableLineSet();
    rows.add(2);
    rows.add(4);
    RowCursor cursor = RowCursor.of(rows, null);
    assertEquals(new LineFinder.Match(4, 0, 4), find(cursor, "beta", 2, 0, true));
    assertNull(find(cursor, "beta", 2, 0, false));
    LineBlock block = reader.readRows(index, 7, new long[] {1, 4, 99}, 3, 4, 6);
    assertEquals(2, block.count());
    assertEquals(7, block.firstLine());
    assertEquals(1, block.sourceLine(7));
    assertEquals(4, block.sourceLine(8));
    assertEquals("    be", block.text(7));
    assertEquals(19, block.width(7));
    assertEquals("", block.text(8));
  }

  @Test
  void cancelStopsTheScan() {
    final CancellationToken token = new CancellationToken();
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            LineFinder.find(
                reader,
                index,
                RowCursor.all(index.lineCount()),
                Pattern.compile("(a|a)*b"),
                0,
                0,
                true,
                token);
          }
        });
  }
}
