package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Go Back and Go Forward over a document's jumps, through the window's commands. */
class NavigationTest {
  private static final SearchOptions LITERAL =
      new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, false, false);

  @TempDir Path temp;

  private ControllerFixture fixture;

  @AfterEach
  void close() throws InterruptedException {
    if (fixture != null) {
      fixture.close();
    }
  }

  private DocumentController open(int lines) throws Exception {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    return fixture.open(fixture.lines("nav.log", lines));
  }

  private static long caret(DocumentController document) {
    return document.sourceLine(document.selection().caret().line());
  }

  @Test
  void jumpsGoBackAndForwardAndANewJumpDropsTheForwardEntries() throws Exception {
    DocumentController document = open(200);
    document.jumpToLine(51);
    document.jumpToLine(101);
    fixture.application.execute(CommandId.LINE_DOWN);
    fixture.application.execute(CommandId.LINE_DOWN);
    assertEquals(102, caret(document));
    // The filtered view has the focus; the commands still move the main view.
    fixture.application.setFocusedView(document.filtered());
    fixture.application.execute(CommandId.GO_BACK);
    assertEquals(50, caret(document));
    fixture.application.execute(CommandId.GO_BACK);
    assertEquals(0, caret(document));
    fixture.application.execute(CommandId.GO_BACK);
    assertEquals(0, caret(document));
    fixture.application.execute(CommandId.GO_FORWARD);
    fixture.application.execute(CommandId.GO_FORWARD);
    assertEquals(102, caret(document));
    fixture.application.execute(CommandId.GO_BACK);
    assertEquals(50, caret(document));
    fixture.application.setFocusedView(document);
    fixture.application.execute(CommandId.GO_TO_BOTTOM);
    assertEquals(199, caret(document));
    fixture.application.execute(CommandId.GO_FORWARD);
    assertEquals(199, caret(document));
    assertArrayEquals(new long[] {0, 50}, document.history().entries());
    fixture.application.execute(CommandId.GO_BACK);
    assertEquals(50, caret(document));
  }

  @Test
  void aFilteredRowChosenRecordsTheLineBeforeTheFilteredViewMovedTheMainView() throws Exception {
    DocumentController document = open(60);
    FilteredController filtered = document.filtered();
    document.jumpToLine(6);
    document.search().run("hit", LITERAL);
    fixture.runUntilEnded(document.search());
    fixture.application.setFocusedView(filtered);
    filtered.select(Selection.caretAt(new TextPosition(2, 0)));
    fixture.application.execute(CommandId.LINE_DOWN);
    assertEquals(24, caret(document));
    assertArrayEquals(new long[] {0}, document.history().entries());
    fixture.application.execute(CommandId.GO_TO_RESULT);
    assertArrayEquals(new long[] {0, 5}, document.history().entries());
    filtered.select(Selection.caretAt(new TextPosition(5, 0)));
    filtered.rowChosen();
    assertArrayEquals(new long[] {0, 5, 24}, document.history().entries());
    fixture.application.execute(CommandId.GO_BACK);
    assertEquals(24, caret(document));
    fixture.application.execute(CommandId.GO_BACK);
    assertEquals(5, caret(document));
  }

  @Test
  void marksAndQuickFindAreJumps() throws Exception {
    final DocumentController document = open(60);
    document.document().marks().add(30);
    fixture.application.setFocusedView(document);
    fixture.application.execute(CommandId.NEXT_MARK);
    assertEquals(30, caret(document));
    fixture.application.quickFind().open(true);
    fixture.application.quickFind().setText("hit 45");
    fixture.application.quickFind().submit();
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return caret(document) == 45;
          }
        });
    assertArrayEquals(new long[] {0, 30}, document.history().entries());
  }

  @Test
  void aReloadKeepsTheHistoryAndATruncationClampsIt() throws Exception {
    DocumentController document = open(100);
    document.jumpToLine(81);
    document.jumpToLine(91);
    fixture.application.execute(CommandId.RELOAD);
    DocumentController reloaded = replacement(document);
    assertArrayEquals(new long[] {0, 80}, reloaded.history().entries());
    Files.writeString(
        temp.resolve("nav.log"), "a\nb\nc\nd\ne\n", StandardOpenOption.TRUNCATE_EXISTING);
    final DocumentController truncated = replacement(reloaded);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return truncated.history().entries().length == 2
                && truncated.history().entries()[1] == 4;
          }
        });
    assertEquals(0, caret(truncated));
    fixture.application.execute(CommandId.GO_BACK);
    assertEquals(4, caret(truncated));
    fixture.application.execute(CommandId.GO_BACK);
    assertEquals(0, caret(truncated));
  }

  private DocumentController replacement(final DocumentController old) throws Exception {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = fixture.application.activeDocument();
            return shown != null && shown != old && !shown.state().indexing();
          }
        });
    DocumentController shown = fixture.application.activeDocument();
    shown.viewportResized(10, 80);
    return shown;
  }
}
