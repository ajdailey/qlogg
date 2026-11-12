package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.function.Supplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Sash;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The filtered view keeps {@link MainWindow#MIN_FILTERED_ROWS} rows at the default split in a
 * 640x480 window with one file open, through a resize and a sash drag toward the bottom, while the
 * wanted split stays as set; a window too short for that keeps a row in each view.
 */
@Tag("swt")
class FilteredMinimumGuiTest {
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
    window.place(new WindowGeometry.Request(0, 0, 640, 480), null);
    window.open();
    StringBuilder text = new StringBuilder();
    for (int i = 1; i <= 500; i++) {
      text.append("line ").append(i).append('\n');
    }
    controller.openFile(
        Files.writeString(temp.resolve("one.log"), text, StandardCharsets.US_ASCII), 0);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController document = controller.activeDocument();
                return document != null
                    && !document.state().indexing()
                    && window.results() != null
                    && window.tabs().isVisible();
              }
            }));
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
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private int mainRows() {
    return controller.activeDocument().viewport().rows();
  }

  private int filteredRows() {
    return controller.activeDocument().filtered().viewport().rows();
  }

  private void assertRows(final int main, final int filtered, String step) {
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return mainRows() >= main && filteredRows() >= filtered;
              }
            }),
        new Supplier<String>() {
          @Override
          public String get() {
            return step
                + ": rows "
                + mainRows()
                + ", filtered "
                + filteredRows()
                + ", shell "
                + window.shell().getSize();
          }
        });
  }

  @Test
  void theFilteredViewKeepsThreeRowsAtTheDefaultSplit() {
    assertEquals(Preferences.DEFAULTS.splitPercent(), window.splitPercent());
    assertRows(1, MainWindow.MIN_FILTERED_ROWS, "640x480");
    window.shell().setSize(640, 420);
    assertRows(1, MainWindow.MIN_FILTERED_ROWS, "640x420");
    window.shell().setSize(640, 480);
    assertRows(1, MainWindow.MIN_FILTERED_ROWS, "back to 640x480");
    assertEquals(Preferences.DEFAULTS.splitPercent(), window.splitPercent(), "wanted split");
  }

  @Test
  void aSashDragTowardTheBottomStopsAtTheMinimum() {
    final Sash sash = window.sash();
    assertNotNull(sash);
    Rectangle bounds = sash.getBounds();
    Event event = new Event();
    event.x = bounds.x;
    event.y = window.split().getClientArea().height - bounds.height - 2;
    event.width = bounds.width;
    event.height = bounds.height;
    sash.notifyListeners(SWT.Selection, event);
    assertRows(1, MainWindow.MIN_FILTERED_ROWS, "after the drag");
    assertTrue(window.splitPercent() > 80, "wanted split " + window.splitPercent());
  }

  @Test
  void aShortWindowKeepsARowInEachView() {
    window.shell().setSize(640, 250);
    assertRows(1, 1, "640x250");
  }
}
