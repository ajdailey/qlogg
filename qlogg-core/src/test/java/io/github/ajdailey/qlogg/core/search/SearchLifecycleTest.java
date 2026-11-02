package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.io.FileByteSource;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import io.github.ajdailey.qlogg.core.testkit.SyntheticLog;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opens, indexes, searches and closes documents in a loop, canceling searches and closing documents
 * at random points, then checks that nothing outlives {@code Workers.close()}: no thread, no open
 * file, no reservation.
 */
class SearchLifecycleTest {
  private static final String[] PATTERNS = {"flagword", "BLOCKTERM", "worker-1[0-9]\\]", "#\\d+7$"};

  @TempDir Path dir;

  @Test
  void nothingOutlivesTheWorkers() throws Exception {
    Path file = dir.resolve("cycle.log");
    SyntheticLog.write(file, 6L << 20, 21, "\n");
    MemoryBudget budget = MemoryBudget.of(1L << 30);
    SplittableRandom random = new SplittableRandom(99);
    List<FileByteSource> sources = new ArrayList<FileByteSource>();
    List<OperationHandle> handles = new ArrayList<OperationHandle>();
    List<SearchFixture.Recording> recordings = new ArrayList<SearchFixture.Recording>();
    List<MutableLineSet> sets = new ArrayList<MutableLineSet>();
    SearchFixture fixture = new SearchFixture(budget);
    try {
      SearchEngine engine = fixture.engine(new SearchSettings(2000, 1 << 18, 6, 0, budget));
      for (int round = 0; round < 25; round++) {
        LogDocument document = LogDocument.open(file, null, fixture.services);
        sources.add((FileByteSource) document.bytes());
        document.startIndexing();
        int searches = random.nextInt(1, 4);
        List<OperationHandle> mine = new ArrayList<OperationHandle>();
        for (int s = 0; s < searches; s++) {
          String pattern = PATTERNS[random.nextInt(PATTERNS.length)];
          MatchMode mode = pattern.indexOf('\\') >= 0 ? MatchMode.REGEX : MatchMode.LITERAL;
          MutableLineSet set = new MutableLineSet(budget);
          SearchFixture.Recording recording = new SearchFixture.Recording();
          OperationHandle handle =
              engine.search(document, SearchQuery.of(pattern, mode), set, recording);
          if (random.nextInt(3) == 0) {
            handle.cancel();
          }
          sets.add(set);
          recordings.add(recording);
          mine.add(handle);
        }
        if (random.nextBoolean()) {
          for (OperationHandle handle : mine) {
            assertTrue(handle.await(SearchFixture.BOUND.toMillis()));
          }
        }
        assertTrue(document.close(LogDocument.CLOSE_BOUND));
        handles.addAll(mine);
      }
      for (OperationHandle handle : handles) {
        assertTrue(handle.await(SearchFixture.BOUND.toMillis()));
      }
    } finally {
      fixture.close();
    }
    for (SearchFixture.Recording recording : recordings) {
      assertEquals(0, recording.finished.getCount());
      assertNotEquals(
          OperationOutcome.FAILED, recording.outcome, String.valueOf(recording.failure));
    }
    for (FileByteSource source : sources) {
      assertFalse(source.isOpen());
    }
    // A pool reports terminated once its last worker leaves the run loop, just before the thread
    // ends, so the check polls.
    assertTrue(
        Poll.until(noLiveWorkerThreads(), SearchFixture.BOUND),
        String.valueOf(liveWorkerThreads()));
    for (MutableLineSet set : sets) {
      set.clear();
    }
    assertEquals(0, budget.reserved(MemoryShare.RESULTS));
    assertEquals(0, budget.reserved(MemoryShare.LINE_INDEX));
  }

  private static BooleanSupplier noLiveWorkerThreads() {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return liveWorkerThreads().isEmpty();
      }
    };
  }

  private static List<String> liveWorkerThreads() {
    List<String> names = new ArrayList<String>();
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.getName().startsWith("qlogg-") && thread.isAlive()) {
        names.add(thread.getName());
      }
    }
    return names;
  }
}
