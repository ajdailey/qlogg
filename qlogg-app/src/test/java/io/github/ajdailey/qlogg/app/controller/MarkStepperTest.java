package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import java.nio.file.Path;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Mark steps in a filter tab on the UI thread and on a worker: the same line for each mark layout,
 * a step from one state of the marks while they change, a new request replacing a running one, and
 * close and Stop canceling it.
 */
class MarkStepperTest {
  private static final SearchOptions LITERAL =
      new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, true, false);

  /** File lines; line {@code i} is a row of the {@code hit} tab when {@code i % 7 == 3}. */
  private static final int LINES = 700;

  @TempDir Path temp;

  private ControllerFixture fixture;

  @AfterEach
  void close() throws InterruptedException {
    if (fixture != null) {
      fixture.close();
    }
  }

  /** Opens the file and a filter tab of its {@code hit} lines. */
  private DocumentController tab() throws Exception {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    final DocumentController root = fixture.open(fixture.lines("tab.log", LINES));
    root.search().run("hit", LITERAL);
    fixture.runUntilEnded(root.search());
    fixture.application.filterToTab(root);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = fixture.application.activeDocument();
            return shown != root
                && !shown.ownsDocument()
                && shown.viewport().lineCount() == LINES / 7;
          }
        });
    DocumentController tab = fixture.application.activeDocument();
    tab.viewportResized(10, 80);
    return tab;
  }

  private static boolean row(long line) {
    return line % 7 == 3;
  }

  /** Every second line, marked rows among them. */
  private static NavigableSet<Long> dense() {
    NavigableSet<Long> marks = new TreeSet<Long>();
    for (long line = 1; line < LINES; line += 2) {
      marks.add(line);
    }
    return marks;
  }

  /** A few marks, rows and other lines. */
  private static NavigableSet<Long> sparse() {
    return new TreeSet<Long>(List.of(5L, 10L, 52L, 300L, 301L, 640L));
  }

  /** The first and last rows and lines outside the rows' span: no step goes round the ends. */
  private static NavigableSet<Long> ends() {
    return new TreeSet<Long>(List.of(0L, 1L, 3L, 696L, 698L, 699L));
  }

  /** Every line but the rows, and one row in the middle. */
  private static NavigableSet<Long> mostlyNotRows() {
    NavigableSet<Long> marks = new TreeSet<Long>();
    for (long line = 0; line < LINES; line++) {
      if (!row(line) || line == 346) {
        marks.add(line);
      }
    }
    return marks;
  }

  private static long expected(NavigableSet<Long> marks, long caret, boolean forward) {
    Long line = forward ? marks.higher(caret) : marks.lower(caret);
    while (line != null && !row(line)) {
      line = forward ? marks.higher(line) : marks.lower(line);
    }
    return line == null ? -1 : line;
  }

  /** Steps from every row both ways on the UI thread and on a worker against the oracle. */
  private void stepsMatch(NavigableSet<Long> layout) throws Exception {
    final DocumentController tab = tab();
    MutableLineSet marks = tab.document().marks();
    for (long line : layout) {
      marks.add(line);
    }
    long rows = tab.viewport().lineCount();
    for (long bound : new long[] {MarkSteps.WORKER_MARKS, 0}) {
      tab.markSteps().setBound(bound);
      for (int i = 0; i < 2; i++) {
        boolean forward = i == 0;
        for (long row = 0; row < rows; row++) {
          long caret = tab.sourceLine(row);
          tab.select(Selection.caretAt(new TextPosition(row, 0)));
          fixture.view.notices.clear();
          if (forward) {
            tab.nextMark();
          } else {
            tab.previousMark();
          }
          assertEquals(bound == 0, tab.markSteps().running());
          waitForStep(tab);
          long want = expected(layout, caret, forward);
          long shown = tab.sourceLine(tab.selection().caret().line());
          assertEquals(want < 0 ? caret : want, shown, "bound " + bound + " from " + caret);
          UiText none = forward ? UiText.NOTICE_NO_NEXT_MARK : UiText.NOTICE_NO_PREVIOUS_MARK;
          assertEquals(want < 0, fixture.view.notices.contains(none.text()));
        }
      }
    }
  }

  private void waitForStep(final DocumentController tab) throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !tab.markSteps().running() && fixture.view.active.get() == 0;
          }
        });
  }

  @Test
  void denseInterleavedMarksStepTheSameOnBothPaths() throws Exception {
    stepsMatch(dense());
  }

  @Test
  void sparseMarksStepTheSameOnBothPaths() throws Exception {
    stepsMatch(sparse());
  }

  @Test
  void stepsStopAtTheEndsOnBothPaths() throws Exception {
    stepsMatch(ends());
  }

  @Test
  void rowsSkippingMostMarksStepTheSameOnBothPaths() throws Exception {
    stepsMatch(mostlyNotRows());
  }

  @Test
  void aNewRequestReplacesARunningStep() throws Exception {
    DocumentController tab = tab();
    for (long line : sparse()) {
      tab.document().marks().add(line);
    }
    tab.markSteps().setBound(0);
    tab.select(Selection.caretAt(new TextPosition(tab.rowOf(150), 0)));
    tab.nextMark();
    tab.previousMark();
    waitForStep(tab);
    assertEquals(52, tab.sourceLine(tab.selection().caret().line()));
    assertFalse(fixture.view.notices.contains(UiText.NOTICE_NO_NEXT_MARK.text()));
  }

  @Test
  void stopAndCloseCancelARunningStep() throws Exception {
    DocumentController tab = tab();
    for (long line : sparse()) {
      tab.document().marks().add(line);
    }
    tab.markSteps().setBound(0);
    long caret = tab.sourceLine(0);
    tab.nextMark();
    tab.stop();
    assertFalse(tab.markSteps().running());
    fixture.runUntilIdle();
    assertEquals(caret, tab.sourceLine(tab.selection().caret().line()));
    tab.nextMark();
    fixture.application.closeDocument(tab);
    assertFalse(tab.markSteps().running());
    fixture.runUntilIdle();
  }

  @Test
  void aStepThatSawTheMarksChangeStepsAgain() {
    final MutableLineSet marks = new MutableLineSet();
    marks.add(5);
    marks.add(40);
    final MutableLineSet members = new MutableLineSet();
    members.add(3);
    members.add(40);
    // The first lookup on the rows marks row 3, which the step has passed: it would find 40.
    LineSet rows =
        new Rows(members) {
          private boolean changed;

          @Override
          public long ceiling(long line) {
            if (!changed) {
              changed = true;
              marks.add(3);
            }
            return super.ceiling(line);
          }
        };
    assertEquals(3, MarkSteps.stable(marks, rows, 0, true, new CancellationToken()));
  }

  @Test
  void aCanceledStepThrows() {
    final MutableLineSet marks = new MutableLineSet();
    final MutableLineSet rows = new MutableLineSet();
    for (long line = 0; line < 4 * MarkSteps.CHECK_STEPS; line++) {
      marks.add(2 * line + 1);
      rows.add(2 * line);
    }
    final CancellationToken token = new CancellationToken();
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            MarkSteps.stable(marks, rows, 0, true, token);
          }
        });
  }

  /** A row set that forwards to another. */
  private static class Rows implements LineSet {
    private final LineSet lines;

    Rows(LineSet lines) {
      this.lines = lines;
    }

    @Override
    public long size() {
      return lines.size();
    }

    @Override
    public boolean contains(long line) {
      return lines.contains(line);
    }

    @Override
    public long select(long index) {
      return lines.select(index);
    }

    @Override
    public long rank(long line) {
      return lines.rank(line);
    }

    @Override
    public long ceiling(long line) {
      return lines.ceiling(line);
    }

    @Override
    public long floor(long line) {
      return lines.floor(line);
    }
  }
}
