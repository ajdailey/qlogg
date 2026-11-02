package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.model.Visibility;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The filtered view over search results and marks, driven through the application. */
class FilteredControllerTest {
  private static final SearchOptions LITERAL =
      new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, false, false);

  @TempDir Path temp;

  private ControllerFixture fixture;
  private DocumentController document;
  private FilteredController filtered;

  @BeforeEach
  void open() throws Exception {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    document = fixture.open(fixture.lines("f.log", 40));
    filtered = document.filtered();
    document.search().run("hit", LITERAL);
    fixture.runUntilEnded(document.search());
    settle();
  }

  @AfterEach
  void close() throws InterruptedException {
    fixture.close();
  }

  private void mark(long first, long last) throws InterruptedException {
    fixture.application.setFocusedView(document);
    document.select(
        new Selection(new TextPosition(first, 0), new TextPosition(last, Long.MAX_VALUE)));
    fixture.application.execute(CommandId.TOGGLE_MARK);
    settle();
  }

  /** Drives the queue until no mark toggle runs and the filtered rows show the marks. */
  private void settle() throws InterruptedException {
    final DocumentController shown = fixture.application.activeDocument();
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !shown.markingRunning()
                && shown.filtered().settled()
                && fixture.view.active.get() == 0;
          }
        });
  }

  @Test
  void rowsFollowMatchesMarksAndTheMode() throws Exception {
    assertEquals(List.of(3L, 10L, 17L, 24L, 31L, 38L), ControllerFixture.rows(filtered));
    mark(5, 5);
    assertEquals(List.of(3L, 5L, 10L, 17L, 24L, 31L, 38L), ControllerFixture.rows(filtered));
    fixture.application.execute(CommandId.SHOW_MARKS);
    assertEquals(List.of(5L), ControllerFixture.rows(filtered));
    fixture.application.execute(CommandId.SHOW_MATCHES);
    assertEquals(6, filtered.viewport().lineCount());
    fixture.application.execute(CommandId.NEXT_VISIBILITY);
    assertEquals(Visibility.MARKS_AND_MATCHES, filtered.visibility());
    fixture.application.execute(CommandId.PREVIOUS_VISIBILITY);
    assertEquals(Visibility.MATCHES, filtered.visibility());
    document.search().run("hit", LITERAL);
    assertEquals(Visibility.MARKS_AND_MATCHES, filtered.visibility());
    fixture.runUntilEnded(document.search());
    settle();
    assertEquals(7, filtered.viewport().lineCount());
  }

  @Test
  void markTogglesARangeAsOneAndWalksTheMarks() throws Exception {
    mark(0, 2);
    assertEquals(List.of(0L, 1L, 2L), ControllerFixture.toList(document.marks()));
    mark(0, 2);
    assertEquals(0, document.marks().size());
    mark(5, 5);
    mark(4, 6);
    assertEquals(List.of(4L, 5L, 6L), ControllerFixture.toList(document.marks()));
    mark(20, 20);
    document.select(Selection.caretAt(new TextPosition(0, 0)));
    fixture.application.execute(CommandId.NEXT_MARK);
    assertEquals(4, document.selection().caret().line());
    document.select(Selection.caretAt(new TextPosition(6, 0)));
    fixture.application.execute(CommandId.NEXT_MARK);
    assertEquals(20, document.selection().caret().line());
    fixture.application.execute(CommandId.NEXT_MARK);
    assertEquals(20, document.selection().caret().line());
    assertEquals(UiText.NOTICE_NO_NEXT_MARK.text(), fixture.view.lastNotice());
    fixture.application.execute(CommandId.PREVIOUS_MARK);
    assertEquals(6, document.selection().caret().line());
  }

  @Test
  void aLargeRangeMarksOnAWorkerAndTheFilteredViewFollows() throws Exception {
    DocumentController big = fixture.open(fixture.lines("big.log", 200_000));
    big.search().run("hit", LITERAL);
    fixture.runUntilEnded(big.search());
    big.selectAll();
    fixture.application.setFocusedView(big);
    fixture.application.execute(CommandId.TOGGLE_MARK);
    assertTrue(big.markingRunning());
    fixture.application.execute(CommandId.TOGGLE_MARK);
    assertEquals(UiText.MARK_RUNNING.text(), fixture.view.lastNotice());
    settle();
    assertEquals(200_000, big.marks().size());
    assertEquals(UiText.MARK_DONE.format(200_000L), fixture.view.lastNotice());
    assertEquals(200_000, big.filtered().viewport().lineCount());
    assertEquals(199_999, big.filtered().sourceLine(199_999));
    big.selectAll();
    fixture.application.execute(CommandId.TOGGLE_MARK);
    settle();
    assertEquals(0, big.marks().size());
    assertEquals(UiText.UNMARK_DONE.format(200_000L), fixture.view.lastNotice());
    assertEquals(big.search().results().size(), big.filtered().viewport().lineCount());
  }

  @Test
  void stopCancelsAMarkToggleAndTheActivityEnds() throws Exception {
    DocumentController big = fixture.open(fixture.lines("stop.log", 200_000));
    big.selectAll();
    fixture.application.setFocusedView(big);
    fixture.application.execute(CommandId.TOGGLE_MARK);
    fixture.application.execute(CommandId.STOP);
    settle();
    long marked = big.marks().size();
    assertTrue(marked <= 200_000);
    String notice = fixture.view.lastNotice();
    assertTrue(
        notice.equals(UiText.MARK_CANCELED.format(marked))
            || notice.equals(UiText.MARK_DONE.format(marked)),
        notice);
  }

  @Test
  void aRowShowsItsLineInTheMainViewAndCopiesSourceLines() throws Exception {
    filtered.select(Selection.caretAt(new TextPosition(2, 0)));
    assertEquals(17, document.selection().caret().line());
    fixture.application.setFocusedView(filtered);
    fixture.application.execute(CommandId.LINE_DOWN);
    assertEquals(3, filtered.selection().caret().line());
    assertEquals(24, document.selection().caret().line());
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return filtered.lines().contains(0) && filtered.lines().contains(5);
          }
        });
    assertEquals("hit 3", filtered.lines().text(0));
    assertEquals(38, filtered.lines().sourceLine(5));
    filtered.select(new Selection(new TextPosition(1, 0), new TextPosition(2, Long.MAX_VALUE)));
    fixture.application.execute(CommandId.COPY);
    fixture.runUntilIdle();
    assertEquals("hit 10" + System.lineSeparator() + "hit 17", fixture.view.clipboard);
    filtered.select(Selection.caretAt(new TextPosition(4, 2)));
    fixture.application.execute(CommandId.COPY_LINE);
    fixture.runUntilIdle();
    assertEquals("hit 31", fixture.view.clipboard);
  }

  @Test
  void filteredLineNumbersToggleApartFromTheMainView() {
    assertTrue(filtered.lineNumbers());
    fixture.application.execute(CommandId.TOGGLE_FILTERED_LINE_NUMBERS);
    assertEquals(false, filtered.lineNumbers());
    assertTrue(document.lineNumbers());
  }

  @Test
  void selectionStartAndEndSelectWholeLines() {
    fixture.application.setFocusedView(document);
    document.select(Selection.caretAt(new TextPosition(12, 3)));
    fixture.application.execute(CommandId.SET_SELECTION_START);
    document.select(Selection.caretAt(new TextPosition(8, 1)));
    fixture.application.execute(CommandId.SET_SELECTION_END);
    assertEquals(new TextPosition(8, 0), document.selection().start());
    assertEquals(new TextPosition(12, Long.MAX_VALUE), document.selection().end());
  }
}
