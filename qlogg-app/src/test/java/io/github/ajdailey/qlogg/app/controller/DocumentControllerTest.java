package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.view.DocumentViewListener;
import io.github.ajdailey.qlogg.app.view.DocumentViewState;
import io.github.ajdailey.qlogg.app.view.ManualUiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentControllerTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final String NL = System.lineSeparator();

  @TempDir Path temp;

  private FakeView view;
  private ManualUiDispatcher ui;
  private Workers workers;
  private ApplicationController application;

  @BeforeEach
  void create() {
    create(MemoryBudget.of(1L << 28));
  }

  /** Replaces the application with one over the budget; call before opening a file. */
  private void create(MemoryBudget budget) {
    view = new FakeView();
    ui = new ManualUiDispatcher();
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                throw new AssertionError("worker failed", error);
              }
            });
    application =
        new ApplicationController(
            workers,
            ui,
            view,
            ApplicationControllerTest.services(workers, budget),
            ApplicationControllerTest.config(temp),
            Preferences.DEFAULTS);
  }

  @AfterEach
  void shutDown() throws InterruptedException {
    ApplicationControllerTest.shutDown(application, ui, view, workers);
  }

  /** Writes lines {@code line N}, N from 1. */
  private Path lines(String name, int count) throws IOException {
    Path file = temp.resolve(name);
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.US_ASCII)) {
      for (int i = 1; i <= count; i++) {
        out.write("line " + i + "\n");
      }
    }
    return file;
  }

  /** Opens a file and runs the UI queue until indexing ends and the visible lines are read. */
  private DocumentController open(Path file, long line) throws InterruptedException {
    application.open(List.of(new FileRequest(file, line)));
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController document = application.activeDocument();
                return document != null && !document.state().indexing() && settled(document);
              }
            },
            BOUND));
    return application.activeDocument();
  }

  /** True when the block covers the viewport's top line. */
  private static boolean settled(DocumentController document) {
    long top = document.viewport().topLine();
    return document.viewport().lineCount() == 0 || document.lines().contains(top);
  }

  private void runUntilSettled(final DocumentController document) throws InterruptedException {
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return settled(document);
              }
            },
            BOUND));
  }

  @Test
  void opensAtTheCommandLineLine() throws Exception {
    DocumentController document = open(lines("a.log", 1000), 500);
    document.viewportResized(20, 80);
    runUntilSettled(document);
    assertEquals(499, document.selection().caret().line());
    assertTrue(document.viewport().topLine() <= 499);
    assertTrue(document.viewport().topLine() + 20 > 499);
    assertEquals(-1, document.pendingLine());
    assertEquals("line 500", document.lines().text(499));
    DocumentViewState state = document.state();
    assertEquals("a.log", state.title());
    assertEquals(1000, state.lineCount());
    assertEquals(100, state.indexPercent());
    assertEquals("UTF-8", state.encodingName());
  }

  @Test
  void goToLinePastTheEndGoesToTheLastLine() throws Exception {
    DocumentController document = open(lines("b.log", 50), 0);
    document.viewportResized(10, 80);
    document.goToLine(1_000_000);
    runUntilSettled(document);
    assertEquals(49, document.selection().caret().line());
    assertEquals(40, document.viewport().topLine());
    assertTrue(document.viewport().atBottom());
    assertEquals("line 50", document.lines().text(49));
    application.execute(CommandId.GO_TO_TOP);
    runUntilSettled(document);
    assertEquals(0, document.viewport().topLine());
    application.execute(CommandId.GO_TO_LINE);
    assertEquals(50, view.askedLineCount);
    view.lineListener.lineChosen(30);
    assertEquals(29, document.selection().caret().line());
  }

  /**
   * Indexing ended before a flush reached the viewport: go to line moved at once on the live index,
   * and the viewport, still empty, clamped the caret to line 0.
   */
  @Test
  void goToLineBeforeTheFlushWaitsForIt() throws Exception {
    application.open(List.of(new FileRequest(lines("early.log", 40), 0)));
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return application.activeDocument() != null;
              }
            },
            BOUND));
    final DocumentController document = application.activeDocument();
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !document.document().status().indexing();
              }
            },
            BOUND));
    document.viewportResized(10, 80);
    document.goToLine(5);
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document.pendingLine() < 0 && settled(document);
              }
            },
            BOUND));
    assertEquals(4, document.selection().caret().line());
  }

  @Test
  void keysMoveTheCaretAndScroll() throws Exception {
    DocumentController document = open(lines("c.log", 100), 0);
    document.viewportResized(10, 80);
    application.execute(CommandId.LINE_DOWN);
    application.execute(CommandId.EXTEND_DOWN);
    assertEquals(new TextPosition(1, 0), document.selection().anchor());
    assertEquals(new TextPosition(2, 0), document.selection().caret());
    application.execute(CommandId.PAGE_DOWN);
    assertEquals(12, document.selection().caret().line());
    assertTrue(document.selection().isEmpty());
    application.execute(CommandId.SCROLL_DOWN);
    assertEquals(11, document.viewport().topLine());
    application.execute(CommandId.GO_TO_BOTTOM);
    assertEquals(99, document.selection().caret().line());
    assertEquals(90, document.viewport().topLine());
    assertTrue(document.lineNumbers());
    application.execute(CommandId.TOGGLE_LINE_NUMBERS);
    assertFalse(document.lineNumbers());
    runUntilSettled(document);
    assertEquals("line 100", document.lines().text(99));
  }

  @Test
  void scrollbarDragMovesTheTopLine() throws Exception {
    DocumentController document = open(lines("d.log", 500), 0);
    document.viewportResized(25, 80);
    assertEquals(500, document.viewport().scrollbarMaximum());
    document.setScrollbarSelection(300);
    runUntilSettled(document);
    assertEquals(300, document.viewport().topLine());
    assertEquals("line 301", document.lines().text(300));
  }

  @Test
  void copiesTheSelectionOrTheCaretLine() throws Exception {
    final DocumentController document = open(lines("e.log", 10), 0);
    document.select(new Selection(new TextPosition(1, 2), new TextPosition(3, 4)));
    document.copySelection();
    assertTrue(ui.runUntil(clipboardSet(), BOUND));
    assertEquals("ne 2" + NL + "line 3" + NL + "line", view.clipboard);
    view.clipboard = null;
    document.select(Selection.caretAt(new TextPosition(6, 3)));
    application.execute(CommandId.COPY);
    assertTrue(ui.runUntil(clipboardSet(), BOUND));
    assertEquals("line 7", view.clipboard);
    view.clipboard = null;
    application.execute(CommandId.SELECT_ALL);
    application.execute(CommandId.COPY);
    assertTrue(ui.runUntil(clipboardSet(), BOUND));
    assertTrue(view.clipboard.startsWith("line 1" + NL + "line 2" + NL));
    assertTrue(view.clipboard.endsWith("line 10"));
  }

  @Test
  void copyIsCappedByTheCopyShareAndReleasesItsCharge() throws Exception {
    application.shutdown();
    ApplicationControllerTest.shutDown(application, ui, view, workers);
    MemoryBudget budget = MemoryBudget.of(1L << 20);
    create(budget);
    long cap = TextExporter.maxCopyBytes(budget.limit(MemoryShare.COPY));
    DocumentController document = open(lines("cap.log", (int) (cap / 8)), 0);
    document.selectAll();
    document.copySelection();
    assertTrue(ui.runUntil(errorShown(), BOUND));
    assertTrue(view.errors.get(0).startsWith("Copy: The selection is "), view.errors.toString());
    assertEquals(null, view.clipboard);
    assertEquals(0, budget.reserved(MemoryShare.COPY));
    assertTrue(ui.runUntil(idle(), BOUND));

    document.select(new Selection(new TextPosition(0, 0), new TextPosition(99, 3)));
    document.copySelection();
    assertTrue(ui.runUntil(clipboardSet(), BOUND));
    assertTrue(view.clipboard.endsWith(NL + "lin"), view.clipboard);
    assertEquals(0, budget.reserved(MemoryShare.COPY));
    assertTrue(ui.runUntil(idle(), BOUND));
    assertEquals(4L, TextExporter.copyCost(1));
  }

  @Test
  void indexingAndCopyHoldTheActivityUntilTheyEnd() throws Exception {
    DocumentController document = open(lines("busy.log", 100), 0);
    assertTrue(ui.runUntil(idle(), BOUND));
    int started = view.activations.get();
    assertTrue(started >= 1);
    application.execute(CommandId.COPY);
    assertEquals(1, view.active.get());
    assertTrue(ui.runUntil(clipboardSet(), BOUND));
    assertEquals(0, view.active.get());
    assertEquals(started + 1, view.activations.get());
    application.execute(CommandId.CLOSE);
    assertTrue(document.isClosed());
    assertEquals(0, view.active.get());
  }

  @Test
  void closingDuringIndexingEndsTheActivity() throws Exception {
    application.openFile(lines("closing.log", 200_000), 0);
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return application.activeDocument() != null;
              }
            },
            BOUND));
    application.execute(CommandId.CLOSE);
    assertTrue(ui.runUntil(idle(), BOUND));
    ui.runPending();
    assertEquals(0, view.active.get());
  }

  @Test
  void endScrollsToTheRightEdgeAndZeroToTheLineStart() throws Exception {
    Path file = temp.resolve("wide.log");
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 30; i++) {
      text.append("x".repeat(i == 5 ? 300 : 40 + i)).append('\n');
    }
    Files.writeString(file, text.toString());
    DocumentController document = open(file, 0);
    final DocumentController shown = document;
    document.viewportResized(10, 80);
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return shown.lines().contains(9);
              }
            },
            BOUND));
    application.execute(CommandId.RIGHT_EDGE);
    long first = document.viewport().firstColumn();
    assertTrue(first == 300 - 80 || first == 300 - 80 + 1, "first column " + first);
    application.execute(CommandId.LINE_START);
    assertEquals(0, document.viewport().firstColumn());
    application.execute(CommandId.LINE_END);
    assertEquals(0, document.viewport().firstColumn());
  }

  private BooleanSupplier errorShown() {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return !view.errors.isEmpty();
      }
    };
  }

  private BooleanSupplier idle() {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return view.active.get() == 0;
      }
    };
  }

  @Test
  void viewListenersSeeStateAndContent() throws Exception {
    final int[] calls = new int[2];
    DocumentController document = open(lines("f.log", 5), 0);
    document.addListener(
        ui.confined(
            DocumentViewListener.class,
            new DocumentViewListener() {
              @Override
              public void stateChanged(DocumentViewState state) {
                calls[0]++;
              }

              @Override
              public void contentChanged() {
                calls[1]++;
              }
            }));
    document.viewportResized(10, 80);
    document.scrollLines(1);
    assertTrue(calls[1] > 0);
    document.scrollColumns(3);
    assertEquals(0, document.viewport().firstColumn());
  }

  private BooleanSupplier clipboardSet() {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return view.clipboard != null;
      }
    };
  }
}
