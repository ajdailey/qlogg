package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.view.ManualUiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs every {@link CommandId} through {@link ApplicationController#execute} with no document, with
 * a document and with its filtered view focused. No command throws on the UI thread or on a worker.
 * {@code QUIT} runs last, as the shutdown.
 */
class CommandDispatchTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private FakeView view;
  private ManualUiDispatcher ui;
  private Workers workers;
  private ApplicationController application;
  private Path file;

  @BeforeEach
  void create() throws Exception {
    view = new FakeView();
    ui = new ManualUiDispatcher();
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    application =
        new ApplicationController(
            workers,
            ui,
            view,
            ApplicationControllerTest.services(workers, MemoryBudget.of(1L << 28)),
            ApplicationControllerTest.config(temp.resolve("settings")),
            Preferences.DEFAULTS);
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 50; i++) {
      text.append(i % 7 == 3 ? "hit " : "line ").append(i).append('\n');
    }
    file = Files.writeString(temp.resolve("dispatch.log"), text.toString());
    view.saveTarget = temp.resolve("saved.log");
    view.chosen = List.of();
  }

  @AfterEach
  void shutDown() throws InterruptedException {
    application.execute(CommandId.QUIT);
    ApplicationControllerTest.shutDown(application, ui, view, workers);
    assertTrue(failures.isEmpty(), failures.toString());
  }

  /** The commands this test runs: all but {@code QUIT}. */
  private static List<CommandId> commands() {
    List<CommandId> all = new ArrayList<CommandId>();
    for (CommandId command : CommandId.values()) {
      if (command != CommandId.QUIT) {
        all.add(command);
      }
    }
    return all;
  }

  /** Runs a command and the UI queue until no operation is active. */
  private void run(final CommandId command) {
    assertDoesNotThrow(
        new Executable() {
          @Override
          public void execute() throws InterruptedException {
            application.execute(command);
            assertTrue(
                ui.runUntil(
                    new BooleanSupplier() {
                      @Override
                      public boolean getAsBoolean() {
                        return view.active.get() == 0;
                      }
                    },
                    BOUND),
                command + " left an operation active");
          }
        },
        command.name());
    assertTrue(failures.isEmpty(), command + ": " + failures);
  }

  /** Opens the file when no document shows, and selects a range in it. */
  private DocumentController shown() throws InterruptedException {
    if (application.activeDocument() == null) {
      application.open(List.of(new FileRequest(file, 0)));
      assertTrue(
          ui.runUntil(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  DocumentController document = application.activeDocument();
                  return document != null && !document.state().indexing() && view.active.get() == 0;
                }
              },
              BOUND));
      DocumentController document = application.activeDocument();
      document.viewportResized(10, 80);
      document.filtered().viewportResized(5, 80);
      document.search().setFieldText("hit");
    }
    DocumentController document = application.activeDocument();
    document.select(new Selection(new TextPosition(1, 0), new TextPosition(3, 2)));
    return document;
  }

  @Test
  void everyCommandRunsWithNoDocument() throws InterruptedException {
    for (CommandId command : commands()) {
      run(command);
      if (application.activeDocument() != null) {
        application.closeAll();
      }
    }
    assertEquals(null, application.activeDocument());
  }

  @Test
  void everyCommandRunsOnADocument() throws InterruptedException {
    for (CommandId command : commands()) {
      DocumentController document = shown();
      application.setFocusedView(document);
      run(command);
    }
  }

  @Test
  void everyCommandRunsWithTheFilteredViewFocused() throws InterruptedException {
    for (CommandId command : commands()) {
      DocumentController document = shown();
      application.setFocusedView(document.filtered());
      run(command);
    }
  }
}
