package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.view.ManualUiDispatcher;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.search.LineSet;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** An application over a fake view and a manual dispatcher, for controller tests. */
final class ControllerFixture implements AutoCloseable {
  static final Duration BOUND = Duration.ofSeconds(20);

  final Path temp;
  final MemoryBudget budget = MemoryBudget.of(1L << 28);
  final FakeView view = new FakeView();
  final ManualUiDispatcher ui = new ManualUiDispatcher();
  final Workers workers;
  final ApplicationController application;

  ControllerFixture(Path temp, Preferences preferences) {
    this.temp = temp;
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
            preferences);
  }

  @Override
  public void close() throws InterruptedException {
    ApplicationControllerTest.shutDown(application, ui, view, workers);
  }

  /** Writes {@code count} lines; every seventh, from line 3 (0-based), says {@code hit}. */
  Path lines(String name, int count) throws IOException {
    Path file = temp.resolve(name);
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.US_ASCII)) {
      for (int i = 0; i < count; i++) {
        out.write((i % 7 == 3 ? "hit " : "line ") + i + "\n");
      }
    }
    return file;
  }

  DocumentController open(final Path file) throws InterruptedException {
    application.open(List.of(new FileRequest(file, 0)));
    final String name = file.getFileName().toString();
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController document = application.activeDocument();
                return document != null
                    && document.state().title().equals(name)
                    && !document.state().indexing();
              }
            },
            BOUND));
    DocumentController document = application.activeDocument();
    document.viewportResized(10, 80);
    document.filtered().viewportResized(5, 80);
    return document;
  }

  void runUntil(BooleanSupplier condition) throws InterruptedException {
    assertTrue(ui.runUntil(condition, BOUND));
  }

  void runUntilEnded(final SearchController search) throws InterruptedException {
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            SearchViewState.Phase phase = search.state().phase();
            return phase != SearchViewState.Phase.RUNNING && view.active.get() == 0;
          }
        });
  }

  /** Drives the queue until no operation is active. */
  void runUntilIdle() throws InterruptedException {
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.active.get() == 0;
          }
        });
  }

  static List<Long> toList(LineSet set) {
    List<Long> lines = new ArrayList<Long>();
    for (long i = 0; i < set.size(); i++) {
      lines.add(set.select(i));
    }
    return lines;
  }

  static List<Long> rows(LineViewController view) {
    List<Long> lines = new ArrayList<Long>();
    for (long row = 0; row < view.viewport().lineCount(); row++) {
      lines.add(view.sourceLine(row));
    }
    return lines;
  }
}
