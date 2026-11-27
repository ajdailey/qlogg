package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.search.RecordLines;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link RecordRows} against a list of record starts built line by line, for start and continuation
 * sets, line 0 in the start set or not, and unclassified lines past the set's end.
 */
class RecordRowsTest {
  private static final int LINES = 3000;

  @Test
  void rowsEqualTheRecordStartsForRandomSets() {
    Random random = new Random(3);
    double[] densities = {0, 0.01, 0.3, 0.9, 1};
    for (double density : densities) {
      for (boolean continuations : new boolean[] {false, true}) {
        for (long end : new long[] {0, 1, 1700, LINES}) {
          MutableLineSet set = new MutableLineSet();
          for (long line = 0; line < LINES + 50; line++) {
            if (random.nextDouble() < density
                && (continuations ? line > 0 : random.nextBoolean())) {
              set.add(line);
            }
          }
          RecordLines records =
              continuations ? RecordLines.continuations(set, end) : RecordLines.starts(set, end);
          assertRows(records, density + " " + continuations + " " + end);
        }
      }
    }
  }

  private static void assertRows(RecordLines records, String message) {
    List<Long> starts = new ArrayList<Long>();
    boolean[] isStart = new boolean[LINES];
    for (int line = 0; line < LINES; line++) {
      isStart[line] =
          line == 0
              || line >= records.end()
              || records.lines().contains(line) != records.continuations();
      if (isStart[line]) {
        starts.add((long) line);
      }
    }
    RecordRows rows = new RecordRows(records);
    assertEquals(starts.size(), rows.rowCount(LINES), message);
    long row = -1;
    for (long line = 0; line < LINES; line++) {
      if (isStart[(int) line]) {
        row++;
        assertEquals(line, rows.lineAt(row), message + " row " + row);
      }
      assertEquals(row, rows.rowOf(line), message + " line " + line);
      long end = row + 1 < starts.size() ? starts.get((int) row + 1) : LINES;
      assertEquals(end, rows.recordEnd(line, LINES), message + " end of " + line);
    }
    RowCursor cursor = rows.cursor(LINES);
    assertFalse(cursor.contiguous());
    long line = -1;
    for (long start : starts) {
      line = cursor.next(line);
      assertEquals(start, line, message);
      assertEquals(start, cursor.previous(start + 1), message);
    }
    assertEquals(-1, cursor.next(line), message);
    assertEquals(-1, cursor.previous(0), message);
  }
}
