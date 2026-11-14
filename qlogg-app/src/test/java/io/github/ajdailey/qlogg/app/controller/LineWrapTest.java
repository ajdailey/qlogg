package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.model.LineBlock;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wrapping one line in place with text wrap off: the toggle and the command, one line per view, the
 * read from column 0, the viewport's rows following the fit, and the line dropped by text wrap, by
 * a reload and, in the filtered view, by a new search.
 */
class LineWrapTest {
  /** Columns of each long line; more than the 80 the fixture's views have. */
  private static final int LONG = 300;

  @TempDir Path temp;

  /** Fifty lines; every tenth, from line 2, is {@link #LONG} columns of digits. */
  private static Path longLines(ControllerFixture fixture) throws IOException {
    Path file = fixture.temp.resolve("long.log");
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.US_ASCII)) {
      for (int i = 0; i < 50; i++) {
        if (i % 10 == 2) {
          StringBuilder line = new StringBuilder();
          for (int k = 0; k < LONG; k++) {
            line.append((char) ('0' + k % 10));
          }
          out.write(line + "\n");
        } else {
          out.write("line " + i + "\n");
        }
      }
    }
    return file;
  }

  private static void awaitWrapped(
      ControllerFixture fixture, final LineViewController view, final long row)
      throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.wrappedLines().contains(row);
          }
        });
  }

  private static void mark(
      ControllerFixture fixture, final DocumentController document, long line, final int marks)
      throws InterruptedException {
    document.select(Selection.caretAt(new TextPosition(line, 0)));
    document.toggleMarks();
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.marks().size() == marks;
          }
        });
    // One mark toggle runs at a time; the next waits for this one to end.
    fixture.runUntilIdle();
  }

  @Test
  void aLineWrapsFromColumnZeroAndUnwraps() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      DocumentController document = fixture.open(longLines(fixture));
      fixture.runUntilIdle();
      document.setFirstColumn(5);
      document.toggleLineWrap(2);
      assertEquals(2, document.wrappedLine());
      awaitWrapped(fixture, document, 2);
      LineBlock wrapped = document.wrappedLines();
      assertEquals(1, wrapped.count());
      assertEquals(0, wrapped.firstColumn());
      assertEquals(LONG, wrapped.text(2).length());
      assertTrue(wrapped.text(2).startsWith("0123456789"));
      assertEquals(5, document.viewport().firstColumn());
      assertEquals(5, document.lines().firstColumn());
      document.toggleLineWrap(2);
      assertEquals(-1, document.wrappedLine());
      assertSame(LineBlock.EMPTY, document.wrappedLines());
    }
  }

  @Test
  void wrappingALineUnwrapsThePreviousOneInItsViewOnly() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      final DocumentController document = fixture.open(longLines(fixture));
      final FilteredController filtered = document.filtered();
      mark(fixture, document, 12, 1);
      mark(fixture, document, 22, 2);
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return filtered.viewport().lineCount() == 2;
            }
          });
      document.toggleLineWrap(2);
      document.toggleLineWrap(5);
      assertEquals(5, document.wrappedLine());
      awaitWrapped(fixture, document, 5);
      assertFalse(document.wrappedLines().contains(2));
      filtered.toggleLineWrap(filtered.rowOf(22));
      assertEquals(22, filtered.wrappedLine());
      awaitWrapped(fixture, filtered, 1);
      assertEquals(LONG, filtered.wrappedLines().text(1).length());
      assertEquals(5, document.wrappedLine());
    }
  }

  /** Search text found in the long lines and in none of the short ones. */
  @Test
  void aNewSearchUnwrapsTheFilteredViewsLineOnly() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      DocumentController document = fixture.open(longLines(fixture));
      FilteredController filtered = document.filtered();
      document.search().run("0123", SearchOptions.DEFAULTS);
      fixture.runUntilEnded(document.search());
      assertEquals(5, filtered.viewport().lineCount());
      filtered.toggleLineWrap(1);
      document.toggleLineWrap(2);
      assertEquals(12, filtered.wrappedLine());
      document.search().run("6789", SearchOptions.DEFAULTS);
      assertEquals(-1, filtered.wrappedLine());
      assertEquals(2, document.wrappedLine());
      fixture.runUntilEnded(document.search());
    }
  }

  @Test
  void theCommandTogglesTheCaretLineAndIsDisabledWithTextWrap() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      DocumentController document = fixture.open(longLines(fixture));
      CommandStates states = new CommandStates(fixture.application);
      document.select(Selection.caretAt(new TextPosition(2, 0)));
      fixture.application.setFocusedView(document);
      assertTrue(states.enabled(CommandId.WRAP_LINE));
      fixture.application.execute(CommandId.WRAP_LINE);
      assertEquals(2, document.wrappedLine());
      fixture.application.execute(CommandId.TOGGLE_WRAP);
      assertEquals(-1, document.wrappedLine());
      assertSame(LineBlock.EMPTY, document.wrappedLines());
      assertFalse(states.enabled(CommandId.WRAP_LINE));
      fixture.application.execute(CommandId.WRAP_LINE);
      document.toggleLineWrap(2);
      assertEquals(-1, document.wrappedLine());
      fixture.application.execute(CommandId.TOGGLE_WRAP);
      assertEquals(-1, document.wrappedLine());
      assertTrue(states.enabled(CommandId.WRAP_LINE));
    }
  }

  @Test
  void theViewportRowsFollowTheFitWhileALineIsWrapped() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      DocumentController document = fixture.open(longLines(fixture));
      document.wrapFitted(4);
      assertEquals(10, document.viewport().rows());
      document.toggleLineWrap(2);
      document.wrapFitted(4);
      assertEquals(4, document.viewport().rows());
      assertEquals(46, document.viewport().maxTop());
      document.viewportResized(12, 80);
      assertEquals(4, document.viewport().rows());
      document.toggleLineWrap(2);
      assertEquals(12, document.viewport().rows());
    }
  }

  @Test
  void aReloadStartsWithNoWrappedLine() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      final DocumentController document = fixture.open(longLines(fixture));
      document.toggleLineWrap(2);
      fixture.application.execute(CommandId.RELOAD);
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              DocumentController shown = fixture.application.activeDocument();
              return shown != null && shown != document && !shown.state().indexing();
            }
          });
      DocumentController reloaded = fixture.application.activeDocument();
      assertEquals(-1, reloaded.wrappedLine());
      assertEquals(-1, reloaded.filtered().wrappedLine());
    }
  }
}
