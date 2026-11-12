package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.controller.MultiSearchController;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Multi-tab operations on the UI path, per tab count in {@code -Dqlogg.bench.tabs} (comma list);
 * runs only when that property is set. For each count N it writes N files of {@code
 * -Dqlogg.bench.lines} lines (2,000 by default, {@code hit} on every 100th) and times, in the main
 * window: one open of all N files until every tab is indexed; Search All Open Files for {@code hit}
 * until every search is done, with the count of {@link MultiSearchController} fires and the time
 * {@link MultiSearchWindow} spends in them; Close All. Each phase reports its wall time and the
 * largest lateness of a repeating 10 ms UI timer, after the same lateness over 2 s of idle loop.
 * The UI thread's stack is sampled every millisecond during the open and Close All; the open also
 * reports the chains sampled during its largest stall.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qlogg.bench.tabs", matches = ".+")
class TabsProbe {
  private static final Duration BOUND = Duration.ofMinutes(10);
  private static final int TICK_MILLIS = 10;

  @TempDir Path temp;

  private long stallMax;
  private long stallEnd;
  private boolean probing;
  private int fires;
  private long syncStart;
  private long syncNanos;
  private long syncMax;

  @Test
  void openSearchAllAndCloseAll() throws Exception {
    int lines = Integer.getInteger("qlogg.bench.lines", 2000);
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
    DocumentServices services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(workers.cpuThreads(), budget)),
            new SearchEngine(
                workers,
                StandardMatchers.INSTANCE,
                SearchSettings.defaults(workers.cpuThreads(), budget)),
            BlockCache.fromBudget(budget),
            budget,
            StandardCharsets.ISO_8859_1);
    MainWindow window = new MainWindow(display);
    final ApplicationController controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            services,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS);
    System.out.println("probe.platform=" + SWT.getPlatform() + " " + SWT.getVersion());
    System.out.println("probe.gtk4=" + System.getenv("SWT_GTK4"));
    controller
        .multiSearch()
        .addListener(
            new MultiSearchController.Listener() {
              @Override
              public void resultsChanged() {
                fires++;
                syncStart = System.nanoTime();
              }
            });
    try {
      window.attach(controller);
      window.place(new WindowGeometry.Request(0, 0, 1100, 700), null);
      window.open();
      settle(display, 1000);
      probing = true;
      startTicks(display);
      for (String count : System.getProperty("qlogg.bench.tabs").split(",")) {
        final int tabs = Integer.parseInt(count.strip());
        List<FileRequest> files = write(tabs, lines);
        stallMax = 0;
        settle(display, 2000);
        System.out.println(
            String.format(Locale.ROOT, "probe.idle n=%d idleStall=%.1fms", tabs, stallMax / 1e6));
        stallMax = 0;
        Sampler openSampler = new Sampler(Thread.currentThread());
        openSampler.start();
        long start = System.nanoTime();
        controller.open(files);
        assertTrue(
            EventLoop.runUntil(
                display,
                new BooleanSupplier() {
                  @Override
                  public boolean getAsBoolean() {
                    if (controller.documents().size() < tabs) {
                      return false;
                    }
                    for (DocumentController document : controller.documents()) {
                      if (document.state().indexing()) {
                        return false;
                      }
                    }
                    return true;
                  }
                },
                BOUND));
        long open = System.nanoTime() - start;
        openSampler.halt();
        long openStall = stallMax;
        long openStallEnd = stallEnd;
        stallMax = 0;
        fires = 0;
        controller.activeDocument().search().setFieldText("hit");
        start = System.nanoTime();
        controller.execute(CommandId.SEARCH_ALL_FILES);
        syncNanos = 0;
        syncMax = 0;
        MultiSearchController.Listener after =
            new MultiSearchController.Listener() {
              @Override
              public void resultsChanged() {
                long spent = System.nanoTime() - syncStart;
                syncNanos += spent;
                syncMax = Math.max(syncMax, spent);
              }
            };
        controller.multiSearch().addListener(after);
        assertTrue(
            EventLoop.runUntil(
                display,
                new BooleanSupplier() {
                  @Override
                  public boolean getAsBoolean() {
                    List<MultiSearchController.FileResults> results =
                        controller.multiSearch().results();
                    if (results.size() < tabs) {
                      return false;
                    }
                    for (MultiSearchController.FileResults result : results) {
                      if (result.state().phase() != SearchViewState.Phase.DONE) {
                        return false;
                      }
                    }
                    return true;
                  }
                },
                BOUND));
        long multi = System.nanoTime() - start;
        long multiStall = stallMax;
        int multiFires = fires;
        controller.multiSearch().removeListener(after);
        window.multiSearch().shell().close();
        settle(display, 500);
        stallMax = 0;
        Sampler sampler = new Sampler(Thread.currentThread());
        sampler.start();
        start = System.nanoTime();
        controller.closeAll();
        long close = System.nanoTime() - start;
        sampler.halt();
        settle(display, 500);
        long closeStall = Math.max(stallMax, close);
        System.out.println(
            String.format(
                Locale.ROOT,
                "probe.tabs n=%d open=%.0fms openStall=%.1fms perTabOpen=%.2fms"
                    + " searchAll=%.0fms searchAllStall=%.1fms fires=%d windowSync=%.0fms"
                    + " windowSyncMax=%.2fms windowSyncPerFire=%.3fms"
                    + " closeAll=%.0fms closeAllStall=%.1fms perTabClose=%.2fms",
                tabs,
                open / 1e6,
                openStall / 1e6,
                open / 1e6 / tabs,
                multi / 1e6,
                multiStall / 1e6,
                multiFires,
                syncNanos / 1e6,
                syncMax / 1e6,
                syncNanos / 1e6 / Math.max(1, multiFires),
                close / 1e6,
                closeStall / 1e6,
                close / 1e6 / tabs));
        openSampler.print("open", tabs, openStallEnd - openStall, openStallEnd);
        sampler.print("closeAll", tabs, 0, Long.MAX_VALUE);
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
          Duration.ofSeconds(60));
      display.dispose();
      workers.close();
    }
  }

  /** Writes {@code tabs} files of {@code lines} lines into a new folder. */
  private List<FileRequest> write(int tabs, int lines) throws Exception {
    Path folder = Files.createDirectory(temp.resolve("n" + tabs));
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < lines; i++) {
      text.append(
              String.format(Locale.ROOT, "2026-11-12 10:00:%02d.000 worker-%d ", i % 60, i % 16))
          .append(i % 100 == 7 ? "hit " : "line ")
          .append(i)
          .append(" mock payload text to fill the row\n");
    }
    List<FileRequest> files = new ArrayList<FileRequest>();
    for (int i = 0; i < tabs; i++) {
      Path file = folder.resolve(String.format(Locale.ROOT, "app-%04d.log", i));
      Files.writeString(file, text, StandardCharsets.US_ASCII);
      files.add(new FileRequest(file, 0));
    }
    return files;
  }

  /**
   * Samples a thread's stack every millisecond and counts, per sample, the innermost QLogg frame
   * and the innermost frame.
   */
  private static final class Sampler extends Thread {
    private final Thread target;
    private final Map<String, Integer> own = new HashMap<String, Integer>();
    private final Map<String, Integer> top = new HashMap<String, Integer>();
    private final Map<String, Integer> chain = new HashMap<String, Integer>();
    private final List<Long> times = new ArrayList<Long>();
    private final List<String> chains = new ArrayList<String>();
    private volatile boolean running = true;
    private int samples;

    Sampler(Thread target) {
      this.target = target;
      setDaemon(true);
    }

    @Override
    public void run() {
      while (running) {
        StackTraceElement[] stack = target.getStackTrace();
        if (stack.length > 0) {
          samples++;
          count(top, stack[0].getClassName() + "." + stack[0].getMethodName());
          StringBuilder calls = new StringBuilder();
          for (StackTraceElement frame : stack) {
            if (frame.getClassName().startsWith("io.github.ajdailey.qlogg.")
                && !frame.getClassName().contains("TabsProbe")) {
              String name = frame.getClassName();
              name = name.substring(name.lastIndexOf('.') + 1) + "." + frame.getMethodName();
              if (calls.length() == 0) {
                count(own, frame.getClassName() + "." + frame.getMethodName());
              }
              calls.insert(0, calls.length() == 0 ? name : name + " > ");
            }
          }
          if (calls.length() > 0) {
            count(chain, calls.toString());
          }
          times.add(System.nanoTime());
          chains.add(calls + " | " + stack[0]);
        }
        try {
          Thread.sleep(1);
        } catch (InterruptedException e) {
          return;
        }
      }
    }

    private static void count(Map<String, Integer> counts, String key) {
      Integer now = counts.get(key);
      counts.put(key, now == null ? 1 : now + 1);
    }

    void halt() throws InterruptedException {
      running = false;
      join();
    }

    /**
     * Prints the counts, then the chains sampled between {@code from} and {@code to} (the largest
     * stall).
     */
    void print(String phase, int tabs, long from, long to) {
      System.out.println("probe." + phase + ".samples n=" + tabs + " samples=" + samples);
      print(phase + ".own", own);
      print(phase + ".top", top);
      print(phase + ".chain", chain);
      if (to != Long.MAX_VALUE) {
        Map<String, Integer> stall = new HashMap<String, Integer>();
        for (int i = 0; i < times.size(); i++) {
          if (times.get(i) >= from && times.get(i) <= to) {
            count(stall, chains.get(i));
          }
        }
        print(phase + ".stall", stall);
      }
    }

    private static void print(String kind, Map<String, Integer> counts) {
      List<Map.Entry<String, Integer>> entries =
          new ArrayList<Map.Entry<String, Integer>>(counts.entrySet());
      entries.sort(
          new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
              return Integer.compare(b.getValue(), a.getValue());
            }
          });
      for (int i = 0; i < Math.min(12, entries.size()); i++) {
        System.out.println(
            "probe." + kind + " " + entries.get(i).getValue() + " " + entries.get(i).getKey());
      }
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
            if (now - due[0] > stallMax) {
              stallMax = now - due[0];
              stallEnd = now;
            }
            if (probing && !display.isDisposed()) {
              due[0] = now + TICK_MILLIS * 1_000_000L;
              display.timerExec(TICK_MILLIS, this);
            }
          }
        });
  }
}
