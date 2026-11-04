package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.ManualUiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationControllerTest {
  private static final Duration BOUND = Duration.ofSeconds(10);

  @TempDir Path temp;

  private static final Thread.UncaughtExceptionHandler RETHROW =
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread thread, Throwable error) {
          throw new AssertionError("worker failed", error);
        }
      };

  static DocumentServices services(Workers workers, MemoryBudget budget) {
    return services(workers, budget, StandardCharsets.ISO_8859_1);
  }

  /** Services whose encoding detection falls back to {@code fallback}. */
  static DocumentServices services(Workers workers, MemoryBudget budget, Charset fallback) {
    return new DocumentServices(
        workers,
        new Indexer(workers, IndexSettings.defaults(workers.cpuThreads(), budget)),
        new SearchEngine(
            workers,
            StandardMatchers.INSTANCE,
            SearchSettings.defaults(workers.cpuThreads(), budget)),
        new BlockCache(65536, 1 << 20),
        budget,
        fallback);
  }

  /** A store over an explicit settings directory. */
  static ConfigStore config(Path dir) {
    return new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, dir));
  }

  private ApplicationController controller(
      Workers workers, ManualUiDispatcher ui, FakeView view, MemoryBudget budget) {
    return new ApplicationController(
        workers, ui, view, services(workers, budget), config(temp), Preferences.DEFAULTS);
  }

  @Test
  void opensEachFileInATabAndShowsTheLast() throws Exception {
    Path file = Files.writeString(temp.resolve("app.log"), "one\ntwo\n");
    Path other = Files.writeString(temp.resolve("other.log"), "x\n");
    final FakeView view = new FakeView();
    ManualUiDispatcher ui = new ManualUiDispatcher();
    final MemoryBudget budget = MemoryBudget.of(1L << 28);
    final Workers workers = new Workers(1, RETHROW);
    final ApplicationController controller = controller(workers, ui, view, budget);
    final List<DocumentController> opened = new ArrayList<DocumentController>();
    final DocumentController[] shown = new DocumentController[1];
    controller.addListener(
        new ApplicationListener() {
          @Override
          public void documentOpened(DocumentController document) {
            opened.add(document);
          }

          @Override
          public void documentClosed(DocumentController document) {
            opened.remove(document);
          }

          @Override
          public void activeDocumentChanged(DocumentController document) {
            shown[0] = document;
          }
        });
    controller.open(List.of(new FileRequest(file, 0), new FileRequest(other, 0)));
    assertEquals("QLogg", view.title);
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return opened.size() == 2
                    && !opened.get(0).state().indexing()
                    && opened.get(0).lines().contains(1);
              }
            },
            BOUND));
    assertEquals("app.log", opened.get(0).state().title());
    assertEquals("two", opened.get(0).lines().text(1));
    assertEquals(opened, controller.documents());
    assertEquals(opened.get(1), controller.activeDocument());
    assertEquals(opened.get(1), shown[0]);
    assertEquals("other.log - QLogg", view.title);

    controller.execute(CommandId.PREVIOUS_TAB);
    assertEquals(opened.get(0), shown[0]);
    assertEquals("app.log - QLogg", view.title);
    DocumentController first = opened.get(0);
    controller.execute(CommandId.CLOSE);
    assertTrue(first.isClosed());
    assertEquals(1, opened.size());
    assertEquals("other.log", shown[0].state().title());
    controller.execute(CommandId.CLOSE_ALL);
    assertTrue(opened.isEmpty());
    assertNull(shown[0]);
    assertEquals("QLogg", view.title);
    shutDown(controller, ui, view, workers);
    assertEquals(0, budget.reserved(MemoryShare.LINE_INDEX));
  }

  @Test
  void aRejectedFileShowsAnError() throws Exception {
    final FakeView view = new FakeView();
    ManualUiDispatcher ui = new ManualUiDispatcher();
    Workers workers = new Workers(1, RETHROW);
    ApplicationController controller = controller(workers, ui, view, MemoryBudget.of(1L << 28));
    controller.openFile(temp, 0);
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !view.errors.isEmpty();
              }
            },
            BOUND));
    assertTrue(view.errors.get(0).startsWith("Cannot open file: "), view.errors.toString());
    assertNull(controller.activeDocument());
    shutDown(controller, ui, view, workers);
  }

  @Test
  void openingAnotherFileAddsATabAndAnOpenFileShowsItsTab() throws Exception {
    Path first = Files.writeString(temp.resolve("first.log"), "1\n");
    Path second = Files.writeString(temp.resolve("second.log"), "2\n");
    final FakeView view = new FakeView();
    view.chosen = List.of(second);
    ManualUiDispatcher ui = new ManualUiDispatcher();
    MemoryBudget budget = MemoryBudget.of(1L << 28);
    Workers workers = new Workers(1, RETHROW);
    final ApplicationController controller = controller(workers, ui, view, budget);
    controller.openFile(first, 0);
    assertTrue(ui.runUntil(titled(view, "first.log - QLogg"), BOUND));
    final DocumentController firstController = controller.activeDocument();
    controller.execute(CommandId.OPEN);
    assertTrue(ui.runUntil(titled(view, "second.log - QLogg"), BOUND));
    assertFalse(firstController.isClosed());
    assertEquals(2, controller.documents().size());
    controller.openFile(first, 0);
    assertTrue(ui.runUntil(titled(view, "first.log - QLogg"), BOUND));
    assertEquals(firstController, controller.activeDocument());
    assertEquals(2, controller.documents().size());
    controller.moveDocument(firstController, 1);
    assertEquals(firstController, controller.documents().get(1));
    shutDown(controller, ui, view, workers);
    assertTrue(firstController.document().status().closed());
    assertEquals(0, budget.reserved(MemoryShare.LINE_INDEX));
  }

  @Test
  void helpCommandsReachTheView() throws Exception {
    final FakeView view = new FakeView();
    ManualUiDispatcher ui = new ManualUiDispatcher();
    Workers workers = new Workers(1, RETHROW);
    ApplicationController controller = controller(workers, ui, view, MemoryBudget.of(1L << 28));
    controller.execute(CommandId.KEYBOARD_SHORTCUTS);
    assertTrue(view.shortcuts);
    controller.execute(CommandId.COMMAND_LINE_OPTIONS);
    assertTrue(view.texts.get(0).startsWith("Command-Line Options: Usage: qlogg"));
    controller.execute(CommandId.ABOUT);
    controller.showLicenses();
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return view.about != null && view.texts.size() == 2;
              }
            },
            BOUND));
    assertEquals("Fake toolkit", view.about.toolkit());
    assertEquals(SettingsMode.EXPLICIT, view.about.settingsMode());
    assertEquals(temp, view.about.settingsDirectory());
    assertTrue(view.texts.get(1).contains("Apache License"), view.texts.get(1));
    controller.execute(CommandId.GO_TO_LINE);
    assertEquals(-1, view.askedLineCount);
    shutDown(controller, ui, view, workers);
  }

  @Test
  void setPreferencesSavesOnAWorker() throws Exception {
    final FakeView view = new FakeView();
    ManualUiDispatcher ui = new ManualUiDispatcher();
    Workers workers = new Workers(1, RETHROW);
    ApplicationController controller = controller(workers, ui, view, MemoryBudget.of(1L << 28));
    controller.setPreferences(Preferences.DEFAULTS.withTheme(ThemeMode.SYSTEM));
    controller.setPreferences(Preferences.DEFAULTS.withTheme(ThemeMode.DARK));
    assertEquals(ThemeMode.DARK, controller.preferences().theme());
    shutDown(controller, ui, view, workers);
    assertEquals(ThemeMode.DARK, config(temp).load().theme());
  }

  @Test
  void shutdownClosesWorkersThenDisposesOnTheUiThread() throws Exception {
    final FakeView view = new FakeView();
    ManualUiDispatcher ui = new ManualUiDispatcher();
    final Workers workers = new Workers(1, RETHROW);
    ApplicationController controller = controller(workers, ui, view, MemoryBudget.of(1L << 28));
    controller.shutdown();
    controller.shutdown();
    assertTrue(view.hidden);
    assertTrue(controller.isShuttingDown());
    shutDown(controller, ui, view, workers);
    assertTrue(workers.isClosed());
  }

  private static BooleanSupplier titled(final FakeView view, final String title) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return view.title.equals(title);
      }
    };
  }

  static void shutDown(
      ApplicationController controller,
      ManualUiDispatcher ui,
      final FakeView view,
      final Workers workers)
      throws InterruptedException {
    controller.shutdown();
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return view.disposed;
              }
            },
            BOUND));
    // Threads end without posting to the UI queue, which runUntil waits on, so poll instead.
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return workers.liveThreads() == 0;
              }
            },
            BOUND));
    ui.runPending();
  }
}
