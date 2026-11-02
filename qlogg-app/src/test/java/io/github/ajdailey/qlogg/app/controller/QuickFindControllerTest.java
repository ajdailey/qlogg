package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuickFindControllerTest {
  @TempDir Path temp;

  private ControllerFixture fixture;
  private DocumentController document;
  private QuickFindController quickFind;

  @BeforeEach
  void open() throws Exception {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    document = fixture.open(fixture.lines("q.log", 40));
    quickFind = fixture.application.quickFind();
  }

  @AfterEach
  void close() throws InterruptedException {
    fixture.close();
  }

  private static Selection range(long line, long from, long to) {
    return new Selection(new TextPosition(line, from), new TextPosition(line, to));
  }

  private BooleanSupplier selected(final Selection expected) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return expected.equals(document.selection()) && fixture.view.active.get() == 0;
      }
    };
  }

  private void execute(CommandId command) throws InterruptedException {
    fixture.application.execute(command);
    fixture.runUntilIdle();
  }

  @Test
  void typingFindsIncrementallyAndNextWalksWithoutWrap() throws Exception {
    execute(CommandId.FIND);
    assertTrue(quickFind.isOpen());
    assertEquals(1, fixture.view.quickFindFocused);
    quickFind.setText("h");
    fixture.runUntilIdle();
    quickFind.setText("hit");
    fixture.runUntilIdle();
    assertEquals(range(3, 0, 3), document.selection());
    execute(CommandId.FIND_NEXT);
    assertEquals(range(10, 0, 3), document.selection());
    execute(CommandId.FIND_PREVIOUS);
    assertEquals(range(3, 0, 3), document.selection());
    execute(CommandId.FIND_PREVIOUS);
    assertEquals(range(3, 0, 3), document.selection());
    assertEquals(UiText.NOTICE_START_OF_FILE.text(), fixture.view.lastNotice());
    assertEquals(UiText.NOTICE_START_OF_FILE.text(), quickFind.state().notice());
    document.select(Selection.caretAt(new TextPosition(38, 1)));
    execute(CommandId.FIND_NEXT);
    assertEquals(UiText.NOTICE_END_OF_FILE.text(), fixture.view.lastNotice());
    assertEquals(Selection.caretAt(new TextPosition(38, 1)), document.selection());
  }

  @Test
  void ignoreCaseAndRegexFollowTheBar() throws Exception {
    quickFind.open(true);
    quickFind.setText("HIT 1");
    fixture.runUntilIdle();
    assertEquals(range(10, 0, 5), document.selection());
    quickFind.setIgnoreCase(false);
    quickFind.submit();
    fixture.runUntilIdle();
    assertEquals(UiText.NOTICE_END_OF_FILE.text(), quickFind.state().notice());
    fixture.application.setQuickFindOptions(false, true);
    assertTrue(fixture.application.preferences().quickFindRegex());
    assertFalse(quickFind.state().incremental());
    quickFind.setText("(");
    quickFind.submit();
    fixture.runUntilIdle();
    assertTrue(
        quickFind.state().notice().startsWith("Invalid regular expression"),
        quickFind.state().notice());
    quickFind.setText("hit [23]\\d");
    quickFind.submit();
    fixture.runUntilIdle();
    assertEquals(range(24, 0, 6), document.selection());
  }

  @Test
  void selectionFindsItsTextAndFindKeysFallBackToTheSearch() throws Exception {
    document.select(range(24, 0, 3));
    fixture.application.execute(CommandId.FIND_SELECTION_FORWARD);
    fixture.runUntil(selected(range(31, 0, 3)));
    assertTrue(quickFind.isOpen());
    assertEquals("hit", quickFind.state().text());
    fixture.application.execute(CommandId.FIND_SELECTION_BACKWARD);
    fixture.runUntil(selected(range(24, 0, 3)));
    quickFind.close();
    document.select(Selection.caretAt(new TextPosition(0, 0)));
    execute(CommandId.FIND_NEXT);
    assertEquals(UiText.NOTICE_END_OF_FILE.text(), fixture.view.lastNotice());
    assertEquals(0, document.selection().caret().line());
  }

  @Test
  void theFilteredViewSearchesItsRowsOnly() throws Exception {
    document
        .search()
        .run(
            "hit",
            new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, false, false));
    fixture.runUntilEnded(document.search());
    FilteredController filtered = document.filtered();
    fixture.application.setFocusedView(filtered);
    quickFind.open(true);
    quickFind.setText("1");
    fixture.runUntilIdle();
    assertEquals(range(1, 4, 5), filtered.selection());
    assertEquals(10, document.selection().caret().line());
    execute(CommandId.FIND_NEXT);
    assertEquals(range(2, 4, 5), filtered.selection());
  }
}
