package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.TimeSettings;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.model.OverviewBins;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.time.TimeSpread;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The overview bar over collapsed entries: bins, clicks and the time spread agree on rows. */
class OverviewFoldsTest {
  private static final SearchOptions LITERAL =
      new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, false, false);
  private static final LocalDateTime BASE = LocalDateTime.of(2026, 3, 1, 5, 0, 0);
  private static final DateTimeFormatter LAYOUT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
  private static final int LINES = 1000;

  /** Two entries of 100 lines, each 99 hidden lines when collapsed. */
  private static final int FIRST = 300;

  private static final int SECOND = 500;
  private static final int ENTRY = 100;

  /** Inside {@link #FIRST}. */
  private static final int HIDDEN_HIT = 350;

  /** Past the row count with one entry collapsed. */
  private static final int LATE_HIT = 950;

  /** The first line after an hour's pause, between the entries. */
  private static final int PAUSE_AT = 450;

  /** The row count with one entry collapsed; a bar of this height has a pixel per row. */
  private static final int ROWS = LINES - ENTRY + 1;

  @TempDir Path temp;

  private ControllerFixture fixture;
  private DocumentController document;

  @BeforeEach
  void start() throws Exception {
    fixture =
        new ControllerFixture(
            temp,
            Preferences.DEFAULTS.withTime(
                new TimeSettings(ZoneOffset.UTC, ZoneOffset.UTC, List.of())));
    document = fixture.open(log());
  }

  @AfterEach
  void close() throws InterruptedException {
    fixture.close();
  }

  private static boolean inEntry(int line) {
    return line > FIRST && line < FIRST + ENTRY || line > SECOND && line < SECOND + ENTRY;
  }

  /** One stamped line a second; a continuation line has no stamp. */
  private Path log() throws IOException {
    Path file = temp.resolve("folds.log");
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.US_ASCII)) {
      for (int i = 0; i < LINES; i++) {
        String hit = i == HIDDEN_HIT || i == LATE_HIT ? "hit " : "";
        if (inEntry(i)) {
          out.write("    at " + hit + "frame " + i + "\n");
        } else {
          LocalDateTime time = BASE.plusSeconds(i + (i >= PAUSE_AT ? 3600 : 0));
          out.write(LAYOUT.format(time) + " INFO " + hit + "entry " + i + "\n");
        }
      }
    }
    return file;
  }

  private void toggleAt(long line, final int folds) throws InterruptedException {
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

  private void searchHits() throws InterruptedException {
    document.search().run("hit", LITERAL);
    fixture.runUntilEnded(document.search());
  }

  /** Waits for bins of a height with a match in each pixel given and nowhere else. */
  private void matchesIn(final int height, final int... pixels) throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            OverviewBins bins = document.overview().bins();
            if (bins.height() != height || bins.lineCount() != LINES) {
              return false;
            }
            int found = 0;
            for (int pixel : pixels) {
              found += bins.matches()[pixel];
            }
            return found == pixels.length && total(bins) == pixels.length;
          }
        });
  }

  private static int total(OverviewBins bins) {
    int sum = 0;
    for (int count : bins.matches()) {
      sum += count;
    }
    return sum;
  }

  private void peakAt(final int row) throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            TimeSpread spread = document.overview().spread();
            return spread.height() == ROWS && spread.levels()[row] == TimeSpread.MAX_LEVEL;
          }
        });
  }

  private long caretLine() {
    return document.sourceLine(document.selection().caret().line());
  }

  @Test
  void withoutFoldsThePixelsSplitTheLines() throws Exception {
    searchHits();
    document.overview().setGeometry(100, true);
    matchesIn(
        100, OverviewBins.rowOf(HIDDEN_HIT, LINES, 100), OverviewBins.rowOf(LATE_HIT, LINES, 100));
    document.overview().jumpTo(95);
    assertEquals(950, caretLine());
  }

  @Test
  void aFoldChangeRefreshesTheBinsAndHiddenLinesCountInTheEntrysPixel() throws Exception {
    searchHits();
    document.overview().setGeometry(ROWS, true);
    matchesIn(
        ROWS,
        OverviewBins.rowOf(HIDDEN_HIT, LINES, ROWS),
        OverviewBins.rowOf(LATE_HIT, LINES, ROWS));
    toggleAt(FIRST, 1);
    matchesIn(ROWS, FIRST, LATE_HIT - ENTRY + 1);
    document.overview().setGeometry(100, false);
    document.overview().setGeometry(100, true);
    matchesIn(
        100, OverviewBins.rowOf(FIRST, ROWS, 100), OverviewBins.rowOf(LATE_HIT - 99, ROWS, 100));
  }

  @Test
  void aClickFollowsTheRowsAndLeavesTheFoldCollapsed() throws Exception {
    toggleAt(FIRST, 1);
    document.overview().setGeometry(ROWS, true);
    document.overview().jumpTo(LATE_HIT - ENTRY + 1);
    assertEquals(LATE_HIT, caretLine());
    document.overview().jumpTo(FIRST + 1);
    assertEquals(FIRST + ENTRY, caretLine());
    document.overview().jumpTo(FIRST);
    assertEquals(FIRST, caretLine());
    document.overview().jumpTo(ROWS + 50);
    assertEquals(LINES - 1, caretLine());
    assertEquals(1, document.folds().size());
  }

  @Test
  void theSpreadRowsFollowTheFolds() throws Exception {
    fixture.ui.holdTimers(OverviewController.GROWTH_MILLIS);
    document.overview().setGeometry(ROWS, true);
    fixture.application.execute(CommandId.TIME_SPREAD_PAUSES);
    // The pause lies in the row of the line before it.
    peakAt(OverviewBins.rowOf(PAUSE_AT - 1, LINES, ROWS));
    toggleAt(FIRST, 1);
    fixture.ui.runHeldTimers();
    peakAt(PAUSE_AT - ENTRY);
    // A swap of equal entries keeps the row count and still moves the rows.
    toggleAt(SECOND, 2);
    toggleAt(FIRST, 1);
    fixture.ui.runHeldTimers();
    peakAt(PAUSE_AT - 1);
  }
}
