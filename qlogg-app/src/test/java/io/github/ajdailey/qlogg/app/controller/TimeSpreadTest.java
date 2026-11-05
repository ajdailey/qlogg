package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.config.TimeSettings;
import io.github.ajdailey.qlogg.app.view.OverviewListener;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.time.SpreadMode;
import io.github.ajdailey.qlogg.core.time.TimeSpread;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The overview bar's time spread: mode, samples, recompute policy and heap share. */
class TimeSpreadTest {
  private static final LocalDateTime BASE = LocalDateTime.of(2026, 3, 1, 5, 0, 0);
  private static final DateTimeFormatter LAYOUT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
  private static final int ROWS = 50;
  private static final int LINES = 1000;

  /** The first line after the pause, inside row 25 of 50. */
  private static final int PAUSE_AT = 510;

  @TempDir Path temp;

  private final AtomicInteger computed = new AtomicInteger();
  private final OverviewListener counter =
      new OverviewListener() {
        @Override
        public void binsChanged() {
          computed.incrementAndGet();
        }
      };

  private ControllerFixture fixture;

  @BeforeEach
  void start() {
    fixture =
        new ControllerFixture(
            temp,
            Preferences.DEFAULTS.withTime(
                new TimeSettings(ZoneOffset.UTC, ZoneOffset.UTC, List.of())));
  }

  @AfterEach
  void close() throws InterruptedException {
    fixture.close();
  }

  /** One line a second, with an hour's pause before {@link #PAUSE_AT}. */
  private Path paused(String name) throws IOException {
    Path file = temp.resolve(name);
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.US_ASCII)) {
      LocalDateTime time = BASE;
      for (int i = 0; i < LINES; i++) {
        time = time.plusSeconds(i == PAUSE_AT ? 3600 : 1);
        out.write(LAYOUT.format(time) + " INFO entry " + i + "\n");
      }
    }
    return file;
  }

  private void append(Path file, int lines) throws IOException {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < lines; i++) {
      text.append(LAYOUT.format(BASE.plusDays(1).plusSeconds(i))).append(" INFO more\n");
    }
    Files.writeString(file, text, StandardCharsets.US_ASCII, StandardOpenOption.APPEND);
  }

  private DocumentController shown(Path file) throws InterruptedException {
    DocumentController document = fixture.open(file);
    document.overview().setGeometry(ROWS, true);
    return document;
  }

  private void spreadOf(final DocumentController document, final int rows)
      throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.overview().spread().height() == rows;
          }
        });
  }

  private void binsCover(final DocumentController document, final long lines)
      throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.overview().bins().lineCount() == lines;
          }
        });
  }

  private void changes(final DocumentController document, final TimeSpread from)
      throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.overview().spread() != from;
          }
        });
  }

  private static int peak(TimeSpread spread) {
    int row = -1;
    for (int y = 0; y < spread.height(); y++) {
      if (row < 0 || spread.levels()[y] > spread.levels()[row]) {
        row = y;
      }
    }
    return row;
  }

  @Test
  void offComputesNothingAndPausesShadeThePauseRow() throws Exception {
    DocumentController document = shown(paused("a.log"));
    binsCover(document, LINES);
    fixture.ui.runPending();
    assertSame(TimeSpread.EMPTY, document.overview().spread());

    fixture.application.execute(CommandId.TIME_SPREAD_PAUSES);
    spreadOf(document, ROWS);
    TimeSpread spread = document.overview().spread();
    assertEquals(SpreadMode.PAUSES, spread.mode());
    assertEquals(25, peak(spread));
    assertEquals(TimeSpread.MAX_LEVEL, spread.levels()[25]);
    assertEquals(0, spread.levels()[3]);
    assertEquals(SpreadMode.PAUSES, fixture.application.preferences().view().timeSpread());
  }

  @Test
  void activityShadesTheRowsAroundThePauseLeast() throws Exception {
    DocumentController document = shown(paused("a.log"));
    fixture.application.execute(CommandId.TIME_SPREAD_ACTIVITY);
    spreadOf(document, ROWS);
    TimeSpread spread = document.overview().spread();
    assertEquals(SpreadMode.ACTIVITY, spread.mode());
    assertEquals(0, spread.levels()[25]);
    // 1000 lines over 4600 s: a row at a line a second runs 4.6 times the average.
    assertEquals(2, spread.levels()[3]);
  }

  @Test
  void offDropsTheSpreadAndItsCharge() throws Exception {
    long before = fixture.budget.reserved(MemoryShare.HIGHLIGHT);
    DocumentController document = shown(paused("a.log"));
    fixture.application.execute(CommandId.TIME_SPREAD_PAUSES);
    spreadOf(document, ROWS);
    long bins = document.overview().bins().retainedBytes();
    assertEquals(
        before + bins + TimeSpread.OVERHEAD + ROWS, fixture.budget.reserved(MemoryShare.HIGHLIGHT));
    fixture.application.execute(CommandId.TIME_SPREAD_OFF);
    assertSame(TimeSpread.EMPTY, document.overview().spread());
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return fixture.budget.reserved(MemoryShare.HIGHLIGHT)
                == before + document.overview().bins().retainedBytes();
          }
        });
  }

  @Test
  void aModeChangeRecomputesAtOnce() throws Exception {
    DocumentController document = shown(paused("a.log"));
    fixture.ui.holdTimers(OverviewController.GROWTH_MILLIS);
    fixture.application.execute(CommandId.TIME_SPREAD_PAUSES);
    spreadOf(document, ROWS);
    TimeSpread first = document.overview().spread();
    fixture.application.execute(CommandId.TIME_SPREAD_ACTIVITY);
    changes(document, first);
    assertEquals(SpreadMode.ACTIVITY, document.overview().spread().mode());
  }

  @Test
  void aNewDocumentTakesTheSavedMode() throws Exception {
    fixture.application.execute(CommandId.TIME_SPREAD_ACTIVITY);
    DocumentController document = shown(paused("a.log"));
    spreadOf(document, ROWS);
    assertEquals(SpreadMode.ACTIVITY, document.overview().spread().mode());
  }

  @Test
  void theModeReachesEveryOpenDocument() throws Exception {
    DocumentController first = shown(paused("a.log"));
    DocumentController second = shown(paused("b.log"));
    fixture.application.execute(CommandId.TIME_SPREAD_PAUSES);
    spreadOf(second, ROWS);
    first.overview().setGeometry(ROWS + 1, true);
    spreadOf(first, ROWS + 1);
  }

  @Test
  void theModeIsSaved() throws Exception {
    fixture.application.execute(CommandId.TIME_SPREAD_PAUSES);
    Path saved = Files.createDirectories(temp.resolve("saved"));
    ConfigStore store = new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, saved));
    store.save(fixture.application.preferences());
    assertEquals(SpreadMode.PAUSES, store.load().view().timeSpread());
  }

  @Test
  void aFileWithoutAFormatHasNoSpread() throws Exception {
    DocumentController document = shown(fixture.lines("plain.log", 200));
    fixture.application.execute(CommandId.TIME_SPREAD_PAUSES);
    binsCover(document, 200);
    fixture.runUntilIdle();
    fixture.ui.runPending();
    assertSame(TimeSpread.EMPTY, document.overview().spread());
  }

  @Test
  void aHiddenBarComputesNothing() throws Exception {
    DocumentController document = fixture.open(paused("a.log"));
    document.overview().setGeometry(ROWS, false);
    fixture.application.execute(CommandId.TIME_SPREAD_PAUSES);
    fixture.ui.runPending();
    assertSame(TimeSpread.EMPTY, document.overview().spread());
    document.overview().setGeometry(ROWS, true);
    spreadOf(document, ROWS);
  }

  @Test
  void aRemovedBarStopsComputingUntilTheNextGeometry() throws Exception {
    DocumentController document = shown(paused("a.log"));
    fixture.application.execute(CommandId.TIME_SPREAD_PAUSES);
    spreadOf(document, ROWS);
    document.overview().barRemoved();
    fixture.application.execute(CommandId.TIME_SPREAD_ACTIVITY);
    fixture.ui.runPending();
    assertEquals(SpreadMode.PAUSES, document.overview().spread().mode());
    TimeSpread kept = document.overview().spread();
    document.overview().setGeometry(ROWS, true);
    changes(document, kept);
    assertEquals(SpreadMode.ACTIVITY, document.overview().spread().mode());
  }

  @Test
  void aBarOfAnotherWindowKeepsTheComputationRunning() throws Exception {
    DocumentController document = shown(paused("a.log"));
    document.overview().addListener(counter);
    document.overview().barRemoved();
    fixture.application.execute(CommandId.TIME_SPREAD_PAUSES);
    spreadOf(document, ROWS);
  }

  @Test
  void aHeightChangeRecomputesDuringTheGrowthWait() throws Exception {
    DocumentController document = shown(paused("a.log"));
    fixture.ui.holdTimers(OverviewController.GROWTH_MILLIS);
    fixture.application.execute(CommandId.TIME_SPREAD_PAUSES);
    spreadOf(document, ROWS);
    document.overview().setGeometry(ROWS * 2, true);
    spreadOf(document, ROWS * 2);
    assertEquals(50, peak(document.overview().spread()));
  }

  @Test
  void aFormatChangeRecomputesAndNoFormatDropsTheSpread() throws Exception {
    DocumentController document = shown(paused("a.log"));
    fixture.ui.holdTimers(OverviewController.GROWTH_MILLIS);
    fixture.application.execute(CommandId.TIME_SPREAD_PAUSES);
    spreadOf(document, ROWS);
    fixture.application.setTimeFormat(document, null);
    assertSame(TimeSpread.EMPTY, document.overview().spread());
    fixture.application.setTimeFormat(
        document, fixture.application.preferences().time().format("iso"));
    spreadOf(document, ROWS);
  }

  @Test
  void aZoneChangeRecomputesAndAnOffsetDoesNot() throws Exception {
    DocumentController document = shown(paused("a.log"));
    fixture.ui.holdTimers(OverviewController.GROWTH_MILLIS);
    fixture.application.execute(CommandId.TIME_SPREAD_PAUSES);
    spreadOf(document, ROWS);
    TimeSpread first = document.overview().spread();
    document.time().setOffset(5_000_000_000L);
    fixture.ui.runPending();
    assertSame(first, document.overview().spread());
    TimeController time = document.time();
    time.configure(time.format(), ZoneId.of("Asia/Tokyo"), time.displayZone());
    changes(document, first);
  }

  @Test
  void growthWaitsForTheTimerAndTheWaitIsTwoSeconds() throws Exception {
    Path file = paused("a.log");
    DocumentController document = shown(file);
    fixture.ui.holdTimers(OverviewController.GROWTH_MILLIS);
    fixture.application.execute(CommandId.TIME_SPREAD_PAUSES);
    spreadOf(document, ROWS);
    TimeSpread first = document.overview().spread();
    final int more = 500;
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return fixture.ui.heldTimers() > 0;
          }
        });
    assertEquals(2000, OverviewController.GROWTH_MILLIS);

    append(file, more);
    binsCover(document, LINES + more);
    fixture.ui.runPending();
    assertSame(first, document.overview().spread());

    fixture.ui.runHeldTimers();
    changes(document, first);
    // The appended day shifts the pause; the bar's rows now cover every line.
    assertEquals(ROWS, document.overview().spread().height());
    assertNotSame(first, document.overview().spread());
  }

  @Test
  void aTimerWithoutGrowthDoesNotRecompute() throws Exception {
    DocumentController document = shown(paused("a.log"));
    fixture.ui.holdTimers(OverviewController.GROWTH_MILLIS);
    fixture.application.execute(CommandId.TIME_SPREAD_PAUSES);
    spreadOf(document, ROWS);
    TimeSpread first = document.overview().spread();
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return fixture.ui.heldTimers() > 0;
          }
        });
    fixture.ui.runHeldTimers();
    fixture.ui.runPending();
    fixture.runUntilIdle();
    assertSame(first, document.overview().spread());
    assertEquals(0, fixture.ui.heldTimers());
  }

  @Test
  void aRefusedShareShowsNoSpreadAndAFreedShareRecovers() throws Exception {
    DocumentController document = shown(paused("a.log"));
    binsCover(document, LINES);
    document.overview().addListener(counter);
    long held = fixture.budget.available(MemoryShare.HIGHLIGHT);
    assertTrue(fixture.budget.reserve(MemoryShare.HIGHLIGHT, held));
    try {
      fixture.application.execute(CommandId.TIME_SPREAD_PAUSES);
      fixture.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return computed.get() > 0;
            }
          });
      assertSame(TimeSpread.EMPTY, document.overview().spread());
    } finally {
      fixture.budget.release(MemoryShare.HIGHLIGHT, held);
    }
    document.overview().setGeometry(ROWS + 1, true);
    spreadOf(document, ROWS + 1);
  }

  @Test
  void closingReturnsTheCharge() throws Exception {
    long before = fixture.budget.reserved(MemoryShare.HIGHLIGHT);
    DocumentController document = shown(paused("a.log"));
    fixture.application.execute(CommandId.TIME_SPREAD_PAUSES);
    spreadOf(document, ROWS);
    document.overview().detach();
    assertSame(TimeSpread.EMPTY, document.overview().spread());
    assertFalse(fixture.budget.reserved(MemoryShare.HIGHLIGHT) > before);
  }
}
