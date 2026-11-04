package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.ViewOptions;
import io.github.ajdailey.qlogg.app.highlight.HighlightSelector;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Folding with auto-expand on navigation, block selection and copy, copy with line numbers, text
 * wrap, save to file, search across open files, automatic highlighter sets, hidden ANSI colors and
 * sending text to the scratchpad.
 */
class ViewFeaturesTest {
  @TempDir Path temp;

  /** Ten entries of four lines: a header, then three indented continuation lines. */
  private Path entries(ControllerFixture fixture, String name) throws IOException {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 10; i++) {
      text.append("entry ").append(i).append('\n');
      for (int k = 0; k < 3; k++) {
        text.append("  detail ").append(i).append('.').append(k).append('\n');
      }
    }
    Path file = fixture.temp.resolve(name);
    Files.writeString(file, text.toString(), StandardCharsets.US_ASCII);
    return file;
  }

  private static void collapseAt(
      ControllerFixture fixture, final DocumentController document, long line, final int folds)
      throws InterruptedException {
    document.select(Selection.caretAt(new TextPosition(document.rowOf(line), 0)));
    document.toggleFold();
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.folds().size() == folds;
          }
        });
  }

  @Test
  void aCollapsedEntryIsOneRowAndNavigationExpandsIt() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      DocumentController document = fixture.open(entries(fixture, "folds.log"));
      collapseAt(fixture, document, 6, 1);
      assertEquals(37, document.viewport().lineCount());
      assertEquals(4, document.sourceLine(4));
      assertEquals(8, document.sourceLine(5));
      assertTrue(document.folded(4));
      assertEquals(5, document.rowOf(8));
      assertEquals(4, document.selection().caret().line());

      document.goToLine(7);
      assertEquals(0, document.folds().size());
      assertEquals(6, document.sourceLine(document.selection().caret().line()));

      collapseAt(fixture, document, 4, 1);
      document.marks().add(5);
      document.goToTop();
      document.nextMark();
      assertEquals(0, document.folds().size());
      assertEquals(5, document.sourceLine(document.selection().caret().line()));

      collapseAt(fixture, document, 13, 1);
      document.search().run("detail 3.2", document.search().options());
      fixture.runUntilEnded(document.search());
      document.goToTop();
      document.search().findNext();
      assertEquals(0, document.folds().size());
      assertEquals(15, document.sourceLine(document.selection().caret().line()));

      collapseAt(fixture, document, 0, 1);
      document.showLine(2);
      assertEquals(0, document.folds().size());
    }
  }

  // Toggle pressed during classification.

  @Test
  void aToggleDuringClassificationRunsWhenItEnds() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      final DocumentController document = fixture.open(entries(fixture, "queued.log"));
      document.select(Selection.caretAt(new TextPosition(document.rowOf(1), 0)));
      document.toggleFold();
      document.select(Selection.caretAt(new TextPosition(document.rowOf(10), 0)));
      document.toggleFold();
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return document.folds().size() == 2;
            }
          });
      assertTrue(document.folds().isCollapsed(0));
      assertTrue(document.folds().isCollapsed(8));
    }
  }

  @Test
  void collapseAllFoldsTheVisibleRegionAndExpandAllRestores() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      final DocumentController document = fixture.open(entries(fixture, "all.log"));
      fixture.application.execute(CommandId.COLLAPSE_ALL_FOLDS);
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return document.folds().size() == 10;
            }
          });
      assertEquals(10, document.viewport().lineCount());
      for (int row = 0; row < 10; row++) {
        assertEquals(row * 4L, document.sourceLine(row));
      }
      fixture.application.execute(CommandId.EXPAND_ALL_FOLDS);
      assertEquals(0, document.folds().size());
      assertEquals(40, document.viewport().lineCount());
      assertEquals(
          0, fixture.budget.reserved(io.github.ajdailey.qlogg.core.concurrent.MemoryShare.FOLDS));
    }
  }

  @Test
  void copySkipsLinesHiddenInAFold() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      final DocumentController document = fixture.open(entries(fixture, "copy.log"));
      collapseAt(fixture, document, 0, 1);
      document.select(new Selection(new TextPosition(0, 0), new TextPosition(1, Long.MAX_VALUE)));
      copy(fixture, document);
      assertEquals("entry 0" + System.lineSeparator() + "entry 1", fixture.view.clipboard);
    }
  }

  private static void copy(ControllerFixture fixture, DocumentController document)
      throws InterruptedException {
    fixture.view.clipboard = null;
    document.copySelection();
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return fixture.view.clipboard != null;
          }
        });
  }

  @Test
  void blockCopyTakesTheColumnsOfEachRowJoinedByLineFeeds() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      Path file = fixture.temp.resolve("block.log");
      Files.writeString(file, "0123456789\nab\tcdef\nxy\n", StandardCharsets.US_ASCII);
      DocumentController document = fixture.open(file);
      document.selectBlock(new Selection(new TextPosition(0, 6), new TextPosition(2, 1)));
      assertTrue(document.blockSelection());
      copy(fixture, document);
      assertEquals("12345\nb    \ny", fixture.view.clipboard);
      document.select(Selection.caretAt(new TextPosition(0, 0)));
      assertFalse(document.blockSelection());

      document.selectBlock(Selection.caretAt(new TextPosition(1, 2)));
      document.execute(CommandId.EXTEND_BLOCK_DOWN);
      document.execute(CommandId.EXTEND_BLOCK_RIGHT);
      assertEquals(new TextPosition(2, 3), document.selection().caret());
      assertEquals(new TextPosition(1, 2), document.selection().anchor());
      assertTrue(document.blockSelection());
    }
  }

  @Test
  void copyWithLineNumbersPrefixesEachSourceLine() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      DocumentController document = fixture.open(fixture.lines("numbers.log", 5));
      document.select(new Selection(new TextPosition(1, 0), new TextPosition(2, Long.MAX_VALUE)));
      fixture.view.clipboard = null;
      fixture.application.execute(CommandId.COPY_WITH_LINE_NUMBERS);
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return fixture.view.clipboard != null;
            }
          });
      assertEquals("2\tline 1" + System.lineSeparator() + "3\tline 2", fixture.view.clipboard);
    }
  }

  @Test
  void wrapPinsTheFirstColumnFollowsTheFitAndRefusesBlocks() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      DocumentController document = fixture.open(fixture.lines("wrap.log", 50));
      document.setFirstColumn(3);
      fixture.application.execute(CommandId.TOGGLE_WRAP);
      assertTrue(document.wrap());
      assertTrue(document.filtered().wrap());
      assertEquals(0, document.viewport().firstColumn());
      document.scrollColumns(5);
      assertEquals(0, document.viewport().firstColumn());
      document.wrapFitted(4);
      assertEquals(4, document.viewport().rows());
      assertEquals(46, document.viewport().maxTop());
      document.selectBlock(new Selection(new TextPosition(0, 0), new TextPosition(1, 2)));
      assertFalse(document.blockSelection());
      assertTrue(fixture.view.notices.contains(UiText.NOTICE_BLOCK_WRAP.text()));
      fixture.application.execute(CommandId.TOGGLE_WRAP);
      assertFalse(document.wrap());
      assertEquals(10, document.viewport().rows());
    }
  }

  @Test
  void wrapKeepsFoldRows() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      DocumentController document = fixture.open(entries(fixture, "wrapfold.log"));
      collapseAt(fixture, document, 0, 1);
      document.setWrapBoth(true);
      assertEquals(37, document.viewport().lineCount());
      assertEquals(4, document.sourceLine(1));
    }
  }

  @Test
  void savesTheSelectionAndTheFilteredViewButNeverAnOpenFile() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      Path source = fixture.lines("save.log", 20);
      final DocumentController document = fixture.open(source);
      document.select(new Selection(new TextPosition(2, 3), new TextPosition(3, 1)));
      final Path target = fixture.temp.resolve("saved.txt");
      fixture.view.saveTarget = target;
      fixture.application.execute(CommandId.SAVE_SELECTION);
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return Files.exists(target);
            }
          });
      String separator = System.lineSeparator();
      assertEquals(
          "line 2" + separator + "hit 3", Files.readString(target, StandardCharsets.UTF_8));

      document.search().run("hit", document.search().options());
      fixture.runUntilEnded(document.search());
      final Path filtered = fixture.temp.resolve("filtered.txt");
      fixture.view.saveTarget = filtered;
      fixture.application.execute(CommandId.SAVE_FILTERED);
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return Files.exists(filtered);
            }
          });
      assertEquals(
          "hit 3" + separator + "hit 10" + separator + "hit 17",
          Files.readString(filtered, StandardCharsets.UTF_8));
      fixture.runUntilIdle();
      assertEquals(
          0, fixture.budget.reserved(io.github.ajdailey.qlogg.core.concurrent.MemoryShare.COPY));

      fixture.view.saveTarget = source;
      fixture.application.execute(CommandId.SAVE_SELECTION);
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return !fixture.view.errors.isEmpty();
            }
          });
      assertTrue(fixture.view.errors.get(0).contains(UiText.SAVE_OPEN_FILE.text()));
      assertEquals(20, Files.readAllLines(source).size());
    }
  }

  @Test
  void searchAcrossOpenFilesGroupsResultsPerFile() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      final DocumentController first = fixture.open(fixture.lines("one.log", 30));
      final DocumentController second = fixture.open(fixture.lines("two.log", 8));
      second.search().setFieldText("hit");
      fixture.application.execute(CommandId.SEARCH_ALL_FILES);
      assertEquals(1, fixture.view.multiSearchShown);
      final MultiSearchController multi = fixture.application.multiSearch();
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              for (MultiSearchController.FileResults group : multi.results()) {
                if (group.state().phase() != SearchViewState.Phase.DONE) {
                  return false;
                }
              }
              return true;
            }
          });
      List<MultiSearchController.FileResults> groups = multi.results();
      assertEquals(2, groups.size());
      assertEquals(first, groups.get(0).document());
      assertEquals(4, groups.get(0).state().matchCount());
      assertEquals(second, groups.get(1).document());
      assertEquals(1, groups.get(1).state().matchCount());
      assertEquals(5, multi.totalMatches());
      assertEquals(
          List.of(3L, 10L, 17L, 24L),
          ControllerFixture.toList(first.search().results()).subList(0, 4));

      fixture.application.closeDocument(first);
      assertEquals(1, multi.results().size());
      assertEquals(second, multi.results().get(0).document());
    }
  }

  @Test
  void aSelectorPicksTheSetByFileNameOrFirstLineAndAManualChoiceDropsIt() throws Exception {
    HighlightSet errors = new HighlightSet("errors", "Errors", List.of());
    HighlightSet web = new HighlightSet("web", "Web", List.of());
    Preferences preferences =
        Preferences.DEFAULTS
            .withHighlights(Preferences.DEFAULTS.highlights().withSets(List.of(errors, web)))
            .withView(
                ViewOptions.DEFAULTS.withSelectors(
                    List.of(
                        new HighlightSelector("*.err", "", "errors"),
                        new HighlightSelector("", "^GET ", "web"))));
    try (ControllerFixture fixture = new ControllerFixture(temp, preferences)) {
      DocumentController byName = fixture.open(fixture.lines("app.err", 3));
      assertEquals("errors", byName.autoHighlightSet());
      Path access = fixture.temp.resolve("access.log");
      Files.writeString(access, "GET /index\nPOST /x\n", StandardCharsets.US_ASCII);
      final DocumentController byLine = fixture.open(access);
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return "web".equals(byLine.autoHighlightSet());
            }
          });
      DocumentController none = fixture.open(fixture.lines("plain.log", 3));
      fixture.runUntilIdle();
      assertNull(none.autoHighlightSet());
      fixture.application.activate(byLine);
      fixture.application.toggleHighlightSet("errors");
      assertNull(byLine.autoHighlightSet());
    }
  }

  @Test
  void aCatastrophicFirstLineSelectorGivesUpAndTheNextOnePicks() throws Exception {
    HighlightSet slow = new HighlightSet("slow", "Slow", List.of());
    HighlightSet web = new HighlightSet("web", "Web", List.of());
    Preferences preferences =
        Preferences.DEFAULTS
            .withHighlights(Preferences.DEFAULTS.highlights().withSets(List.of(slow, web)))
            .withView(
                ViewOptions.DEFAULTS.withSelectors(
                    List.of(
                        new HighlightSelector("", "(.*a){12}$", "slow"),
                        new HighlightSelector("", "^a", "web"))));
    try (final ControllerFixture fixture = new ControllerFixture(temp, preferences)) {
      Path file = fixture.temp.resolve("nested.log");
      Files.writeString(file, "a".repeat(4000) + "!\nnext\n", StandardCharsets.US_ASCII);
      final DocumentController document = fixture.open(file);
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return "web".equals(document.autoHighlightSet());
            }
          });
      long started = System.nanoTime();
      fixture.application.closeDocument(document);
      assertTrue(
          Poll.until(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return fixture.budget.reserved(MemoryShare.LINE_INDEX) == 0
                      && !fixture.workers.liveThreadNames().contains(Workers.Kind.READ.prefix());
                }
              },
              ControllerFixture.BOUND));
      long millis = (System.nanoTime() - started) / 1_000_000L;
      assertTrue(millis < LogDocument.CLOSE_BOUND.toMillis(), millis + " ms");
    }
  }

  @Test
  void hideAnsiAppliesToOpenDocumentsAndTheirSearches() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      Path file = fixture.temp.resolve("ansi.log");
      Files.writeString(file, "\u001b[31mfail\u001b[0med\nok\n", StandardCharsets.US_ASCII);
      DocumentController document = fixture.open(file);
      fixture.application.execute(CommandId.HIDE_ANSI);
      assertTrue(document.document().hideAnsi());
      assertTrue(fixture.application.preferences().view().hideAnsi());
      document.search().run("failed", document.search().options());
      fixture.runUntilEnded(document.search());
      assertEquals(1, document.search().state().matchCount());
    }
  }

  @Test
  void selectionTextGoesToTheScratchpad() throws Exception {
    try (ControllerFixture fixture = new ControllerFixture(temp, Preferences.DEFAULTS)) {
      DocumentController document = fixture.open(fixture.lines("scratch.log", 4));
      fixture.application.setFocusedView(document);
      document.select(new Selection(new TextPosition(1, 0), new TextPosition(1, 4)));
      fixture.application.execute(CommandId.SEND_TO_SCRATCHPAD);
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return !fixture.view.scratchpad.isEmpty();
            }
          });
      assertEquals(List.of("line"), fixture.view.scratchpad);
    }
  }
}
