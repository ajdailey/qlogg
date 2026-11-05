package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkCommentsTest {
  private final MemoryBudget budget = MemoryBudget.of(1L << 26);

  private static List<Long> lines(MarkComments comments) {
    List<Long> out = new ArrayList<Long>();
    for (MarkComments.Comment comment : comments.list(0, Integer.MAX_VALUE)) {
      out.add(comment.line());
    }
    return out;
  }

  @Test
  void cleanReplacesControlCharsStripsAndCaps() {
    assertEquals("a b c", MarkComments.clean("  a\tb\nc\u0085 "));
    assertEquals("x y", MarkComments.clean("x y"));
    assertEquals("", MarkComments.clean(null));
    assertEquals("", MarkComments.clean("\r\n"));
    assertEquals(MarkComments.MAX_LENGTH, MarkComments.clean("z".repeat(500)).length());
    String pair = "😀";
    String cut = MarkComments.clean("y".repeat(MarkComments.MAX_LENGTH - 1) + pair);
    assertEquals(MarkComments.MAX_LENGTH - 1, cut.length());
  }

  @Test
  void putReplacesRemovesAndChargesTheResultsShare() {
    MarkComments comments = new MarkComments(budget);
    assertTrue(comments.put(4, "first note"));
    assertTrue(comments.put(2, "second"));
    assertEquals("first note", comments.get(4));
    assertNull(comments.get(3));
    long bytes = MarkComments.ENTRY_BYTES * 2 + 2L * ("first note".length() + "second".length());
    assertEquals(bytes, comments.retainedBytes());
    assertEquals(bytes, budget.reserved(MemoryShare.RESULTS));
    long version = comments.version();
    assertTrue(comments.put(4, "   "));
    assertNull(comments.get(4));
    assertTrue(comments.version() > version);
    assertEquals(
        List.of(new MarkComments.Comment(2, "second")), comments.list(0, Integer.MAX_VALUE));
    comments.clear();
    assertEquals(0, comments.size());
    assertEquals(0, budget.reserved(MemoryShare.RESULTS));
  }

  @Test
  void refusesPastTheCountAndTheBudget() {
    MarkComments comments = new MarkComments(budget);
    for (int i = 0; i < MarkComments.MAX_COMMENTS; i++) {
      assertTrue(comments.put(i, "n"));
    }
    assertFalse(comments.put(MarkComments.MAX_COMMENTS, "n"));
    assertTrue(comments.put(0, "replaced"));
    MarkComments tight = new MarkComments(MemoryBudget.of(400));
    assertFalse(tight.put(0, "refused"));
    assertEquals(0, tight.size());
  }

  @Test
  void removesByLinesAndFromALine() {
    MarkComments comments = new MarkComments(budget);
    for (long line = 0; line < 10; line++) {
      comments.put(line, "c" + line);
    }
    assertEquals(2, comments.removeAll(new long[] {7, 3, 42}, 3));
    comments.removeFrom(8);
    assertEquals(List.of(0L, 1L, 2L, 4L, 5L, 6L), lines(comments));
    assertEquals(2, comments.list(5, 10).size());
  }

  @Test
  void addAllKeepsCommentsOfMarkedLinesOnly() {
    MutableLineSet marks = new MutableLineSet();
    marks.add(1);
    MarkComments comments = new MarkComments(budget);
    assertTrue(
        comments.addAll(
            List.of(new MarkComments.Comment(1, "kept"), new MarkComments.Comment(2, "dropped")),
            marks));
    assertEquals("kept", comments.get(1));
    assertNull(comments.get(2));
  }

  @Test
  void unmarkingRemovesTheComments() {
    MutableLineSet marks = new MutableLineSet();
    MarkComments comments = new MarkComments(budget);
    RowCursor all = RowCursor.all(100);
    MarkToggle.run(marks, comments, all, 10, 5, new CancellationToken(), null);
    comments.put(12, "note");
    comments.put(40, "outside");
    MarkToggle.Result result =
        MarkToggle.run(marks, comments, all, 10, 5, new CancellationToken(), null);
    assertFalse(result.marked());
    assertNull(comments.get(12));
    assertEquals("outside", comments.get(40));
  }
}
