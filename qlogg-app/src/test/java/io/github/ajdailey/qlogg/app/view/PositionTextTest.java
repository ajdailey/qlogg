package io.github.ajdailey.qlogg.app.view;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import org.junit.jupiter.api.Test;

class PositionTextTest {
  @Test
  void showsTheCaretAndTheSelectionSize() {
    assertEquals(
        "Ln 12/1,200  Col 5", PositionText.of(Selection.caretAt(new TextPosition(11, 4)), 1200));
    assertEquals(
        "Ln 3/10  Col 9  Sel 6 chars",
        PositionText.of(new Selection(new TextPosition(2, 2), new TextPosition(2, 8)), 10));
    assertEquals(
        "Ln 2/10  Col 1  Sel 4 lines",
        PositionText.of(new Selection(new TextPosition(4, 3), new TextPosition(1, 0)), 10));
    assertEquals(
        "Ln 10/10  Sel 10 lines",
        PositionText.of(
            new Selection(new TextPosition(0, 0), new TextPosition(9, Long.MAX_VALUE)), 10));
  }
}
