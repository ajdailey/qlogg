package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.io.FileByteSource;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Shuts the application down while searches, quick finds, reads, indexing and watches run, at
 * random points, and checks that nothing outlives {@code Workers.close()}: no thread, no open file,
 * no reservation. Each shutdown ends within the document close bound, so no document waited out its
 * bound and closed its file under a running operation.
 */
class ShutdownLifecycleTest {
  private static final SearchOptions REGEX =
      new SearchOptions(MatchMode.REGEX, true, false, false, false, false, true, false);

  @TempDir Path temp;

  @Test
  void shutdownDuringSearchAndQuickFindLeavesNothingRunning() throws Exception {
    SplittableRandom random = new SplittableRandom(7);
    List<Long> shutdownMillis = new ArrayList<Long>();
    for (int round = 0; round < 12; round++) {
      final ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
      Path big = fixture.lines("big-" + round + ".log", 200_000);
      Path small = fixture.lines("small-" + round + ".log", 500);
      List<FileByteSource> sources = new ArrayList<FileByteSource>();
      try {
        final DocumentController document = fixture.open(big);
        sources.add((FileByteSource) document.document().bytes());
        fixture.application.open(List.of(new FileRequest(small, 0)));
        document.search().run("hit 1[0-9]*7$", REGEX);
        if (random.nextBoolean()) {
          fixture.application.activate(document);
          fixture.application.quickFind().open(true);
          fixture.application.quickFind().setText("line 199999");
        }
        int drains = random.nextInt(0, 6);
        for (int i = 0; i < drains; i++) {
          fixture.ui.runPending();
        }
        for (DocumentController open : fixture.application.documents()) {
          FileByteSource source = (FileByteSource) open.document().bytes();
          if (!sources.contains(source)) {
            sources.add(source);
          }
        }
      } finally {
        long started = System.nanoTime();
        fixture.close();
        shutdownMillis.add((System.nanoTime() - started) / 1_000_000L);
      }
      assertEquals(0, fixture.workers.liveThreads());
      for (FileByteSource source : sources) {
        assertFalse(source.isOpen(), source.path().toString());
      }
      assertEquals(0, fixture.budget.reserved(MemoryShare.RESULTS));
      assertEquals(0, fixture.budget.reserved(MemoryShare.LINE_INDEX));
      assertEquals(0, fixture.budget.reserved(MemoryShare.HIGHLIGHT));
      assertEquals(0, fixture.view.active.get());
    }
    assertTrue(noQloggThreadAlive().getAsBoolean(), "a qlogg thread is still alive");
    for (long millis : shutdownMillis) {
      assertTrue(millis < LogDocument.CLOSE_BOUND.toMillis(), shutdownMillis.toString());
    }
  }

  /** Platform threads only: a virtual thread does not appear in {@code getAllStackTraces}. */
  private static BooleanSupplier noQloggThreadAlive() {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
          if (thread.getName().startsWith("qlogg-") && thread.isAlive()) {
            return false;
          }
        }
        return true;
      }
    };
  }
}
