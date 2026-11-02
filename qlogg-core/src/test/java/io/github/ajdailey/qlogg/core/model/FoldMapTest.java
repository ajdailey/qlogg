package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import org.junit.jupiter.api.Test;

/** Row and line mapping of collapsed entries, and their charge. */
class FoldMapTest {
  private final MemoryBudget budget = MemoryBudget.of(1L << 24);

  @Test
  void collapsedEntriesMapRowsToLinesBothWays() {
    FoldMap map = new FoldMap(budget);
    assertTrue(map.collapse(2, 5));
    assertTrue(map.collapse(10, 20));
    assertEquals(2 + 9, map.hiddenLines());
    long[] rows = {0, 1, 2, 2, 2, 3, 4, 5, 6, 7, 8, 8, 8, 9};
    long[] lines = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 19, 20};
    for (int i = 0; i < lines.length; i++) {
      assertEquals(rows[i], map.rowOf(lines[i]), "line " + lines[i]);
    }
    long[] shown = {0, 1, 2, 5, 6, 7, 8, 9, 10, 20, 21};
    for (int row = 0; row < shown.length; row++) {
      assertEquals(shown[row], map.lineAt(row), "row " + row);
      assertEquals(row, map.rowOf(map.lineAt(row)));
    }
  }

  @Test
  void overlappingAndSingleLineEntriesAreRefused() {
    FoldMap map = new FoldMap(budget);
    assertTrue(map.collapse(5, 8));
    assertFalse(map.collapse(7, 9));
    assertFalse(map.collapse(3, 6));
    assertFalse(map.collapse(4, 5));
    assertTrue(map.collapse(8, 10));
    assertEquals(2, map.size());
  }

  @Test
  void expandContainingExpandsOnlyAHiddenLine() {
    FoldMap map = new FoldMap(budget);
    map.collapse(3, 7);
    assertFalse(map.expandContaining(3));
    assertFalse(map.expandContaining(7));
    assertArrayEquals(new long[] {3, 7}, map.collapsedEntry(5));
    assertTrue(map.expandContaining(5));
    assertNull(map.collapsedEntry(5));
    assertEquals(0, map.hiddenLines());
    assertEquals(5, map.rowOf(5));
  }

  @Test
  void cursorSkipsHiddenLinesAndKeepsItsSnapshot() {
    FoldMap map = new FoldMap(budget);
    map.collapse(1, 4);
    RowCursor cursor = map.cursor(6);
    map.clear();
    assertFalse(cursor.contiguous());
    assertEquals(0, cursor.next(-1));
    assertEquals(1, cursor.next(0));
    assertEquals(4, cursor.next(1));
    assertEquals(5, cursor.next(4));
    assertEquals(-1, cursor.next(5));
    assertEquals(1, cursor.previous(4));
    assertEquals(1, cursor.previous(3));
    assertEquals(0, cursor.previous(1));
    assertTrue(map.cursor(6).contiguous());
  }

  @Test
  void eachEntryIsChargedAndRefusedPastTheShare() {
    MemoryBudget small = MemoryBudget.of(1L << 20);
    long share = small.limit(MemoryShare.FOLDS);
    FoldMap map = new FoldMap(small);
    long entries = share / FoldMap.ENTRY_BYTES;
    for (long i = 0; i < entries; i++) {
      assertTrue(map.collapse(i * 3, i * 3 + 2));
    }
    assertEquals(entries * FoldMap.ENTRY_BYTES, map.retainedBytes());
    assertEquals(map.retainedBytes(), small.reserved(MemoryShare.FOLDS));
    assertFalse(map.collapse(entries * 3, entries * 3 + 2));
    map.expand(0);
    assertEquals(map.retainedBytes(), small.reserved(MemoryShare.FOLDS));
    map.clear();
    assertEquals(0, small.reserved(MemoryShare.FOLDS));
  }
}
