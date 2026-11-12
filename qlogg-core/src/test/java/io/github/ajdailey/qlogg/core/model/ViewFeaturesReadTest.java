package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.github.ajdailey.qlogg.core.io.BlockCache;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Fold classification, column windows and block columns, whitespace markers and hidden ANSI colors
 * over files.
 */
class ViewFeaturesReadTest {
  /** U+1F600, two chars at display column 2 of {@code "ab" + EMOJI + "c"}. */
  private static final String EMOJI = new String(Character.toChars(0x1F600));

  @TempDir Path dir;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Workers workers;
  private LogDocument document;

  private LogDocument open(String content) throws Exception {
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
    Path file = dir.resolve("view.log");
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
    return document;
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

  private long[] entries(long from, long to) throws IOException {
    return FoldClassifier.entries(
        document.newReader(),
        document.index(),
        FoldRules.DEFAULTS.compile(),
        from,
        to,
        new CancellationToken());
  }

  @Test
  void classifierFindsEntriesFromInsideAndAboveTheRegion() throws Exception {
    open(
        "start\n"
            + "Exception: boom\n"
            + "\tat a.B.c(B.java:1)\n"
            + "\tat a.B.d(B.java:2)\n"
            + "Caused by: x\n"
            + "... 3 more\n"
            + "single\n"
            + "next\n"
            + "  indented\n");
    assertArrayEquals(new long[] {1, 6, 7, 9}, entries(0, 9));
    assertArrayEquals(new long[] {1, 6}, entries(3, 4));
    assertArrayEquals(new long[0], entries(6, 7));
  }

  @Test
  void anEntryStartPatternMakesEveryOtherLineAContinuation() throws Exception {
    open("2026-01-01 a\nfree text\nmore\n2026-01-02 b\n2026-01-03 c\ntail\n");
    FoldRules rules = new FoldRules("^\\d{4}-\\d{2}-\\d{2}", List.of());
    long[] found =
        FoldClassifier.entries(
            document.newReader(), document.index(), rules.compile(), 0, 6, new CancellationToken());
    assertArrayEquals(new long[] {0, 3, 4, 6}, found);
  }

  @Test
  void aCanceledClassificationThrows() throws Exception {
    open("a\n b\n c\n");
    final CancellationToken token = new CancellationToken();
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            FoldClassifier.entries(
                document.newReader(), document.index(), FoldRules.DEFAULTS.compile(), 0, 3, token);
          }
        });
  }

  /**
   * Regression: a fold rule ran with plain {@code find} and the token was checked only between
   * lines, so a backtracking rule kept its worker busy after cancel until the process ended.
   */
  @Test
  void aBacktrackingFoldRuleEndsOnCancel() throws Exception {
    open("start\n" + "a".repeat(60) + "\n");
    final FoldRules.Compiled rules = new FoldRules("(.*a){25}x", List.of()).compile();
    final CancellationToken token = new CancellationToken();
    token.cancel();
    final LineReader reader = document.newReader();
    final CountDownLatch done = new CountDownLatch(1);
    Thread classify =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                try {
                  FoldClassifier.entries(reader, document.index(), rules, 0, 2, token);
                } catch (IOException | CancellationException e) {
                  // Either ending is correct; only a run that does not end fails.
                } finally {
                  done.countDown();
                }
              }
            });
    classify.setDaemon(true);
    classify.start();
    assertTrue(done.await(5, TimeUnit.SECONDS), "classification still running 5 s after cancel");
  }

  /**
   * Regression: a fold rule that overflowed the regex engine's stack threw {@code
   * StackOverflowError} out of the classification. It now finds no match on that line.
   */
  @Test
  void aFoldRuleThatOverflowsTheStackFindsNoMatch() throws Exception {
    open("start\n" + "a".repeat(400_000) + "\n");
    long[] found =
        FoldClassifier.entries(
            document.newReader(),
            document.index(),
            new FoldRules("", List.of("(a|b)*c")).compile(),
            0,
            2,
            new CancellationToken());
    assertArrayEquals(new long[0], found);
  }

  /**
   * An entry-start rule that reaches its read bound (the first rule) or overflows the stack (the
   * second) on a line starts an entry there, so the line stays unfolded instead of folding into the
   * entry above.
   */
  @ParameterizedTest
  @CsvSource({"'(.*a){25}x', 60", "'(a|b)*c', 400000"})
  void anEntryStartRuleThatDoesNotFinishStartsAnEntry(String rule, int length) throws Exception {
    open("start\n" + "a".repeat(length) + "\n");
    long[] found =
        FoldClassifier.entries(
            document.newReader(),
            document.index(),
            new FoldRules(rule, List.of()).compile(),
            0,
            2,
            new CancellationToken());
    assertArrayEquals(new long[0], found);
  }

  /**
   * Regression: a low surrogate took the column after its high surrogate, so an edge there split
   * the pair: a copy ending after an emoji held a lone high surrogate, and a window starting there
   * drew a lone low one.
   */
  @Test
  void columnEdgesKeepSurrogatePairsWhole() throws Exception {
    open("ab" + EMOJI + "c\n");
    LineReader reader = document.newReader();
    StringBuilder out = new StringBuilder();
    reader.appendText(document.index(), 0, 2, 3, out);
    assertEquals(EMOJI, out.toString());
    out.setLength(0);
    reader.appendText(document.index(), 0, 3, 4, out);
    assertEquals("c", out.toString());
    assertEquals("ab" + EMOJI, reader.read(document.index(), 0, 1, 0, 3).text(0));
    assertEquals("c", reader.read(document.index(), 0, 1, 3, 1).text(0));
  }

  @Test
  void blockColumnsAndMarkedRowsKeepSurrogatePairsWhole() throws Exception {
    open("ab" + EMOJI + "c\n");
    LineReader reader = document.newReader();
    StringBuilder out = new StringBuilder();
    reader.appendColumns(document.index(), 0, 2, 3, out);
    assertEquals(EMOJI, out.toString());
    out.setLength(0);
    reader.appendColumns(document.index(), 0, 3, 4, out);
    assertEquals("c", out.toString());
    LineBlock left = reader.readRows(document.index(), 0, new long[] {0}, 1, 0, 3, null, true);
    assertEquals("ab" + EMOJI, left.text(0));
    assertEquals(left.text(0).length(), left.whitespace(0).length());
    LineBlock right = reader.readRows(document.index(), 0, new long[] {0}, 1, 3, 1, null, true);
    assertEquals("c", right.text(0));
    assertEquals(" ", right.whitespace(0));
  }

  @Test
  void blockColumnsCutTabsIntoSpaces() throws Exception {
    open("ab\tcd\n0123456789\n");
    LineReader reader = document.newReader();
    StringBuilder out = new StringBuilder();
    reader.appendColumns(document.index(), 0, 1, 5, out);
    assertEquals("b   ", out.toString());
    out.setLength(0);
    reader.appendColumns(document.index(), 0, 4, 10, out);
    assertEquals("    cd", out.toString());
    out.setLength(0);
    reader.appendColumns(document.index(), 1, 3, 6, out);
    assertEquals("345", out.toString());
  }

  @Test
  void markersShowTabsTrailingSpacesLoneCarriageReturnsAndTerminators() throws Exception {
    open("a\tb  \r\nx\ry\nlast");
    LineReader reader = document.newReader();
    LineBlock block =
        reader.readRows(document.index(), 0, new long[] {0, 1, 2}, 3, 0, 40, null, true);
    assertEquals("a       b  ", block.text(0));
    assertEquals(" T       SS", block.whitespace(0));
    assertEquals(LineTerminator.CRLF, block.terminator(0));
    assertEquals("x y", block.text(1));
    assertEquals(" R ", block.whitespace(1));
    assertEquals(LineTerminator.LF, block.terminator(1));
    assertEquals(LineTerminator.NONE, block.terminator(2));
    LineBlock plain = reader.readRows(document.index(), 0, new long[] {0}, 1, 0, 40, null);
    assertNull(plain.whitespace(0));
    assertNull(plain.terminator(0));
  }

  @Test
  void hiddenAnsiColorsLeaveDisplayAndCopy() throws Exception {
    open("\u001b[31mred\u001b[0m and \u001b[1;32mgreen\u001b[m\n");
    LineReader reader = document.newReader();
    assertTrue(reader.decodedLine(document.index(), 0).toString().contains("\u001b["));
    document.setHideAnsi(true);
    assertTrue(document.hideAnsi());
    assertEquals("red and green", reader.decodedLine(document.index(), 0).toString());
    StringBuilder out = new StringBuilder();
    document.newReader().appendText(document.index(), 0, 0, Long.MAX_VALUE, out);
    assertEquals("red and green", out.toString());
    assertFalse(out.toString().contains("\u001b"));
  }
}
