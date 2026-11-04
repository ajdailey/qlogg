package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LineViewController#selectWordAt}: word bounds, the char before, and what selects nothing.
 */
class WordSelectionTest {
  @TempDir Path temp;

  private static Selection on(long row, long from, long to) {
    return new Selection(new TextPosition(row, from), new TextPosition(row, to));
  }

  @Test
  void aPositionSelectsTheWordAroundItOrBeforeIt() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      Path file = temp.resolve("words.log");
      Files.writeString(
          file, "at mock_word.run(42) end\n  ..  \ncafé word\n", StandardCharsets.UTF_8);
      final DocumentController document = fixture.open(file);
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return document.lines().contains(2);
            }
          });
      document.selectWordAt(new TextPosition(0, 5));
      assertEquals(on(0, 3, 12), document.selection());
      document.selectWordAt(new TextPosition(0, 3));
      assertEquals(on(0, 3, 12), document.selection());
      // On the dot after the word: the char before it.
      document.selectWordAt(new TextPosition(0, 12));
      assertEquals(on(0, 3, 12), document.selection());
      document.selectWordAt(new TextPosition(0, 17));
      assertEquals(on(0, 17, 19), document.selection());
      document.selectWordAt(new TextPosition(0, 23));
      assertEquals(on(0, 21, 24), document.selection());

      Selection before = Selection.caretAt(new TextPosition(0, 0));
      document.select(before);
      // Past the line end, between non-word chars, a row outside printable ASCII, a row not read.
      document.selectWordAt(new TextPosition(0, 24));
      document.selectWordAt(new TextPosition(1, 3));
      document.selectWordAt(new TextPosition(2, 6));
      document.selectWordAt(new TextPosition(500, 0));
      assertEquals(before, document.selection());
    }
  }
}
