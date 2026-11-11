package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.FontSettings;
import io.github.ajdailey.qlogg.app.config.PositionStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.Session;
import io.github.ajdailey.qlogg.app.config.SessionStore;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.config.WindowState;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.ManualUiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.model.MarkComments;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Settings, the session, positions and UI state written by one run and read by the next. */
class RememberedStateTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  /** One run of the application over the settings directory. */
  private final class Run implements AutoCloseable {
    final ConfigStore config =
        new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings")));
    final FakeView view = new FakeView();
    final ManualUiDispatcher ui = new ManualUiDispatcher();
    final Workers workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                throw new AssertionError("worker failed", error);
              }
            });
    final ApplicationController application;

    Run() throws IOException {
      Files.createDirectories(temp.resolve("settings"));
      Preferences preferences = config.load();
      config.positions().load();
      application =
          new ApplicationController(
              workers,
              ui,
              view,
              ApplicationControllerTest.services(workers, MemoryBudget.of(1L << 28)),
              config,
              preferences);
    }

    void until(BooleanSupplier condition) throws InterruptedException {
      assertTrue(ui.runUntil(condition, BOUND));
    }

    void untilOpen(final int count) throws InterruptedException {
      until(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              if (application.documents().size() != count) {
                return false;
              }
              for (DocumentController document : application.documents()) {
                if (document.state().indexing()) {
                  return false;
                }
              }
              return true;
            }
          });
    }

    DocumentController tab(String title) {
      for (DocumentController document : application.documents()) {
        if (document.state().title().equals(title)) {
          return document;
        }
      }
      throw new AssertionError("no tab " + title);
    }

    @Override
    public void close() throws InterruptedException {
      ApplicationControllerTest.shutDown(application, ui, view, workers);
    }
  }

  private Path lines(String name, int count) throws IOException {
    Path file = temp.resolve(name);
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.US_ASCII)) {
      for (int i = 0; i < count; i++) {
        out.write("line " + i + "\n");
      }
    }
    return file;
  }

  @Test
  void commentsPastTheCapShowOneNoticeAtTheNextStartAndTheNextSaveClearsTheCount()
      throws Exception {
    Path first = lines("first.log", MarkComments.MAX_COMMENTS);
    Path second = lines("second.log", 5);
    try (Run run = new Run()) {
      run.application.restoreSession(Session.EMPTY);
      run.application.open(List.of(new FileRequest(first, 0), new FileRequest(second, 0)));
      run.untilOpen(2);
      for (int i = 0; i < MarkComments.MAX_COMMENTS; i++) {
        run.tab("first.log").setMarkComment(i, "flagword");
      }
      for (int i = 0; i < 3; i++) {
        run.tab("second.log").setMarkComment(i, "flagword");
      }
    }
    final String notice = UiText.NOTICE_COMMENTS_DROPPED.format(3L, MarkComments.MAX_COMMENTS);
    try (Run opened = new Run()) {
      final Run run = opened;
      Session session = run.config.sessions().load();
      assertEquals(3, session.droppedComments());
      run.application.restoreSession(session);
      run.untilOpen(2);
      run.until(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return run.view.notices.contains(notice);
            }
          });
      assertEquals(1, Collections.frequency(run.view.notices, notice));
    }
    try (Run run = new Run()) {
      Session session = run.config.sessions().load();
      assertEquals(0, session.droppedComments(), "the save after the notice clears the count");
      run.application.restoreSession(session);
      run.untilOpen(2);
      assertFalse(run.view.notices.contains(notice));
    }
  }

  @Test
  void aTabsTimeOffsetComesBackWithTheSession() throws Exception {
    Path file = lines("offset.log", 10);
    try (Run run = new Run()) {
      run.application.restoreSession(Session.EMPTY);
      run.application.open(List.of(new FileRequest(file, 0)));
      run.untilOpen(1);
      run.tab("offset.log").time().setOffset(-90_000_000_000L);
    }
    try (Run run = new Run()) {
      run.application.restoreSession(run.config.sessions().load());
      run.untilOpen(1);
      assertEquals(-90_000_000_000L, run.tab("offset.log").time().offset());
    }
  }

  @Test
  void sessionPositionsAndStateRoundTripThroughRestart() throws Exception {
    Path alpha = lines("alpha.log", 40);
    Path beta = lines("beta.log", 40);
    try (Run run = new Run()) {
      run.application.restoreSession(Session.EMPTY);
      run.application.open(List.of(new FileRequest(alpha, 0), new FileRequest(beta, 0)));
      run.untilOpen(2);
      final DocumentController a = run.tab("alpha.log");
      final DocumentController b = run.tab("beta.log");
      run.application.setTabName(a, "Alpha");
      a.setFollowing(true);
      b.select(Selection.caretAt(new TextPosition(3, 0)));
      b.toggleMarks();
      run.until(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return b.marks().contains(3) && !b.markingRunning();
            }
          });
      b.setMarkComment(9, "mock note");
      b.goToLine(21);
      run.application.activate(a);
      run.application.execute(CommandId.ADD_FAVORITE);
      run.application.activate(b);
      run.application.execute(CommandId.ZOOM_IN);
      run.application.execute(CommandId.TOGGLE_LINE_NUMBERS);
      run.view.window = new WindowState(30, 40, 700, 500, true);
    }
    Path settings = temp.resolve("settings");
    assertTrue(Files.isRegularFile(settings.resolve(SessionStore.FILE)));
    assertTrue(Files.isRegularFile(settings.resolve(PositionStore.FILE)));

    try (Run run = new Run()) {
      Preferences loaded = run.application.preferences();
      assertEquals(11, loaded.options().font().zoom());
      assertEquals(new WindowState(30, 40, 700, 500, true), loaded.state().window());
      assertFalse(loaded.state().lineNumbers());
      assertEquals(
          List.of(alpha.toAbsolutePath().normalize().toString()), loaded.options().favorites());
      assertEquals(2, loaded.state().recentFiles().size());
      Session session = run.config.sessions().load();
      assertEquals(2, session.tabs().size());
      assertEquals(1, session.active());
      run.application.restoreSession(session);
      run.untilOpen(2);
      final DocumentController b = run.tab("beta.log");
      run.until(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return run.application.activeDocument() == b;
            }
          });
      final DocumentController a = run.tab("alpha.log");
      final Session restored = session;
      run.ui.runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return "Alpha".equals(run.application.tabTitle(a));
            }
          },
          BOUND);
      assertEquals(
          "Alpha",
          run.application.tabTitle(a),
          new Supplier<String>() {
            @Override
            public String get() {
              return "session " + restored.tabs() + ", document " + a.document().path();
            }
          });
      assertTrue(a.following());
      assertTrue(b.marks().contains(3));
      assertEquals(2, b.marks().size());
      assertEquals("mock note", b.markComment(9));
      assertNull(b.markComment(3));
      assertEquals(20, b.selection().caret().line());
      assertFalse(b.lineNumbers());
    }
  }

  @Test
  void openAtLineOverridesTheRememberedPosition() throws Exception {
    Path file = lines("app.log", 40);
    try (Run run = new Run()) {
      run.application.open(List.of(new FileRequest(file, 0)));
      run.untilOpen(1);
      run.application.activeDocument().goToLine(31);
      run.application.closeAll();
    }
    try (Run run = new Run()) {
      run.application.open(List.of(new FileRequest(file, 0)));
      run.untilOpen(1);
      assertEquals(30, run.application.activeDocument().selection().caret().line());
      run.application.closeAll();
      run.application.open(List.of(new FileRequest(file, 5)));
      run.untilOpen(1);
      assertEquals(4, run.application.activeDocument().selection().caret().line());
    }
  }

  @Test
  void anEncodingOverrideReindexesAndIsRememberedPerFile() throws Exception {
    Path file = temp.resolve("wide.log");
    Files.write(file, "one\ntwo\nthree\n".getBytes(StandardCharsets.UTF_16LE));
    try (Run run = new Run()) {
      run.application.open(List.of(new FileRequest(file, 0)));
      run.untilOpen(1);
      final DocumentController before = run.application.activeDocument();
      run.application.setEncoding(before, StandardCharsets.ISO_8859_1);
      run.until(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              DocumentController now = run.application.activeDocument();
              return now != before
                  && now.state().encodingForced()
                  && !now.state().indexing()
                  && now.state().encodingName().equals("ISO-8859-1");
            }
          });
      assertEquals(4, run.application.activeDocument().state().lineCount());
    }
    try (Run run = new Run()) {
      run.application.open(List.of(new FileRequest(file, 0)));
      run.untilOpen(1);
      DocumentController reopened = run.application.activeDocument();
      assertTrue(reopened.state().encodingForced());
      assertEquals("ISO-8859-1", reopened.state().encodingName());
      run.application.execute(CommandId.ENCODING_AUTO);
      final DocumentController forced = reopened;
      run.until(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              DocumentController now = run.application.activeDocument();
              return now != forced && !now.state().encodingForced() && !now.state().indexing();
            }
          });
      assertEquals("UTF-16LE", run.application.activeDocument().state().encodingName());
    }
  }

  /** Records the WARN records {@code DocumentOpener} logs while open. */
  private static final class OpenerWarnings extends Handler implements AutoCloseable {
    final List<String> records = new ArrayList<String>();
    final Logger logger = Logger.getLogger(DocumentOpener.class.getName());

    OpenerWarnings() {
      logger.addHandler(this);
      logger.setLevel(Level.WARNING);
    }

    @Override
    public void publish(LogRecord record) {
      synchronized (records) {
        records.add(record.getMessage());
      }
    }

    @Override
    public void flush() {
      // Nothing is buffered.
    }

    List<String> messages() {
      synchronized (records) {
        return new ArrayList<String>(records);
      }
    }

    @Override
    public void close() {
      logger.removeHandler(this);
      logger.setLevel(null);
    }
  }

  @Test
  void anUnsupportedRememberedEncodingWarnsAndFallsBackToDetection() throws Exception {
    Path file = temp.resolve("wide.log");
    Files.write(file, "one\ntwo\nthree\n".getBytes(StandardCharsets.UTF_16LE));
    try (OpenerWarnings warnings = new OpenerWarnings()) {
      try (Run run = new Run()) {
        run.application.open(List.of(new FileRequest(file, 0)));
        run.untilOpen(1);
        final DocumentController detected = run.application.activeDocument();
        run.application.setEncoding(detected, StandardCharsets.ISO_8859_1);
        run.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController now = run.application.activeDocument();
                return now != detected && now.state().encodingForced() && !now.state().indexing();
              }
            });
      }
      try (Run run = new Run()) {
        run.application.open(List.of(new FileRequest(file, 0)));
        run.untilOpen(1);
        assertEquals("ISO-8859-1", run.application.activeDocument().state().encodingName());
      }
      assertEquals(List.of(), warnings.messages());

      Path positions = temp.resolve("settings").resolve(PositionStore.FILE);
      Files.writeString(
          positions, Files.readString(positions).replace("ISO-8859-1", "x-mock-unsupported"));
      try (Run run = new Run()) {
        run.application.open(List.of(new FileRequest(file, 0)));
        run.untilOpen(1);
        DocumentController reopened = run.application.activeDocument();
        assertFalse(reopened.state().encodingForced());
        assertEquals("UTF-16LE", reopened.state().encodingName());
      }
      List<String> logged = warnings.messages();
      assertEquals(1, logged.size(), logged.toString());
      assertTrue(logged.get(0).contains("x-mock-unsupported"), logged.get(0));
    }
  }

  @Test
  void zoomStaysInRangeAndResetReturnsToTheOptionsSize() throws Exception {
    try (Run run = new Run()) {
      for (int i = 0; i < 100; i++) {
        run.application.execute(CommandId.ZOOM_IN);
      }
      assertEquals(FontSettings.MAX_SIZE, run.application.preferences().options().font().zoom());
      for (int i = 0; i < 200; i++) {
        run.application.zoomFont(-1);
      }
      assertEquals(FontSettings.MIN_SIZE, run.application.preferences().options().font().zoom());
      run.application.execute(CommandId.ZOOM_RESET);
      assertEquals(0, run.application.preferences().options().font().zoom());
      assertEquals(10, run.application.preferences().options().font().effectiveSize(10));
    }
  }

  @Test
  void anInstanceThatDidNotLoadTheSessionLeavesItAlone() throws Exception {
    Path file = lines("app.log", 5);
    try (Run run = new Run()) {
      run.application.open(List.of(new FileRequest(file, 0)));
      run.untilOpen(1);
    }
    assertFalse(Files.exists(temp.resolve("settings").resolve(SessionStore.FILE)));
  }

  @Test
  void cleanUpDropsMissingRecentFiles() throws Exception {
    final Path gone = lines("gone.log", 3);
    Path kept = lines("kept.log", 3);
    try (final Run run = new Run()) {
      run.application.open(List.of(new FileRequest(gone, 0), new FileRequest(kept, 0)));
      run.untilOpen(2);
      run.application.closeAll();
      Files.delete(gone);
      run.application.execute(CommandId.CLEAN_RECENT);
      run.until(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return run.application.preferences().state().recentFiles().size() == 1;
            }
          });
      assertEquals(
          List.of(kept.toAbsolutePath().normalize().toString()),
          run.application.preferences().state().recentFiles());
    }
  }

  // Recently Closed and Open All.

  @Test
  void recentlyClosedIsSavedAndReopensAtTheRememberedPosition() throws Exception {
    Path alpha = lines("alpha.log", 40);
    String path = alpha.toAbsolutePath().normalize().toString();
    try (Run run = new Run()) {
      run.application.open(List.of(new FileRequest(alpha, 0)));
      run.untilOpen(1);
      run.tab("alpha.log").goToLine(21);
      run.application.execute(CommandId.CLOSE);
      assertEquals(List.of(path), run.application.preferences().state().closedFiles());
    }
    try (final Run run = new Run()) {
      assertEquals(List.of(path), run.application.preferences().state().closedFiles());
      run.application.execute(CommandId.OPEN_ALL_CLOSED);
      run.untilOpen(1);
      final DocumentController reopened = run.tab("alpha.log");
      run.until(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return reopened.selection().caret().line() == 20;
            }
          });
      assertEquals(List.of(), run.application.preferences().state().closedFiles());
      run.application.execute(CommandId.CLOSE);
      assertEquals(List.of(path), run.application.preferences().state().closedFiles());
      run.application.execute(CommandId.CLEAR_CLOSED);
      assertEquals(List.of(), run.application.preferences().state().closedFiles());
    }
  }

  @Test
  void openAllShowsAnOpenFileOpensAClosedOneAndNamesAMissingOne() throws Exception {
    Path open = lines("open.log", 3);
    Path closed = lines("closed.log", 3);
    Path missing = lines("missing.log", 3);
    try (final Run run = new Run()) {
      run.application.open(
          List.of(
              new FileRequest(missing, 0), new FileRequest(closed, 0), new FileRequest(open, 0)));
      run.untilOpen(3);
      run.application.closeDocument(run.tab("missing.log"));
      run.application.closeDocument(run.tab("closed.log"));
      Files.delete(missing);
      assertEquals(3, run.application.preferences().state().recentFiles().size());
      run.application.execute(CommandId.OPEN_ALL_RECENT);
      run.until(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return !run.view.notices.isEmpty()
                  && run.application.documents().size() == 2
                  && !run.application.activeDocument().state().indexing();
            }
          });
      assertEquals(
          UiText.NOTICE_OPEN_ALL_SKIPPED.format(1, "missing.log"),
          run.view.notices.get(run.view.notices.size() - 1));
      assertEquals("closed.log", run.application.activeDocument().state().title());
      assertEquals(List.of(), run.view.errors);
      assertEquals(
          List.of(missing.toAbsolutePath().normalize().toString()),
          run.application.preferences().state().closedFiles());
    }
  }
}
