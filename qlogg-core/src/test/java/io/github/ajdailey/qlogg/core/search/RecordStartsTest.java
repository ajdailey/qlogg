package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** The record starts of a line set against a walk of the records, for both record forms. */
class RecordStartsTest {
  private static MutableLineSet set(long... lines) {
    MutableLineSet set = new MutableLineSet();
    for (long line : lines) {
      set.add(line);
    }
    return set;
  }

  /** Records over 40 lines, each 1 to 4 lines long; lines from 36 on are not classified. */
  private static long[] starts(Random random) {
    List<Long> found = new ArrayList<Long>();
    long line = 0;
    while (line < 36) {
      found.add(line);
      line += 1 + random.nextInt(4);
    }
    long[] out = new long[found.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = found.get(i);
    }
    return out;
  }

  /** Expected starts: the first line of each record holding a member, walked line by line. */
  private static List<Long> walk(RecordLines records, LineSet members, long lines) {
    List<Long> out = new ArrayList<Long>();
    for (long line = 0; line < lines; line++) {
      long start = records.startOf(line);
      if (members.contains(line) && (out.isEmpty() || out.get(out.size() - 1) != start)) {
        out.add(start);
      }
    }
    return out;
  }

  @Test
  void matchesAWalkOfTheRecordsInBothForms() {
    Random random = new Random(5);
    for (int round = 0; round < 200; round++) {
      long[] starts = starts(random);
      MutableLineSet startSet = set(starts);
      MutableLineSet continuations = new MutableLineSet();
      for (long line = 0; line < 36; line++) {
        if (!startSet.contains(line)) {
          continuations.add(line);
        }
      }
      MutableLineSet members = new MutableLineSet();
      for (long line = 0; line < 40; line++) {
        if (random.nextInt(4) == 0) {
          members.add(line);
        }
      }
      for (RecordLines records :
          new RecordLines[] {
            RecordLines.starts(startSet, 36), RecordLines.continuations(continuations, 36)
          }) {
        RecordStarts view = new RecordStarts(members, records);
        List<Long> want = walk(records, members, 40);
        assertEquals(want.size(), view.size());
        for (int i = 0; i < want.size(); i++) {
          assertEquals((long) want.get(i), view.select(i));
        }
        for (long line = -1; line <= 41; line++) {
          long ceiling = -1;
          long floor = -1;
          long rank = 0;
          for (long start : want) {
            if (start >= line && ceiling < 0) {
              ceiling = start;
            }
            if (start <= line) {
              floor = start;
            }
            if (start < line) {
              rank++;
            }
          }
          assertEquals(ceiling, view.ceiling(line), "ceiling " + line);
          assertEquals(floor, view.floor(line), "floor " + line);
          assertEquals(rank, view.rank(line), "rank " + line);
          assertEquals(want.contains(line), view.contains(line), "contains " + line);
        }
      }
    }
  }

  @Test
  void aContinuationLineMapsToItsRecordsFirstLine() {
    RecordLines records = RecordLines.starts(set(0, 3, 7), 10);
    final RecordStarts view = new RecordStarts(set(5, 6, 8), records);
    assertEquals(3, view.ceiling(0));
    assertEquals(3, view.floor(6));
    assertTrue(view.contains(3));
    assertFalse(view.contains(5));
    assertEquals(7, view.ceiling(4));
    assertEquals(2, view.size());
    assertThrows(
        IndexOutOfBoundsException.class,
        new Executable() {
          @Override
          public void execute() {
            view.select(2);
          }
        });
  }
}
