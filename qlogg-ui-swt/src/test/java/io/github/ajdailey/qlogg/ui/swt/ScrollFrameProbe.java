package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
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
import java.util.Arrays;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Scrolling frame time of the main view over the file named by {@code -Dqlogg.bench.file}; runs
 * only when that property is set. A frame is the time from a scroll on the UI thread to the end of
 * the first canvas paint that shows the new top line with its text loaded; the paint time is the
 * canvas's own paint listeners alone. Before scrolling it records the longest UI-thread stall while
 * the file indexes and while a regex search for {@code blockterm} runs, from a 10 ms timer's
 * lateness. Prints the results on standard output.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qlogg.bench.file", matches = ".+")
class ScrollFrameProbe {
  private static final Duration BOUND = Duration.ofSeconds(60);
  private static final int FRAMES = 500;
  private static final int STALL_TICK_MILLIS = 10;

  @TempDir Path temp;

  private long paintStart;
  private long paintEnd;
  private long paintNanos;
  private long paints;
  private boolean shownLoaded;
  private long shownTop = -1;
  private long stallMax;
  private boolean stallProbe;

  @Test
  void scroll() throws Exception {
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
    try {
      window.attach(controller);
      window.place(new WindowGeometry.Request(0, 0, 1100, 700), null);
      window.open();
      startStallProbe(display);
      controller.openFile(Path.of(System.getProperty("qlogg.bench.file")), 0);
      final MainWindow shown = window;
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
      final DocumentController document = controller.activeDocument();
      System.out.println("probe.stall.indexing=" + stallMax / 1_000_000 + "ms");
      stallMax = 0;
      document.search().run("blockterm", SearchOptions.DEFAULTS);
      assertTrue(
          EventLoop.runUntil(
              display,
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return document.search().state().phase() != SearchViewState.Phase.RUNNING;
                }
              },
              Duration.ofMinutes(10)));
      System.out.println(
          "probe.stall.search="
              + stallMax / 1_000_000
              + "ms matches="
              + document.search().state().matchCount());
      stallProbe = false;
      instrument(window.canvas().control(), document);
      int rows = document.viewport().rows();
      System.out.println("probe.platform=" + SWT.getPlatform() + " " + SWT.getVersion());
      System.out.println("probe.gtk4=" + System.getenv("SWT_GTK4"));
      System.out.println("probe.rows=" + rows);
      measure(display, document, "line", 1);
      measure(display, document, "page", rows);
    } finally {
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

  /** Records the largest lateness of a repeating 10 ms timer until {@code stallProbe} clears. */
  private void startStallProbe(final Display display) {
    stallProbe = true;
    final long[] due = {System.nanoTime() + STALL_TICK_MILLIS * 1_000_000L};
    display.timerExec(
        STALL_TICK_MILLIS,
        new Runnable() {
          @Override
          public void run() {
            long now = System.nanoTime();
            stallMax = Math.max(stallMax, now - due[0]);
            if (stallProbe && !display.isDisposed()) {
              due[0] = now + STALL_TICK_MILLIS * 1_000_000L;
              display.timerExec(STALL_TICK_MILLIS, this);
            }
          }
        });
  }

  /** Puts a timing listener before and after the canvas's own paint listeners. */
  private void instrument(Canvas canvas, final DocumentController document) {
    Listener[] own = canvas.getListeners(SWT.Paint);
    for (Listener listener : own) {
      canvas.removeListener(SWT.Paint, listener);
    }
    canvas.addListener(
        SWT.Paint,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            paintStart = System.nanoTime();
          }
        });
    for (Listener listener : own) {
      canvas.addListener(SWT.Paint, listener);
    }
    canvas.addListener(
        SWT.Paint,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            paintEnd = System.nanoTime();
            paintNanos += paintEnd - paintStart;
            paints++;
            shownTop = document.viewport().topLine();
            shownLoaded = document.lines().contains(shownTop);
          }
        });
  }

  private void measure(Display display, final DocumentController document, String name, long step) {
    long[] frames = new long[FRAMES];
    long paintsBefore = paints;
    long paintNanosBefore = paintNanos;
    for (int i = 0; i < FRAMES; i++) {
      final long before = paints;
      long start = System.nanoTime();
      document.scrollLines(step);
      final long target = document.viewport().topLine();
      boolean painted =
          EventLoop.runUntil(
              display,
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return paints > before && shownTop == target && shownLoaded;
                }
              },
              BOUND);
      assertTrue(painted, "no paint of line " + target);
      frames[i] = paintEnd - start;
    }
    Arrays.sort(frames);
    long count = paints - paintsBefore;
    System.out.println(
        String.format(
            Locale.ROOT,
            "probe.%s frames=%d p50=%.2fms p90=%.2fms p99=%.2fms max=%.2fms paints=%d"
                + " meanPaint=%.2fms",
            name,
            FRAMES,
            frames[FRAMES / 2] / 1e6,
            frames[FRAMES * 9 / 10] / 1e6,
            frames[FRAMES * 99 / 100] / 1e6,
            frames[FRAMES - 1] / 1e6,
            count,
            (paintNanos - paintNanosBefore) / 1e6 / Math.max(1, count)));
  }
}
