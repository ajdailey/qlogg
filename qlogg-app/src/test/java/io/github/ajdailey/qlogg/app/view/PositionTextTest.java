package io.github.ajdailey.qlogg.app.view;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import org.junit.jupiter.api.Test;

class PositionTextTest {
  @Test
  void showsTheCaretAndTheSelectionSize() {
    Selection caret = Selection.caretAt(new TextPosition(11, 4));
    assertEquals("Ln 12/1,200  Col 5", PositionText.of(caret, 1200, caret, -1, ""));
    Selection inLine = new Selection(new TextPosition(2, 2), new TextPosition(2, 8));
    assertEquals("Ln 3/10  Col 9  Sel 3 chars", PositionText.of(inLine, 10, inLine, 3, ""));
    assertEquals("Ln 3/10  Col 9", PositionText.of(inLine, 10, inLine, -1, ""), "count not read");
    Selection lines = new Selection(new TextPosition(4, 3), new TextPosition(1, 0));
    assertEquals("Ln 2/10  Col 1  Sel 4 lines", PositionText.of(lines, 10, lines, -1, ""));
    Selection all = new Selection(new TextPosition(0, 0), new TextPosition(9, Long.MAX_VALUE));
    assertEquals("Ln 10/10  Sel 10 lines", PositionText.of(all, 10, all, -1, ""));
  }

  @Test
  void addsTheTimeDifferenceOfASelectionOverLines() {
    Selection caret = Selection.caretAt(new TextPosition(1, 0));
    Selection rows = new Selection(new TextPosition(2, 0), new TextPosition(5, 4));
    assertEquals(
        "Ln 2/10  Col 1  Sel 4 lines  +00:01:23.456",
        PositionText.of(caret, 10, rows, -1, "+00:01:23.456"));
    Selection inLine = new Selection(new TextPosition(2, 2), new TextPosition(2, 8));
    assertEquals(
        "Ln 3/10  Col 9  Sel 6 chars", PositionText.of(inLine, 10, inLine, 6, "+00:00:00.000"));
    assertEquals("Ln 2/10  Col 1  Sel 4 lines", PositionText.of(caret, 10, rows, -1, ""));
  }
}
