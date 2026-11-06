package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.model.MarkComments;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Edit Mark Comment and the marks list, driven through the application. */
class MarkCommentsTest {
  @TempDir Path temp;

  private ControllerFixture fixture;
  private DocumentController document;

  @BeforeEach
  void open() throws Exception {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    document = fixture.open(fixture.lines("m.log", 40));
  }

  @AfterEach
  void close() throws InterruptedException {
    fixture.close();
  }

  private void caretAt(long line) {
    fixture.application.setFocusedView(document);
    document.select(Selection.caretAt(new TextPosition(line, 0)));
  }

  private void settle() throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !document.markingRunning() && fixture.view.active.get() == 0;
          }
        });
  }

  @Test
  void editMarkCommentMarksTheCaretLineAndAnEmptyCommentKeepsTheMark() throws Exception {
    caretAt(6);
    fixture.application.execute(CommandId.EDIT_MARK_COMMENT);
    assertEquals(6, fixture.view.commentLine);
    assertEquals("", fixture.view.suggestedName);
    fixture.view.nameListener.nameChosen("first\tnote");
    assertTrue(document.marked(6));
    assertEquals("first note", document.markComment(6));
    fixture.application.execute(CommandId.EDIT_MARK_COMMENT);
    assertEquals("first note", fixture.view.suggestedName);
    fixture.view.nameListener.nameChosen("");
    assertTrue(document.marked(6));
    assertNull(document.markComment(6));
  }

  @Test
  void unmarkingAndRemovingDropTheComment() throws Exception {
    document.setMarkComment(3, "a");
    document.setMarkComment(9, "b");
    caretAt(3);
    fixture.application.execute(CommandId.TOGGLE_MARK);
    settle();
    assertFalse(document.marked(3));
    assertNull(document.markComment(3));
    document.removeMarks(new long[] {9});
    assertFalse(document.marked(9));
    assertEquals(0, document.document().markComments().size());
  }

  @Test
  void aRefusedCommentShowsANotice() throws Exception {
    for (int i = 0; i < MarkComments.MAX_COMMENTS; i++) {
      assertTrue(document.document().markComments().put(1000 + i, "n"));
    }
    document.setMarkComment(39, "one too many");
    assertTrue(document.marked(39));
    assertNull(document.markComment(39));
    assertEquals(
        UiText.NOTICE_COMMENT_NOT_KEPT.format(MarkComments.MAX_COMMENTS),
        fixture.view.lastNotice());
  }

  @Test
  void aCanceledMarksListReadReturnsNothingAndNoReservation() throws Exception {
    document.setMarkComment(3, "flagword");
    settle();
    MemoryBudget own = MemoryBudget.of(1L << 28);
    CancellationToken token = new CancellationToken();
    token.cancel();
    assertNull(MarksListController.read(document.document(), own, token));
    assertEquals(0, own.reserved(MemoryShare.RESULTS));
  }

  @Test
  void theMarksListFollowsMarksCommentsAndTheShownTab() throws Exception {
    final AtomicInteger changes = new AtomicInteger();
    final MarksListController list =
        new MarksListController(
            fixture.application,
            new MarksListController.Listener() {
              @Override
              public void rowsChanged() {
                changes.incrementAndGet();
              }
            });
    assertSame(document, list.document());
    document.setMarkComment(10, "flagword");
    document.setMarkComment(3, "");
    untilRows(list, 2);
    assertEquals(
        List.of(
            new MarksListController.Row(3, "", "hit 3"),
            new MarksListController.Row(10, "flagword", "hit 10")),
        list.rows());
    assertEquals(2, list.total());
    list.setComment(3, "edited");
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !list.rows().isEmpty() && "edited".equals(list.rows().get(0).comment());
          }
        });
    list.goTo(10);
    assertEquals(10, document.selection().caret().line());
    list.remove(new long[] {3});
    untilRows(list, 1);
    assertEquals(10, list.rows().get(0).line());

    DocumentController other = fixture.open(fixture.lines("n.log", 5));
    assertSame(other, list.document());
    untilRows(list, 0);
    fixture.application.activate(document);
    untilRows(list, 1);
    assertTrue(changes.get() > 0);
    long listed = fixture.budget.reserved(MemoryShare.RESULTS);
    list.close();
    assertNull(list.document());
    assertTrue(list.rows().isEmpty());
    assertTrue(fixture.budget.reserved(MemoryShare.RESULTS) < listed);
  }

  @Test
  void marksListCommandOpensTheWindow() {
    fixture.application.execute(CommandId.MARKS_LIST);
    assertEquals(1, fixture.view.marksShown);
  }

  private void untilRows(final MarksListController list, final int rows)
      throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !list.reading() && list.rows().size() == rows;
          }
        });
  }
}
