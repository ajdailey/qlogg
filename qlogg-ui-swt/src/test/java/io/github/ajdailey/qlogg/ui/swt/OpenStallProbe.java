package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Longest UI-thread stall per open and per close of the file named by {@code -Dqlogg.bench.file};
 * runs only when that property is set. The window settles for {@code -Dqlogg.bench.settle}
 * milliseconds (1000 by default), then the file opens {@code -Dqlogg.bench.opens} times (10 by
 * default), its tab closing before the next open. An open's stall is the largest lateness of a
 * repeating 10 ms timer from the open request until the file is indexed and its top line is loaded;
 * a close's stall is the same over the close and the 500 ms after it. Prints the results on
 * standard output.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qlogg.bench.file", matches = ".+")
class OpenStallProbe {
  private static final Duration BOUND = Duration.ofSeconds(60);
  private static final int TICK_MILLIS = 10;
  private static final long CLOSE_SETTLE_MILLIS = 500;

  @TempDir Path temp;

  private long stallMax;
  private boolean probing;

  @Test
  void openAndClose() throws Exception {
    int opens = Integer.getInteger("qlogg.bench.opens", 10);
    Display display = new Display();
    Workers workers =
        new Workers(
            Runtime.getRuntime().availableProcessors(),
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                error.printStackTrace();
              }
            });
    MemoryBudget budget = MemoryBudget.fromRuntime();
    long cacheBytes = budget.limit(MemoryShare.BLOCK_CACHE);
    budget.charge(MemoryShare.BLOCK_CACHE, cacheBytes);
    DocumentServices services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(workers.cpuThreads(), budget)),
            new SearchEngine(
                workers,
                StandardMatchers.INSTANCE,
                SearchSettings.defaults(workers.cpuThreads(), budget)),
            new BlockCache(64 * 1024, cacheBytes),
            budget,
            StandardCharsets.ISO_8859_1);
    MainWindow window = new MainWindow(display);
    final ApplicationController controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            services,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS);
    Path file = Path.of(System.getProperty("qlogg.bench.file"));
    System.out.println("probe.platform=" + SWT.getPlatform() + " " + SWT.getVersion());
    System.out.println("probe.gtk4=" + System.getenv("SWT_GTK4"));
    try {
      window.attach(controller);
      window.place(new WindowGeometry.Request(0, 0, 1100, 700), null);
      window.open();
      settle(display, Long.getLong("qlogg.bench.settle", 1000L));
      probing = true;
      startTicks(display);
      final MainWindow shown = window;
      for (int i = 0; i < opens; i++) {
        stallMax = 0;
        controller.openFile(file, 0);
        assertTrue(
            EventLoop.runUntil(
                display,
                new BooleanSupplier() {
                  @Override
                  public boolean getAsBoolean() {
                    DocumentController document = controller.activeDocument();
                    return shown.canvas() != null
                        && document != null
                        && !document.state().indexing()
                        && document.lines().contains(document.viewport().topLine());
                  }
                },
                Duration.ofMinutes(10)));
        long openStall = stallMax;
        stallMax = 0;
        controller.closeDocument(controller.activeDocument());
        settle(display, CLOSE_SETTLE_MILLIS);
        System.out.println(
            String.format(
                Locale.ROOT,
                "probe.open i=%d openStall=%.1fms closeStall=%.1fms",
                i,
                openStall / 1e6,
                stallMax / 1e6));
      }
    } finally {
      probing = false;
      controller.shutdown();
      final MainWindow closing = window;
      EventLoop.runUntil(
          display,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return closing.shell().isDisposed();
            }
          },
          BOUND);
      display.dispose();
      workers.close();
    }
  }

  /** Runs the event loop for a fixed time. */
  private static void settle(Display display, long millis) {
    final long end = System.nanoTime() + millis * 1_000_000L;
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() >= end;
          }
        },
        BOUND);
  }

  /** Records the largest lateness of a repeating 10 ms timer until {@code probing} clears. */
  private void startTicks(final Display display) {
    final long[] due = {System.nanoTime() + TICK_MILLIS * 1_000_000L};
    display.timerExec(
        TICK_MILLIS,
        new Runnable() {
          @Override
          public void run() {
            long now = System.nanoTime();
            stallMax = Math.max(stallMax, now - due[0]);
            if (probing && !display.isDisposed()) {
              due[0] = now + TICK_MILLIS * 1_000_000L;
              display.timerExec(TICK_MILLIS, this);
            }
          }
        });
  }
}
