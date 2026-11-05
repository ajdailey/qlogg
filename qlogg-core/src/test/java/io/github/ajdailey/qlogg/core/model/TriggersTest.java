package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Trigger scans over a real document. */
class TriggersTest {
  @TempDir Path dir;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Workers workers;
  private MemoryBudget budget;
  private DocumentServices services;
  private LogDocument document;
  private LineReader reader;
  private LineIndex index;
  private MutableLineSet marks;
  private MarkComments comments;
  private MutableLineSet quiet;

  @BeforeEach
  void open() {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    budget = MemoryBudget.of(1L << 28);
    services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(2, budget)),
            new SearchEngine(
                workers, StandardMatchers.INSTANCE, SearchSettings.defaults(2, budget)),
            new BlockCache(65536, 1 << 20),
            budget,
            StandardCharsets.ISO_8859_1);
    marks = new MutableLineSet(budget);
    comments = new MarkComments(budget);
    quiet = new MutableLineSet(budget);
  }

  @AfterEach
  void close() {
    if (document != null) {
      assertTrue(document.close(LogDocument.CLOSE_BOUND));
    }
    assertTrue(workers.close(Workers.CLOSE_BOUND));
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private void indexed(String text) throws IOException, InterruptedException {
    Path file = dir.resolve("app.log");
    Files.writeString(file, text, StandardCharsets.US_ASCII);
    index(file);
  }

  private void index(Path file) throws IOException, InterruptedException {
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

  private static HighlightRule rule(String pattern, RuleTrigger trigger) {
    return HighlightRule.matchOnly(pattern, MatchMode.LITERAL, null, Rgb.of(0xffff00))
        .withTrigger(trigger);
  }

  private Triggers.Batch scan(Triggers triggers, long from, long to) throws IOException {
    return triggers.scan(
        reader, index, document.path(), from, to, marks, comments, quiet, new CancellationToken());
  }

  @Test
  void onlyEnabledRulesWithAnActionAndAPatternAct() {
    RuleTrigger stop = new RuleTrigger(true, false, "", false);
    assertSame(Triggers.NONE, Triggers.of(List.of(rule("a", RuleTrigger.NONE))));
    assertSame(Triggers.NONE, Triggers.of(List.of(rule("a", stop).withEnabled(false))));
    assertSame(Triggers.NONE, Triggers.of(List.of(rule("", stop))));
    assertSame(
        Triggers.NONE,
        Triggers.of(
            List.of(HighlightRule.matchOnly("(", MatchMode.REGEX, null, null).withTrigger(stop))));
    assertFalse(Triggers.of(List.of(rule("a", stop))).quiet());
    assertTrue(Triggers.of(List.of(rule("a", new RuleTrigger(false, false, "", true)))).quiet());
  }

  @Test
  void everyMatchingRuleActsWhateverItsOrder() throws Exception {
    indexed("ok 0\nERROR disk 1\nheartbeat 2\nERROR heartbeat 3\nok 4\n");
    Triggers triggers =
        Triggers.of(
            List.of(
                rule("heartbeat", new RuleTrigger(false, false, "", true)),
                rule("ERROR", new RuleTrigger(false, true, "error at %L in %N", false)),
                rule("disk", new RuleTrigger(true, true, "disk at %L", false))));
    Triggers.Batch batch = scan(triggers, 1, 5);
    assertEquals(1, batch.from());
    assertEquals(5, batch.to());
    assertEquals("disk", batch.stopRule());
    assertEquals(1, batch.stopLine());
    assertEquals(2, batch.marked());
    assertEquals(List.of(1L, 3L), List.of(marks.select(0), marks.select(1)));
    assertEquals("error at 2 in app.log", comments.get(1), "the higher rule's comment wins");
    assertEquals("error at 4 in app.log", comments.get(3));
    assertEquals(2, batch.loudLines(), "lines 1 and 4");
    assertEquals(2, quiet.size());
    assertTrue(quiet.contains(2) && quiet.contains(3));
    assertFalse(batch.refused());
    assertEquals(List.of(), batch.failures());
  }

  @Test
  void anExistingCommentStays() throws Exception {
    indexed("ERROR 0\n");
    marks.add(0);
    comments.put(0, "mine");
    Triggers triggers =
        Triggers.of(List.of(rule("ERROR", new RuleTrigger(false, true, "trigger", false))));
    assertEquals(1, scan(triggers, 0, 1).marked());
    assertEquals("mine", comments.get(0));
  }

  @Test
  void aFailingTemplateKeepsItsTextAndReportsOnce() throws Exception {
    indexed("ERROR 0\nERROR 1\n");
    Triggers triggers =
        Triggers.of(List.of(rule("ERROR", new RuleTrigger(false, true, "x {E}{$2}", false))));
    Triggers.Batch first = scan(triggers, 0, 1);
    assertEquals(1, first.failures().size());
    assertTrue(first.failures().get(0).startsWith("ERROR: "), first.failures().toString());
    assertEquals("x {E}{$2}", comments.get(0));
    assertEquals(List.of(), scan(triggers, 1, 2).failures());
    assertEquals("x {E}{$2}", comments.get(1));
  }

  @Test
  void aCommentPastTheCapIsRefusedAndTheMarkStays() throws Exception {
    indexed("ERROR 0\n");
    for (int i = 0; i < MarkComments.MAX_COMMENTS; i++) {
      marks.add(1000 + i);
      assertTrue(comments.put(1000 + i, "c"));
    }
    Triggers triggers =
        Triggers.of(List.of(rule("ERROR", new RuleTrigger(false, true, "%L", false))));
    Triggers.Batch batch = scan(triggers, 0, 1);
    assertTrue(batch.refused());
    assertTrue(marks.contains(0));
    assertNull(comments.get(0));
  }

  @Test
  void marksTheBudgetRefusesAreNotAdded() throws Exception {
    indexed("ERROR 0\nERROR 1\n");
    marks = new MutableLineSet(MemoryBudget.of(1024));
    Triggers triggers =
        Triggers.of(List.of(rule("ERROR", new RuleTrigger(false, true, "c", false))));
    Triggers.Batch batch = scan(triggers, 0, 2);
    assertTrue(batch.refused());
    assertEquals(0, batch.marked());
    assertEquals(0, marks.size());
    assertEquals(0, comments.size());
  }

  @Test
  void aScanReadsOneBatchAtMostAndStopsWhenCanceled() throws Exception {
    Path file = dir.resolve("big.log");
    int lines = Triggers.BATCH_LINES + 10;
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.US_ASCII)) {
      for (int i = 0; i < lines; i++) {
        out.write("hit " + i + "\n");
      }
    }
    index(file);
    Triggers triggers = Triggers.of(List.of(rule("hit", new RuleTrigger(false, true, "", false))));
    Triggers.Batch batch = scan(triggers, 5, lines);
    assertEquals(5 + Triggers.BATCH_LINES, batch.to());
    assertEquals(Triggers.BATCH_LINES, batch.marked());
    CancellationToken canceled = new CancellationToken();
    canceled.cancel();
    Triggers.Batch none =
        triggers.scan(reader, index, null, 0, lines, marks, comments, quiet, canceled);
    assertEquals(0, none.to());
  }
}
