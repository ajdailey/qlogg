package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FilteredViewTest {
  private final MemoryBudget budget = MemoryBudget.of(1L << 26);

  private static MutableLineSet set(long... lines) {
    MutableLineSet set = new MutableLineSet();
    for (long line : lines) {
      set.add(line);
    }
    return set;
  }

  /** Builds and installs the marks snapshot, as the controller does on a worker. */
  private static FilteredView sync(FilteredView view) {
    if (view.extrasStale()) {
      assertTrue(
          view.installExtras(
              FilteredView.buildExtras(
                  view.matches(), view.marks(), view.budget(), new CancellationToken())));
    }
    return view;
  }

  private static List<Long> rows(FilteredView view) {
    sync(view);
    List<Long> lines = new ArrayList<Long>();
    for (long row = 0; row < view.size(); row++) {
      lines.add(view.sourceLine(row));
    }
    return lines;
  }

  @Test
  void rowsMapToSourceLinesInEachMode() {
    MutableLineSet matches = set(3, 10, 17, 70_000, 1L << 40);
    MutableLineSet marks = set(0, 10, 20, 70_001);
    FilteredView view = sync(new FilteredView(matches, marks, budget));
    assertEquals(Visibility.MARKS_AND_MATCHES, view.visibility());
    assertEquals(List.of(0L, 3L, 10L, 17L, 20L, 70_000L, 70_001L, 1L << 40), rows(view));
    assertEquals(-1, view.sourceLine(8));
    assertEquals(-1, view.sourceLine(-1));
    for (long row = 0; row < view.size(); row++) {
      assertEquals(row, view.rowOf(view.sourceLine(row)));
    }
    assertEquals(4, view.rowOf(18));
    assertEquals(8, view.rowOf((1L << 40) + 1));
    assertTrue(view.lists(20));
    assertFalse(view.lists(21));

    view.setVisibility(Visibility.MARKS);
    assertEquals(List.of(0L, 10L, 20L, 70_001L), rows(view));
    assertEquals(2, view.rowOf(11));
    assertFalse(view.lists(3));

    view.setVisibility(Visibility.MATCHES);
    assertEquals(List.of(3L, 10L, 17L, 70_000L, 1L << 40), rows(view));
    assertEquals(1, view.rowOf(4));
  }

  @Test
  void unionFollowsMarkTogglesAndGrowingMatches() {
    MutableLineSet matches = set(5, 9);
    MutableLineSet marks = set(1, 5);
    FilteredView view = new FilteredView(matches, marks, budget);
    assertEquals(List.of(1L, 5L, 9L), rows(view));
    marks.toggle(1);
    marks.toggle(7);
    assertEquals(List.of(5L, 7L, 9L), rows(view));
    matches.add(7);
    matches.add(12);
    assertEquals(List.of(5L, 7L, 9L, 12L), rows(view));
    view.setMatches(set(2));
    assertEquals(List.of(2L, 5L, 7L), rows(view));
  }

  @Test
  void consecutiveRowsAndTheCursorAgreeWithSelect() {
    MutableLineSet matches = new MutableLineSet();
    MutableLineSet marks = new MutableLineSet();
    for (long line = 0; line < 200_000; line += 3) {
      matches.add(line);
    }
    for (long line = 1; line < 200_000; line += 1_000) {
      marks.add(line);
    }
    FilteredView view = sync(new FilteredView(matches, marks, budget));
    long[] out = new long[50];
    long first = view.size() - 20;
    int n = view.sourceLines(first, out.length, out);
    assertEquals(20, n);
    for (int i = 0; i < n; i++) {
      assertEquals(view.sourceLine(first + i), out[i]);
    }
    RowCursor cursor = view.cursor();
    assertFalse(cursor.contiguous());
    assertEquals(view.sourceLine(0), cursor.next(-1));
    assertEquals(view.sourceLine(5), cursor.previous(view.sourceLine(6)));
    assertEquals(-1, cursor.next(view.sourceLine(view.size() - 1)));
  }

  @Test
  void extrasAreChargedAndReleased() {
    MutableLineSet matches = set(1);
    MutableLineSet marks = set(1, 2, 3);
    FilteredView view = new FilteredView(matches, marks, budget);
    assertEquals(1, view.size());
    assertTrue(view.extrasStale());
    sync(view);
    assertFalse(view.extrasStale());
    assertEquals(3, view.size());
    assertTrue(view.retainedBytes() > 0);
    assertEquals(view.retainedBytes(), budget.reserved(MemoryShare.RESULTS));
    view.close();
    assertEquals(0, view.retainedBytes());
    assertEquals(0, budget.reserved(MemoryShare.RESULTS));
  }

  @Test
  void snapshotStaysInPlaceUntilTheNextOneIsInstalled() {
    MutableLineSet matches = set(5);
    MutableLineSet marks = set(1);
    FilteredView view = sync(new FilteredView(matches, marks, budget));
    marks.toggle(7);
    assertTrue(view.extrasStale());
    assertEquals(2, view.size());
    assertEquals(-1, view.sourceLine(2));
    FilteredView.Extras stale =
        FilteredView.buildExtras(set(9), marks, budget, new CancellationToken());
    assertFalse(view.installExtras(stale));
    assertEquals(3, rows(view).size());
  }

  @Test
  void aPartialSnapshotWhenTheShareRefuses() {
    MemoryBudget small = MemoryBudget.of(100);
    MutableLineSet marks = new MutableLineSet();
    for (long line = 0; line < 100_000; line += 2) {
      marks.add(line);
    }
    FilteredView.Extras extras =
        FilteredView.buildExtras(new MutableLineSet(), marks, small, new CancellationToken());
    assertFalse(extras.complete());
    assertTrue(extras.lines().size() < marks.size());
    extras.lines().clear();
    assertEquals(0, small.reserved(MemoryShare.RESULTS));
  }

  @Test
  void visibilityCyclesBothWays() {
    assertEquals(Visibility.MARKS, Visibility.MARKS_AND_MATCHES.next());
    assertEquals(Visibility.MATCHES, Visibility.MARKS.next());
    assertEquals(Visibility.MARKS_AND_MATCHES, Visibility.MATCHES.next());
    assertEquals(Visibility.MATCHES, Visibility.MARKS_AND_MATCHES.previous());
  }

  @Test
  void everyLineCursor() {
    RowCursor all = RowCursor.all(3);
    assertTrue(all.contiguous());
    assertEquals(0, all.next(-1));
    assertEquals(2, all.next(1));
    assertEquals(-1, all.next(2));
    assertEquals(-1, all.previous(0));
    assertEquals(2, all.previous(10));
  }
}
