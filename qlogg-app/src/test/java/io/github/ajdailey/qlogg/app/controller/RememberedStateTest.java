package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.github.ajdailey.qlogg.app.view.ManualUiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
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
      assertEquals(List.of(alpha.toRealPath().toString()), loaded.options().favorites());
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
      assertEquals(1, b.marks().size());
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
          List.of(kept.toRealPath().toString()),
          run.application.preferences().state().recentFiles());
    }
  }
}
