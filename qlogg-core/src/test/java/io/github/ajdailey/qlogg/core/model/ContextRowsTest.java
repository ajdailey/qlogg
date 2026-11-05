package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** {@link ContextRows} against a brute-force expansion, and {@link FilteredView} with context. */
class ContextRowsTest {
  private final MemoryBudget budget = MemoryBudget.of(1L << 26);

  /** A line count the test changes. */
  private static final class Lines implements ContextRows.LineCount {
    long count;

    Lines(long count) {
      this.count = count;
    }

    @Override
    public long get() {
      return count;
    }
  }

  private static List<Long> expected(LineSet matches, int before, int after, long count) {
    TreeSet<Long> lines = new TreeSet<Long>();
    for (long i = 0; i < matches.size(); i++) {
      long m = matches.select(i);
      for (long line = Math.max(0, m - before); line <= m + after && line < count; line++) {
        lines.add(line);
      }
    }
    return new ArrayList<Long>(lines);
  }

  private static void assertSameAs(List<Long> lines, LineSet rows, long count, String context) {
    assertEquals(lines.size(), rows.size(), context);
    for (int i = 0; i < lines.size(); i++) {
      assertEquals((long) lines.get(i), rows.select(i), context + " select " + i);
    }
    assertEquals(-1, rows.select(lines.size()), context);
    int k = 0;
    for (long x = 0; x <= count + 2; x++) {
      while (k < lines.size() && lines.get(k) < x) {
        k++;
      }
      assertEquals(k, rows.rank(x), context + " rank " + x);
      boolean listed = k < lines.size() && lines.get(k) == x;
      assertEquals(listed, rows.contains(x), context + " contains " + x);
      long ceiling = k < lines.size() ? lines.get(k) : -1;
      assertEquals(ceiling, rows.ceiling(x), context + " ceiling " + x);
      long floor = listed ? x : (k > 0 ? lines.get(k - 1) : -1);
      assertEquals(floor, rows.floor(x), context + " floor " + x);
    }
  }

  @Test
  void rowsMatchTheExpandedLinesForEveryDensity() {
    Random random = new Random(11);
    int[][] counts = {{0, 1}, {1, 0}, {2, 3}, {5, 5}, {40, 0}};
    int[] gaps = {1, 3, 9, 60};
    for (int gap : gaps) {
      MutableLineSet matches = new MutableLineSet();
      long line = random.nextInt(4);
      while (line < 3000) {
        matches.add(line);
        line += 1 + random.nextInt(gap);
      }
      for (int[] count : counts) {
        Lines lines = new Lines(3000);
        ContextRows rows =
            ContextRows.empty(matches, count[0], count[1], lines)
                .extend(budget, new CancellationToken());
        String context = "gap " + gap + " context " + count[0] + "/" + count[1];
        assertTrue(rows.complete(), context);
        assertFalse(rows.stale(), context);
        assertSameAs(expected(matches, count[0], count[1], 3000), rows, 3000, context);
        lines.count = 2990;
        assertSameAs(expected(matches, count[0], count[1], 2990), rows, 2990, context + " cut");
      }
    }
  }

  @Test
  void extendingContinuesFromTheCoveredMatches() {
    MutableLineSet matches = new MutableLineSet();
    for (long line = 0; line < 1000; line += 7) {
      matches.add(line);
    }
    Lines lines = new Lines(5000);
    ContextRows first =
        ContextRows.empty(matches, 2, 2, lines).extend(budget, new CancellationToken());
    for (long line = 1001; line < 3000; line += 5) {
      matches.add(line);
    }
    assertTrue(first.stale());
    ContextRows second = first.extend(budget, new CancellationToken());
    assertTrue(second.continues(first));
    assertEquals(second.reservedBytes() - first.reservedBytes(), second.reservedBeyond(first));
    assertSameAs(expected(matches, 2, 2, 5000), second, 5000, "extended");
    matches.removeFrom(2996);
    matches.add(2998);
    assertTrue(second.stale(), "the last covered match changed");
    ContextRows third = second.extend(budget, new CancellationToken());
    assertFalse(third.continues(second));
    assertEquals(third.reservedBytes(), third.reservedBeyond(second));
    assertSameAs(expected(matches, 2, 2, 5000), third, 5000, "rebuilt");
  }

  @Test
  void theLastMatchContextFollowsTheLineCount() {
    MutableLineSet matches = new MutableLineSet();
    matches.add(10);
    Lines lines = new Lines(11);
    ContextRows rows =
        ContextRows.empty(matches, 1, 3, lines).extend(budget, new CancellationToken());
    assertEquals(2, rows.size());
    lines.count = 13;
    assertEquals(4, rows.size());
    lines.count = 100;
    assertEquals(5, rows.size());
    assertEquals(13, rows.select(4));
  }

  @Test
  void checkpointsAreChargedAndStopAtARefusal() {
    MutableLineSet matches = new MutableLineSet();
    for (long line = 0; line < 200_000; line += 2) {
      matches.add(line);
    }
    MemoryBudget small = MemoryBudget.of(10_000);
    ContextRows rows =
        ContextRows.empty(matches, 1, 0, new Lines(200_000)).extend(small, new CancellationToken());
    assertFalse(rows.complete());
    assertFalse(rows.stale(), "a partial build is not retried");
    assertTrue(rows.covered() < matches.size());
    assertTrue(rows.covered() > 0);
    assertEquals(rows.reservedBytes(), small.reserved(MemoryShare.RESULTS));
    assertEquals(rows.covered() * 2 - 1, rows.size(), "rows cover the matches the budget held");
    small.release(MemoryShare.RESULTS, rows.reservedBytes());
  }

  @Test
  void theFilteredViewListsContextAndMarksGroups() {
    MutableLineSet matches = new MutableLineSet();
    matches.add(5);
    matches.add(6);
    matches.add(20);
    MutableLineSet marks = new MutableLineSet();
    marks.add(4);
    marks.add(40);
    FilteredView view = new FilteredView(matches, marks, budget);
    view.setContext(1, 1, new Lines(100));
    assertEquals(0, view.context().size(), "nothing listed before the build is installed");
    assertTrue(view.contextStale());
    ContextRows from = view.context();
    assertTrue(view.installContext(from.extend(budget, new CancellationToken()), from));
    assertFalse(view.contextStale());
    if (view.extrasStale()) {
      assertTrue(
          view.installExtras(
              FilteredView.buildExtras(view.rows(), marks, budget, new CancellationToken())));
    }
    List<Long> lines = new ArrayList<Long>();
    for (long row = 0; row < view.size(); row++) {
      lines.add(view.sourceLine(row));
    }
    assertEquals(List.of(4L, 5L, 6L, 7L, 19L, 20L, 21L, 40L), lines);
    assertTrue(view.isContext(4), "a marked context line shades as context");
    assertFalse(view.isContext(5));
    assertTrue(view.isContext(7));
    assertFalse(view.isContext(40), "a mark outside the context is not context");
    assertTrue(view.startsGroup(19));
    assertTrue(view.startsGroup(40));
    assertFalse(view.startsGroup(4), "the first row");
    assertFalse(view.startsGroup(5));
    view.setVisibility(Visibility.MATCHES);
    assertEquals(7, view.size());
    view.setVisibility(Visibility.MARKS);
    assertEquals(2, view.size());
    assertFalse(view.isContext(4));
    assertFalse(view.startsGroup(40));
    long reserved = budget.reserved(MemoryShare.RESULTS);
    assertTrue(reserved > 0);
    MutableLineSet other = new MutableLineSet();
    view.setMatches(other);
    assertSame(other, view.context().matches());
    view.close();
    assertEquals(0, budget.reserved(MemoryShare.RESULTS), "close returns every reservation");
  }

  @Test
  void aBuildFromReplacedRowsIsDropped() {
    MutableLineSet matches = new MutableLineSet();
    matches.add(3);
    FilteredView view = new FilteredView(matches, new MutableLineSet(), budget);
    view.setContext(2, 2, new Lines(10));
    ContextRows from = view.context();
    ContextRows built = from.extend(budget, new CancellationToken());
    view.setContext(1, 1, new Lines(10));
    assertFalse(view.installContext(built, from));
    view.close();
    assertEquals(0, budget.reserved(MemoryShare.RESULTS));
  }
}
