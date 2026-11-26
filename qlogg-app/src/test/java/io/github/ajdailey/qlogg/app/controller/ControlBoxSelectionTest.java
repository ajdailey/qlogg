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
 * Selection across a control box: the columns after it, copy returning the original char, a double
 * click that stops at the box, and the selected char count over a box and a tab.
 */
class ControlBoxSelectionTest {
  @TempDir Path temp;

  private static Selection on(long row, long from, long to) {
    return new Selection(new TextPosition(row, from), new TextPosition(row, to));
  }

  private static String text(final DocumentController document, final ControllerFixture fixture)
      throws InterruptedException {
    final String[] read = new String[1];
    document.selectionText(
        new TextListener() {
          @Override
          public void textRead(String text) {
            read[0] = text;
          }
        });
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return read[0] != null;
          }
        });
    return read[0];
  }

  @Test
  void aSelectionAcrossABoxCopiesTheCharAndAWordStopsAtIt() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      Path file = temp.resolve("controls.log");
      Files.writeString(file, "mock\u001bword end\n", StandardCharsets.UTF_8);
      final DocumentController document = fixture.open(file);
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return document.lines().contains(0);
            }
          });
      assertEquals("mock  word end", document.lines().text(0));
      assertEquals(14, document.lines().width(0));
      document.select(on(0, 4, 10));
      assertEquals("\u001bword", text(document, fixture));
      document.select(on(0, 6, 10));
      assertEquals("word", text(document, fixture));
      document.selectWordAt(new TextPosition(0, 7));
      assertEquals(on(0, 6, 10), document.selection());
      document.selectWordAt(new TextPosition(0, 2));
      assertEquals(on(0, 0, 4), document.selection());
      Selection before = document.selection();
      document.selectWordAt(new TextPosition(0, 5));
      assertEquals(before, document.selection());
    }
  }

  @Test
  void theSelectedCharCountTakesABoxAndATabAsOneChar() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      Path file = temp.resolve("controls.log");
      Files.writeString(file, "C\u0007[0m\tend\n", StandardCharsets.UTF_8);
      final DocumentController document = fixture.open(file);
      assertEquals(-1, document.selectedChars(), "no selection");
      document.select(on(0, 0, 4));
      counted(document, fixture, 3);
      document.select(on(0, 4, 8));
      counted(document, fixture, 3);
    }
  }

  /** Waits for the count; C, BEL and [ span four columns, 0, m and the tab four. */
  private static void counted(
      final DocumentController document, ControllerFixture fixture, final long expected)
      throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.selectedChars() >= 0;
          }
        });
    assertEquals(expected, document.selectedChars(), document.selection().toString());
  }
}
