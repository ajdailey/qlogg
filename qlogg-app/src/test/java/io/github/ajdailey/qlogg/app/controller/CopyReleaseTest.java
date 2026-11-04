package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.view.ManualUiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Copy and save return their {@link MemoryShare#COPY} charge and end their activity on every
 * refusal path: a selection over the share, a save whose write fails, and a tab closed while the
 * copy runs.
 */
class CopyReleaseTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private MemoryBudget budget;
  private FakeView view;
  private ManualUiDispatcher ui;
  private Workers workers;
  private ApplicationController application;

  private void create(MemoryBudget limit) {
    budget = limit;
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
            ApplicationControllerTest.services(workers, budget),
            ApplicationControllerTest.config(temp.resolve("settings")),
            Preferences.DEFAULTS);
  }

  @AfterEach
  void shutDown() throws InterruptedException {
    ApplicationControllerTest.shutDown(application, ui, view, workers);
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private DocumentController open(int lines) throws IOException, InterruptedException {
    Path file = temp.resolve("copy.log");
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.US_ASCII)) {
      for (int i = 1; i <= lines; i++) {
        out.write("line " + i + "\n");
      }
    }
    application.open(List.of(new FileRequest(file, 0)));
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController document = application.activeDocument();
                return document != null && !document.state().indexing() && idle();
              }
            },
            BOUND));
    return application.activeDocument();
  }

  private boolean idle() {
    return view.active.get() == 0;
  }

  private void runUntilReleased() throws InterruptedException {
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return idle() && budget.reserved(MemoryShare.COPY) == 0;
              }
            },
            BOUND));
  }

  @Test
  void aSaveOverTheShareIsRefusedAndReleased() throws Exception {
    create(MemoryBudget.of(1L << 20));
    long cap = DocumentController.maxCopyBytes(budget.limit(MemoryShare.COPY));
    DocumentController document = open((int) (cap / 8));
    document.saveAll(temp.resolve("all.log"));
    runUntilReleased();
    assertEquals(1, view.errors.size(), view.errors.toString());
    assertTrue(view.errors.get(0).startsWith("Save: "), view.errors.toString());
    assertTrue(Files.notExists(temp.resolve("all.log")));
  }

  @Test
  void aSaveWhoseWriteFailsIsReportedAndReleased() throws Exception {
    create(MemoryBudget.of(1L << 28));
    DocumentController document = open(20);
    Path directory = Files.createDirectory(temp.resolve("target.log"));
    Files.writeString(directory.resolve("keep"), "x");
    document.saveAll(directory);
    runUntilReleased();
    assertEquals(1, view.errors.size(), view.errors.toString());
    assertTrue(view.errors.get(0).startsWith("Save: "), view.errors.toString());
    assertTrue(Files.isDirectory(directory));
  }

  @Test
  void closingTheTabDuringACopyReleasesIt() throws Exception {
    create(MemoryBudget.of(1L << 28));
    DocumentController document = open(200_000);
    document.selectAll();
    document.copySelection();
    assertEquals(1, view.active.get());
    application.closeDocument(document);
    assertTrue(document.isClosed());
    runUntilReleased();
    assertNull(view.clipboard);
    assertTrue(view.errors.isEmpty(), view.errors.toString());
  }

  @Test
  void selectedTextOfAClosedTabIsDropped() throws Exception {
    create(MemoryBudget.of(1L << 28));
    DocumentController document = open(20);
    document.selectAll();
    final String[] read = new String[1];
    document.selectionText(
        new TextListener() {
          @Override
          public void textRead(String text) {
            read[0] = text;
          }
        });
    application.closeDocument(document);
    runUntilReleased();
    assertNull(read[0]);
  }
}
