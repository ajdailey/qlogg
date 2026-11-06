package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.TimeSettings;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.TimeStatus;
import io.github.ajdailey.qlogg.app.view.TimeText;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.time.UserFormat;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Per-document time state, caret times and go to time, driven through the application. */
class TimeControllerTest {
  private static final LocalDateTime BASE = LocalDateTime.of(2026, 3, 1, 5, 0, 0);
  private static final DateTimeFormatter LAYOUT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

  @TempDir Path temp;

  private ControllerFixture fixture;

  /** 0-based line of each entry in the last file written. */
  private final List<Long> entryLines = new ArrayList<Long>();

  /** Opened after each test; holds the tasks of {@link #holdCpuPool}. */
  private final CountDownLatch cpu = new CountDownLatch(1);

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
    cpu.countDown();
    fixture.close();
  }

  /** Writes entries one second apart from {@code first}, each third followed by two trace lines. */
  private Path timed(String name, int count, LocalDateTime first) throws IOException {
    entryLines.clear();
    Path file = temp.resolve(name);
    long line = 0;
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.US_ASCII)) {
      for (int i = 0; i < count; i++) {
        entryLines.add(line++);
        out.write(LAYOUT.format(first.plusSeconds(i)) + " INFO entry " + i + "\n");
        if (i % 3 == 2) {
          out.write("    at frame one\n    at frame two\n");
          line += 2;
        }
      }
    }
    return file;
  }

  private static long nanos(LocalDateTime time) {
    Instant instant = time.toInstant(ZoneOffset.UTC);
    return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
  }

  private void caretTimeRead(final LineViewController view, final long expected)
      throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.timeStatus().caret() == expected;
          }
        });
  }

  private void searched(final DocumentController document) throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !document.time().running() && fixture.view.active.get() == 0;
          }
        });
  }

  @Test
  void detectionAtOpenGivesTheCaretTimeAndTheSelectionDifference() throws Exception {
    DocumentController document = fixture.open(timed("a.log", 30, BASE));
    assertSame(BuiltInFormat.ISO, document.time().format());
    document.select(Selection.caretAt(new TextPosition(entryLines.get(5), 0)));
    caretTimeRead(document, nanos(BASE.plusSeconds(5)));
    TimeController time = document.time();
    assertEquals(
        "2026-03-01 05:00:05.000",
        TimeText.caret(document.timeStatus(), time.offset(), time.displayZone()));
    // A trace line takes the time of the entry above it.
    document.select(Selection.caretAt(new TextPosition(entryLines.get(2) + 2, 0)));
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.timeStatus().caret() == nanos(BASE.plusSeconds(2));
          }
        });
    document.select(
        new Selection(
            new TextPosition(entryLines.get(4), 0), new TextPosition(entryLines.get(14), 3)));
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return TimeText.difference(document.timeStatus()).equals("+00:00:10.000");
          }
        });
    time.setOffset(-3_600_000_000_000L);
    assertEquals(
        "2026-03-01 04:00:14.000",
        TimeText.caret(document.timeStatus(), time.offset(), time.displayZone()));
    assertEquals("+00:00:10.000", TimeText.difference(document.timeStatus()));
  }

  @Test
  void goToTimeMovesTheCaretReleasesFollowAndPairsTheActivity() throws Exception {
    DocumentController document = fixture.open(timed("b.log", 400, BASE));
    document.setFollowing(true);
    assertTrue(document.following());
    int before = fixture.view.activations.get();
    document.time().goToTime(nanos(BASE.plusSeconds(37)));
    searched(document);
    assertEquals(entryLines.get(37).longValue(), document.selection().caret().line());
    assertFalse(document.following());
    assertEquals(before + 1, fixture.view.activations.get());
    assertEquals(0, fixture.view.active.get());
    // Between two entries: the last line before the target.
    document.time().goToTime(nanos(BASE.plusSeconds(120)) + 500_000_000L);
    searched(document);
    assertEquals(entryLines.get(120).longValue(), document.selection().caret().line());
    // The offset is subtracted from the target.
    document.time().setOffset(10_000_000_000L);
    document.time().goToTime(nanos(BASE.plusSeconds(60)));
    searched(document);
    assertEquals(entryLines.get(50).longValue(), document.selection().caret().line());
    // Closing the tab returns the order check's charge with the views' spans.
    fixture.application.closeDocument(document);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return fixture.budget.reserved(MemoryShare.HIGHLIGHT) == 0;
          }
        });
  }

  @Test
  void aTargetBeforeTheFirstStampGoesToItWithANotice() throws Exception {
    DocumentController document = fixture.open(timed("c.log", 20, BASE));
    document.select(Selection.caretAt(new TextPosition(30, 0)));
    document.time().goToTime(nanos(BASE.minusDays(1)));
    searched(document);
    assertEquals(0, document.selection().caret().line());
    assertEquals(UiText.NOTICE_TIME_BEFORE_FIRST.text(), fixture.view.lastNotice());
  }

  @Test
  void anOutOfOrderFileIsSearchedLineByLine() throws Exception {
    Path file = temp.resolve("d.log");
    Path earlier = timed("earlier.log", 30, BASE.minusDays(1));
    Path later = timed("later.log", 30, BASE);
    Files.write(file, Files.readAllBytes(later));
    Files.write(file, Files.readAllBytes(earlier), StandardOpenOption.APPEND);
    DocumentController document = fixture.open(file);
    // Bisection would land in the earlier day at the end; the scan finds the entry in file order.
    document.time().goToTime(nanos(BASE.plusSeconds(12)));
    searched(document);
    assertEquals(entryLines.get(12).longValue(), document.selection().caret().line());
    assertEquals(UiText.NOTICE_TIME_OUT_OF_ORDER.text(), fixture.view.lastNotice());
  }

  /** 1,000 lines a second apart whose 499th and 500th are a day early, between two samples. */
  private Path blockAtTheFirstProbe() throws IOException {
    Path file = temp.resolve("block.log");
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.US_ASCII)) {
      for (int i = 0; i < 1000; i++) {
        LocalDateTime day = i == 499 || i == 500 ? BASE.minusDays(1) : BASE;
        out.write(LAYOUT.format(day.plusSeconds(i)) + " INFO entry\n");
      }
    }
    return file;
  }

  @Test
  void aProbeOutsideItsBracketSearchesLineByLine() throws Exception {
    DocumentController document = fixture.open(blockAtTheFirstProbe());
    // Bisection alone would land on the block, the last stamp under the target it sees.
    document.time().goToTime(nanos(BASE.plusSeconds(300)));
    searched(document);
    assertEquals(300, document.selection().caret().line());
    assertEquals(UiText.NOTICE_TIME_OUT_OF_ORDER.text(), fixture.view.lastNotice());
  }

  @Test
  void aRefusedOrderCheckSearchesLineByLineWithoutTheOrderNotice() throws Exception {
    Path file = temp.resolve("d.log");
    Path earlier = timed("earlier.log", 30, BASE.minusDays(1));
    Path later = timed("later.log", 30, BASE);
    Files.write(file, Files.readAllBytes(later));
    Files.write(file, Files.readAllBytes(earlier), StandardOpenOption.APPEND);
    DocumentController document = fixture.open(file);
    long held = 0;
    for (long size = 1L << 40; size > 0; size >>= 1) {
      if (fixture.budget.reserve(MemoryShare.HIGHLIGHT, size)) {
        held += size;
      }
    }
    try {
      document.time().goToTime(nanos(BASE.plusSeconds(12)));
      searched(document);
    } finally {
      fixture.budget.release(MemoryShare.HIGHLIGHT, held);
    }
    assertEquals(entryLines.get(12).longValue(), document.selection().caret().line());
    assertFalse(fixture.view.notices.contains(UiText.NOTICE_TIME_OUT_OF_ORDER.text()));
  }

  @Test
  void aFormatOrZoneChangeDropsARunningGoToTime() throws Exception {
    DocumentController document = fixture.open(timed("a.log", 30, BASE));
    TimeController time = document.time();
    // Its result can post but not apply: only this thread drains the queue.
    time.goToTime(nanos(BASE.plusSeconds(12)));
    time.configure(time.format(), ZoneOffset.ofHours(2), time.displayZone());
    searched(document);
    assertEquals(0, document.selection().caret().line());
    assertFalse(fixture.view.notices.contains(UiText.NOTICE_TIME_CANCELED.text()));
  }

  @Test
  void aNewerRequestDropsTheOlderResult() throws Exception {
    DocumentController document = fixture.open(timed("e.log", 200, BASE));
    document.time().goToTime(nanos(BASE.plusSeconds(10)));
    document.time().goToTime(nanos(BASE.plusSeconds(150)));
    searched(document);
    assertEquals(entryLines.get(150).longValue(), document.selection().caret().line());
    assertEquals(0, fixture.view.active.get());
  }

  @Test
  void escCancelsAGoToTime() throws Exception {
    DocumentController document = fixture.open(timed("f.log", 200, BASE));
    document.select(Selection.caretAt(new TextPosition(3, 0)));
    document.time().goToTime(nanos(BASE.plusSeconds(150)));
    fixture.application.setFocusedView(document);
    fixture.application.execute(CommandId.STOP);
    searched(document);
    assertEquals(3, document.selection().caret().line());
    assertEquals(UiText.NOTICE_TIME_CANCELED.text(), fixture.view.lastNotice());
    assertEquals(0, fixture.view.active.get());
  }

  @Test
  void aFileWithoutTimestampsDisablesTheCommands() throws Exception {
    DocumentController document = fixture.open(fixture.lines("plain.log", 20));
    assertNull(document.time().format());
    CommandStates states = new CommandStates(fixture.application);
    assertFalse(states.enabled(CommandId.GO_TO_TIME));
    assertFalse(states.enabled(CommandId.TIME_OFFSET));
    assertTrue(states.enabled(CommandId.TIMESTAMP_FORMATS));
    document.time().goToTime(nanos(BASE));
    assertEquals(UiText.NOTICE_TIME_NO_FORMAT.text(), fixture.view.lastNotice());
    assertEquals(0, fixture.view.active.get());
    fixture.application.setTimeFormat(document, BuiltInFormat.ISO);
    assertTrue(states.enabled(CommandId.GO_TO_TIME));
  }

  @Test
  void aRememberedChoiceSkipsDetection() throws Exception {
    Path file = timed("g.log", 20, BASE);
    DocumentController document = fixture.open(file);
    UserFormat user = new UserFormat("mine", "^(?<ts>\\S+ \\S+)", "yyyy-MM-dd HH:mm:ss.SSS");
    fixture.application.setUserTimeFormats(List.of(user));
    fixture.application.setTimeFormat(document, null);
    assertNull(document.time().format());
    fixture.application.closeDocument(document);
    document = fixture.open(file);
    assertNull(document.document().timeFormat());
    assertNull(document.time().format());
    fixture.application.setTimeFormat(document, user);
    fixture.application.closeDocument(document);
    document = fixture.open(file);
    assertNull(document.document().timeFormat());
    assertEquals(user, document.time().format());
  }

  @Test
  void theDialogsSetTheTargetTheOffsetAndTheZone() throws Exception {
    DocumentController document = fixture.open(timed("h.log", 60, BASE));
    fixture.application.setFocusedView(document);
    document.select(Selection.caretAt(new TextPosition(entryLines.get(8), 0)));
    caretTimeRead(document, nanos(BASE.plusSeconds(8)));
    fixture.application.execute(CommandId.GO_TO_TIME);
    assertEquals("2026-03-01 05:00:08.000", fixture.view.timeInitial);
    assertEquals(BASE.toLocalDate(), fixture.view.caretDate);
    fixture.view.timeListener.timeChosen(nanos(BASE.plusSeconds(40)));
    searched(document);
    assertEquals(entryLines.get(40).longValue(), document.selection().caret().line());

    caretTimeRead(document, nanos(BASE.plusSeconds(40)));
    fixture.application.execute(CommandId.TIME_OFFSET);
    assertEquals(0, fixture.view.askedOffset);
    assertEquals(nanos(BASE.plusSeconds(40)), fixture.view.askedCaret);
    fixture.view.timeListener.timeChosen(90_000_000_000L);
    assertEquals(90_000_000_000L, document.time().offset());
    WindowSet.WindowDraft draft = fixture.application.draft();
    assertEquals(90_000_000_000L, draft.tabs().get(0).timeOffset());

    fixture.application.execute(CommandId.TIME_ZONE);
    assertEquals("", fixture.view.zoneCurrent);
    fixture.view.nameListener.nameChosen("Europe/Berlin");
    assertEquals("Europe/Berlin", document.time().zone().getId());
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.timeStatus().caret() == nanos(BASE.plusSeconds(40).minusHours(1));
          }
        });
  }

  @Test
  void aReloadKeepsTheOffset() throws Exception {
    DocumentController document = fixture.open(timed("i.log", 20, BASE));
    document.time().setOffset(5_000_000_000L);
    fixture.application.reload(document);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = fixture.application.activeDocument();
            return shown != document && shown != null && !shown.state().indexing();
          }
        });
    assertEquals(5_000_000_000L, fixture.application.activeDocument().time().offset());
  }

  /** Occupies the CPU pool, so the indexing of the next growth holds until {@link #cpu} opens. */
  private void holdCpuPool() {
    for (int i = 0; i < fixture.workers.cpuThreads(); i++) {
      fixture.workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              try {
                cpu.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
          });
    }
  }

  /** Opens a file, then appends to it with the CPU pool held: indexing runs and stays running. */
  private DocumentController growingDocument(String name) throws Exception {
    Path file = timed(name, 40, BASE);
    final DocumentController document = fixture.open(file);
    holdCpuPool();
    Files.writeString(
        file,
        LAYOUT.format(BASE.plusSeconds(500)) + " INFO appended\n",
        StandardCharsets.US_ASCII,
        StandardOpenOption.APPEND);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.state().indexing();
          }
        });
    return document;
  }

  private void searchEnded(final DocumentController document) throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !document.time().running();
          }
        });
  }

  @Test
  void aTargetAfterTheLastStampWhileIndexingRunsSaysSo() throws Exception {
    DocumentController document = growingDocument("j.log");
    document.time().goToTime(nanos(BASE.plusDays(1)));
    searchEnded(document);
    assertEquals(entryLines.get(39).longValue(), document.selection().caret().line());
    assertEquals(UiText.NOTICE_TIME_INDEXED_PART.text(), fixture.view.lastNotice());
    assertTrue(document.document().status().indexing());
  }

  @Test
  void escDuringAGoToTimeLeavesLoadingForTheSecondPress() throws Exception {
    DocumentController document = growingDocument("k.log");
    fixture.application.setFocusedView(document);
    document.time().goToTime(nanos(BASE.plusSeconds(20)));
    fixture.application.execute(CommandId.STOP);
    assertFalse(document.document().status().loadingStopped());
    searchEnded(document);
    assertEquals(UiText.NOTICE_TIME_CANCELED.text(), fixture.view.lastNotice());
    assertTrue(document.document().status().indexing());
    assertFalse(document.document().status().loadingStopped());

    fixture.application.execute(CommandId.STOP);
    assertTrue(document.document().status().loadingStopped());
    assertEquals(
        UiText.NOTICE_LOADING_STOPPED.format(document.document().index().lineCount()),
        fixture.view.lastNotice());
  }

  /**
   * The save holds at the gated line. The go to time stays running until its result drains, and the
   * samples, every second line, stay off the gated line.
   */
  @Test
  void theFirstEscDuringAGoToTimeAlsoCancelsASave() throws Exception {
    final GatedDecodeCharset gate = new GatedDecodeCharset();
    try {
      fixture.close();
      fixture =
          new ControllerFixture(
              temp,
              Preferences.DEFAULTS.withTime(
                  new TimeSettings(ZoneOffset.UTC, ZoneOffset.UTC, List.of())),
              gate);
      Path file = temp.resolve("save.log");
      try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.ISO_8859_1)) {
        for (int i = 0; i < 255 * 2 + 1; i++) {
          String stamp = LAYOUT.format(BASE.plusSeconds(i));
          out.write(i == 301 ? "Gate line\n" : stamp + (i == 0 ? " caf\u00e9\n" : " entry\n"));
        }
      }
      final DocumentController document = fixture.open(file);
      Path target = temp.resolve("saved.log");
      document.saveAll(target);
      assertTrue(gate.awaitHeld());
      document.time().goToTime(nanos(BASE.plusSeconds(5)));
      fixture.application.setFocusedView(document);
      fixture.application.execute(CommandId.STOP);
      assertFalse(document.document().status().loadingStopped());
      gate.open();
      fixture.runUntilIdle();
      assertTrue(Files.notExists(target));
      assertEquals(UiText.NOTICE_TIME_CANCELED.text(), fixture.view.lastNotice());
    } finally {
      gate.open();
    }
  }

  /**
   * A linear scan posts a progress notice once {@link TimeController#PROGRESS_MILLIS} has passed at
   * a report every 4 MiB. The gate holds the scan at a line before the first report, past that
   * interval, so the first report posts. The held line is off the order check's samples, each 200
   * lines, and past the detection head.
   */
  @Test
  void aLinearScanReportsProgress() throws Exception {
    final GatedDecodeCharset gate = new GatedDecodeCharset();
    try {
      fixture.close();
      fixture =
          new ControllerFixture(
              temp,
              Preferences.DEFAULTS.withTime(
                  new TimeSettings(ZoneOffset.UTC, ZoneOffset.UTC, List.of())),
              gate);
      int lines = 255 * 200 + 1;
      String padding = "x".repeat(80);
      Path file = temp.resolve("scan.log");
      try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.ISO_8859_1)) {
        for (int i = 0; i < lines; i++) {
          if (i == 5001) {
            out.write("Gate line\n");
          } else {
            // The second half starts a day earlier, so the order check descends.
            LocalDateTime time = (i < lines / 2 ? BASE.plusDays(1) : BASE).plusSeconds(i);
            out.write(LAYOUT.format(time) + (i == 0 ? " caf\u00e9 " : " ") + padding + "\n");
          }
        }
      }
      final DocumentController document = fixture.open(file);
      document.time().goToTime(nanos(BASE.plusDays(30)));
      assertTrue(gate.awaitHeld());
      // The elapsed interval is the input here, not a state another thread produces.
      Thread.sleep(TimeController.PROGRESS_MILLIS + 50);
      gate.open();
      searchEnded(document);
      boolean reported = false;
      for (int percent = 1; percent < 100; percent++) {
        reported |= fixture.view.notices.contains(UiText.NOTICE_TIME_PROGRESS.format(percent));
      }
      assertTrue(reported, fixture.view.notices.toString());
      assertEquals(UiText.NOTICE_TIME_OUT_OF_ORDER.text(), fixture.view.lastNotice());
    } finally {
      gate.open();
    }
  }

  @Test
  void theFilteredViewReadsTheTimesOfItsRowsSourceLines() throws Exception {
    final DocumentController document = fixture.open(timed("l.log", 30, BASE));
    document.search().run("entry 1", document.search().options());
    fixture.runUntilEnded(document.search());
    final FilteredController filtered = document.filtered();
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return filtered.viewport().lineCount() == 11;
          }
        });
    // Rows 0 and 10 are entries 1 and 19.
    filtered.select(new Selection(new TextPosition(0, 0), new TextPosition(10, 2)));
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return TimeText.difference(filtered.timeStatus()).equals("+00:00:18.000");
          }
        });
    assertEquals(nanos(BASE.plusSeconds(19)), filtered.timeStatus().caret());
    assertEquals(nanos(BASE.plusSeconds(1)), filtered.timeStatus().first());
    assertEquals(nanos(BASE.plusSeconds(19)), filtered.timeStatus().last());
  }

  @Test
  void aCloseDuringATimeReadDropsTheReadAndTheQueuedOne() throws Exception {
    final DocumentController document = fixture.open(timed("m.log", 30, BASE));
    document.select(Selection.caretAt(new TextPosition(entryLines.get(5), 0)));
    caretTimeRead(document, nanos(BASE.plusSeconds(5)));
    TimeStatus shown = document.timeStatus();
    // This thread drains the UI queue, so the first read's post-back runs after the close.
    document.select(Selection.caretAt(new TextPosition(entryLines.get(7), 0)));
    document.select(Selection.caretAt(new TextPosition(entryLines.get(9), 0)));
    fixture.application.closeDocument(document);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            boolean open = document.document().enter();
            if (open) {
              document.document().exit();
            }
            return !open;
          }
        });
    fixture.runUntilIdle();
    assertSame(shown, document.timeStatus());
  }

  /** Holds a read's post-back until the test runs it. */
  private static final class HeldPost implements UiDispatcher {
    private final CountDownLatch arrived = new CountDownLatch(1);
    private volatile Runnable task;

    @Override
    public void asyncExec(Runnable posted) {
      task = posted;
      arrived.countDown();
    }

    @Override
    public void timerExec(int delayMillis, Runnable posted) {
      asyncExec(posted);
    }

    @Override
    public boolean isUiThread() {
      return true;
    }

    void await() throws InterruptedException {
      assertTrue(arrived.await(20, TimeUnit.SECONDS));
    }
  }

  @Test
  void aReadThatPostedBackBeforeTheCloseIsNotApplied() throws Exception {
    DocumentController document = fixture.open(timed("n.log", 30, BASE));
    HeldPost open = new HeldPost();
    CaretTimes applied = new CaretTimes(document, fixture.workers, open);
    applied.request();
    open.await();
    open.task.run();
    assertEquals(nanos(BASE), applied.status().caret());

    HeldPost closing = new HeldPost();
    CaretTimes dropped = new CaretTimes(document, fixture.workers, closing);
    dropped.request();
    closing.await();
    fixture.application.closeDocument(document);
    assertTrue(document.isClosed());
    closing.task.run();
    assertSame(TimeStatus.NONE, dropped.status());
  }
}
