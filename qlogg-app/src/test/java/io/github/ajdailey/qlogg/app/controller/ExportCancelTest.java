package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.ManualUiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tab close and {@code Esc} cancel a running copy or save. The file's lines from {@link
 * #GATED_FROM} on start with {@code G}, so the copy holds at the first of them until the test
 * passes one decode; without the cancel it holds again at the next row and outlives the document's
 * close bound.
 */
class ExportCancelTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final int LINES = 2000;
  private static final int GATED_FROM = 200;

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final GatedDecodeCharset gate = new GatedDecodeCharset();
  private final MemoryBudget budget = MemoryBudget.of(1L << 28);
  private FakeView view;
  private ManualUiDispatcher ui;
  private Workers workers;
  private ApplicationController application;

  @BeforeEach
  void create() {
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
            ApplicationControllerTest.services(workers, budget, gate),
            ApplicationControllerTest.config(temp.resolve("settings")),
            Preferences.DEFAULTS);
  }

  @AfterEach
  void shutDown() throws InterruptedException {
    gate.open();
    ApplicationControllerTest.shutDown(application, ui, view, workers);
    assertTrue(failures.isEmpty(), failures.toString());
  }

  /** Opens a file that is not UTF-8, so it decodes through the gate. */
  private DocumentController open() throws IOException, InterruptedException {
    Path file = temp.resolve("gated.log");
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.ISO_8859_1)) {
      out.write("café 0\n");
      for (int i = 1; i < LINES; i++) {
        out.write((i < GATED_FROM ? "line " : "Gate ") + i + "\n");
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

  private void runUntil(final MemoryShare share) throws InterruptedException {
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return idle() && budget.reserved(share) == 0;
              }
            },
            BOUND),
        share + " still holds " + budget.reserved(share));
  }

  @Test
  void closingTheTabDuringACopyReturnsTheCopyAndIndexReservations() throws Exception {
    DocumentController document = open();
    assertTrue(budget.reserved(MemoryShare.LINE_INDEX) > 0);
    document.selectAll();
    document.copySelection();
    assertTrue(gate.awaitHeld());
    application.closeDocument(document);
    gate.pass();
    runUntil(MemoryShare.COPY);
    runUntil(MemoryShare.LINE_INDEX);
    assertNull(view.clipboard);
    assertTrue(view.errors.isEmpty(), view.errors.toString());
  }

  @Test
  void escCancelsASaveAndLeavesNoFile() throws Exception {
    DocumentController document = open();
    Path target = temp.resolve("saved.log");
    document.saveAll(target);
    assertTrue(gate.awaitHeld());
    application.execute(CommandId.STOP);
    gate.pass();
    gate.open();
    runUntil(MemoryShare.COPY);
    assertTrue(Files.notExists(target));
    assertTrue(view.errors.isEmpty(), view.errors.toString());
    assertTrue(view.notices.isEmpty(), view.notices.toString());
  }

  @Test
  void aSecondCopyWhileOneRunsShowsANoticeAndCopyRunsAfterEsc() throws Exception {
    DocumentController document = open();
    document.selectAll();
    document.copySelection();
    assertTrue(gate.awaitHeld());
    document.copySelection();
    assertEquals(List.of(UiText.NOTICE_EXPORT_RUNNING.text()), view.notices);
    application.execute(CommandId.STOP);
    gate.pass();
    gate.open();
    runUntil(MemoryShare.COPY);
    assertNull(view.clipboard);

    document.select(new Selection(new TextPosition(1, 0), new TextPosition(2, Long.MAX_VALUE)));
    document.copySelection();
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return view.clipboard != null;
              }
            },
            BOUND));
    assertEquals("line 1" + System.lineSeparator() + "line 2", view.clipboard);
  }
}
