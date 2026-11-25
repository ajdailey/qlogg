package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tool windows open inside the work area of their owner's monitor, and Search in Folder, Search in
 * Open Files, the marks list and the scratchpad reopen at the size they closed with. GTK 4 cannot
 * position a shell ({@link Gtk4#NO_POSITION}), so there only the size is checked.
 */
@Tag("swt")
class ToolWindowFitGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;

  @BeforeEach
  void open() {
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
    window.place(new WindowGeometry.Request(0, 0, 900, 600), null);
    window.open();
  }

  @AfterEach
  void close() {
    controller.shutdown();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.shell().isDisposed();
          }
        });
    display.dispose();
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  @Test
  void aWindowTallerThanTheWorkAreaOpensInsideIt() {
    FolderSearchWindow folder =
        new FolderSearchWindow(window.shell(), controller.folderSearch(), controller::keymap);
    Shell shell = folder.shell();
    Point preferred = shell.computeSize(SWT.DEFAULT, SWT.DEFAULT);
    Rectangle work = new Rectangle(40, 30, preferred.x + 200, preferred.y / 2);
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT, work);
    Rectangle bounds = shell.getBounds();
    assertEquals(work.height, bounds.height);
    assertTrue(bounds.width <= work.width, bounds + " wider than " + work);
    if (!Gtk4.active()) {
      assertTrue(work.contains(bounds.x, bounds.y), bounds + " starts outside " + work);
      assertTrue(bounds.x + bounds.width <= work.x + work.width, bounds + " ends right of " + work);
      assertTrue(bounds.y + bounds.height <= work.y + work.height, bounds + " ends below " + work);
    }
    shell.dispose();
  }

  @Test
  void searchInFolderReopensAtTheSizeItClosedWith() {
    window.showFolderSearch();
    Shell first = window.folderSearch().shell();
    first.setSize(520, 380);
    final Point closed = first.getSize();
    first.dispose();
    window.showFolderSearch();
    final Shell second = window.folderSearch().shell();
    assertTrue(second != first);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return second.getSize().equals(closed);
              }
            }),
        "reopened at " + second.getSize() + ", closed at " + closed);
  }

  @Test
  void aRememberedSizeLargerThanTheMonitorIsFittedToItsWorkArea() {
    controller.rememberedState().rememberDialogSize("marks", 20000, 20000);
    window.showMarks();
    Shell shell = window.marks().shell();
    Rectangle work = window.shell().getMonitor().getClientArea();
    Rectangle bounds = shell.getBounds();
    assertTrue(bounds.width <= work.width && bounds.height <= work.height, bounds + " in " + work);
    if (!Gtk4.active()) {
      assertTrue(work.contains(bounds.x, bounds.y), bounds + " starts outside " + work);
    }
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }
}
