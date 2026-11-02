package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MarkToggleTest {
  @Test
  void marksThenUnmarksAContiguousRange() {
    MutableLineSet marks = new MutableLineSet();
    marks.add(5);
    RowCursor all = RowCursor.all(1_000_000);
    MarkToggle.Result first = MarkToggle.run(marks, all, 0, 300_000, new CancellationToken(), null);
    assertTrue(first.marked());
    assertEquals(299_999, first.changed());
    assertEquals(300_000, marks.size());
    MarkToggle.Result second =
        MarkToggle.run(marks, all, 0, 300_000, new CancellationToken(), null);
    assertFalse(second.marked());
    assertEquals(300_000, second.changed());
    assertEquals(0, marks.size());
  }

  @Test
  void walksListedRowsOnly() {
    MutableLineSet matches = new MutableLineSet();
    for (long line = 0; line < 50_000; line += 10) {
      matches.add(line);
    }
    MutableLineSet marks = new MutableLineSet();
    RowCursor rows = RowCursor.of(matches, null);
    MarkToggle.Result result =
        MarkToggle.run(marks, rows, 100, 4500, new CancellationToken(), null);
    assertTrue(result.marked());
    assertEquals(4500, marks.size());
    assertEquals(100, marks.select(0));
    assertEquals(100 + 10 * 4499, marks.select(4499));
    assertFalse(marks.contains(101));
  }

  @Test
  void unmarksInTheMarksViewWhileWalkingIt() {
    MutableLineSet marks = new MutableLineSet();
    for (long line = 0; line < 20_000; line += 2) {
      marks.add(line);
    }
    MarkToggle.Result result =
        MarkToggle.run(marks, RowCursor.of(marks, null), 2, 9000, new CancellationToken(), null);
    assertFalse(result.marked());
    assertEquals(9000, result.changed());
    assertEquals(1000, marks.size());
    assertTrue(marks.contains(0));
    assertTrue(marks.contains(18_002));
  }

  @Test
  void cancelKeepsWhatWasDoneAndReportsProgress() {
    MutableLineSet marks = new MutableLineSet();
    final CancellationToken token = new CancellationToken();
    final AtomicLong last = new AtomicLong();
    MarkToggle.Result result =
        MarkToggle.run(
            marks,
            RowCursor.all(10_000_000),
            0,
            10_000_000,
            token,
            new MarkToggle.Progress() {
              @Override
              public void progressed(long done, long total) {
                last.set(done);
                if (done >= total / 2 + 3 * MarkToggle.BATCH) {
                  token.cancel();
                }
              }
            });
    assertTrue(result.canceled());
    assertTrue(result.changed() > 0 && result.changed() < 10_000_000);
    assertEquals(result.changed(), marks.size());
    assertTrue(last.get() > 0);
  }

  @Test
  void stopsAtTheMemoryShare() {
    MemoryBudget budget = MemoryBudget.of(1L << 20);
    MutableLineSet marks = new MutableLineSet(budget);
    MarkToggle.Result result =
        MarkToggle.run(marks, RowCursor.all(1L << 32), 0, 1L << 32, new CancellationToken(), null);
    assertTrue(result.memoryLimit());
    assertTrue(marks.size() > 0);
    assertTrue(budget.reserved(MemoryShare.RESULTS) <= budget.limit(MemoryShare.RESULTS));
  }
}
