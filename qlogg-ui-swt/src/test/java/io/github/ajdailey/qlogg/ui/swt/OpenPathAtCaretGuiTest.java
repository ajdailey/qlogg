package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Open Path at Caret from the log view's context menu under Xvfb. */
@Tag("swt")
class OpenPathAtCaretGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;

  @BeforeEach
  void open() throws Exception {
    display = new Display();
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    MemoryBudget budget = MemoryBudget.of(1L << 28);
    DocumentServices services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(2, budget)),
            new SearchEngine(
                workers, StandardMatchers.INSTANCE, SearchSettings.defaults(2, budget)),
            new BlockCache(65536, 1 << 20),
            budget,
            StandardCharsets.ISO_8859_1);
    window = new MainWindow(display);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            services,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 1100, 700), null);
    window.open();
  }

  @AfterEach
  void close() {
    controller.shutdown();
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.shell().isDisposed();
          }
        },
        BOUND);
    display.dispose();
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private void awaitShown(final String name, final long line) {
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController document = controller.activeDocument();
                return window.canvas() != null
                    && document != null
                    && document.state().title().equals(name)
                    && !document.state().indexing()
                    && document.selection().caret().line() == line
                    && document.lines().contains(document.viewport().topLine());
              }
            },
            BOUND),
        name + " at line " + line);
  }

  @Test
  void theContextMenuItemOpensThePathAtItsLine() throws Exception {
    StringBuilder target = new StringBuilder();
    for (int i = 1; i <= 200; i++) {
      target.append("target row ").append(i).append('\n');
    }
    Files.writeString(temp.resolve("target.log"), target, StandardCharsets.US_ASCII);
    Path log =
        Files.writeString(
            temp.resolve("app.log"),
            "start\nERROR see target.log:120 for the cause\nend\n",
            StandardCharsets.US_ASCII);
    controller.openFile(log, 0);
    awaitShown("app.log", 0);
    LogCanvas view = window.canvas();
    view.menuAt(
        view.gutterWidth() + view.columnOffset(12), view.lineHeight() + view.lineHeight() / 2);
    assertEquals(1, controller.activeDocument().selection().caret().line());
    assertEquals(12, controller.activeDocument().selection().caret().column());
    MenuItem item = null;
    Menu menu = view.control().getMenu();
    for (MenuItem candidate : menu.getItems()) {
      if (candidate.getData() == CommandId.OPEN_PATH_AT_CARET) {
        item = candidate;
      }
    }
    assertNotNull(item);
    menu.notifyListeners(SWT.Show, new Event());
    assertTrue(item.getEnabled());
    item.notifyListeners(SWT.Selection, new Event());
    awaitShown("target.log", 119);
    assertEquals(2, controller.documents().size());
  }
}
