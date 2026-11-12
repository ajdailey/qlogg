package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.FolderSearchController;
import io.github.ajdailey.qlogg.app.view.FolderSearchState;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.folder.FolderListener;
import io.github.ajdailey.qlogg.core.folder.FolderProgress;
import io.github.ajdailey.qlogg.core.folder.FolderQuery;
import io.github.ajdailey.qlogg.core.folder.FolderResults;
import io.github.ajdailey.qlogg.core.folder.FolderSearch;
import io.github.ajdailey.qlogg.core.folder.NamePattern;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.search.ExpressionParser;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchLimit;
import io.github.ajdailey.qlogg.core.search.SearchQuery;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Search in Folder on the UI path against the same search without a window, per file count in
 * {@code -Dqlogg.bench.folderFiles} (comma list); runs only when that property is set. For each
 * count F it writes F files of {@code -Dqlogg.bench.lines} lines (200 by default, {@code hit} on
 * every 20th), runs {@link FolderSearch} directly, then runs the same literal search from the
 * Search in Folder window until it is done. Reports both wall times, the largest lateness of a
 * repeating 10 ms UI timer during the window run, and the count of controller publishes.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qlogg.bench.folderFiles", matches = ".+")
class FolderWindowProbe {
  private static final Duration BOUND = Duration.ofMinutes(10);
  private static final int TICK_MILLIS = 10;

  @TempDir Path temp;

  private long stallMax;
  private boolean probing;
  private int publishes;

  @Test
  void folderSearchThroughTheWindow() throws Exception {
    int lines = Integer.getInteger("qlogg.bench.lines", 200);
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
    final FolderSearchController folders = controller.folderSearch();
    folders.addListener(
        new FolderSearchController.Listener() {
          @Override
          public void changed() {
            publishes++;
          }
        });
    try {
      window.attach(controller);
      window.place(new WindowGeometry.Request(0, 0, 1100, 700), null);
      window.open();
      controller.execute(CommandId.SEARCH_FOLDER);
      final FolderSearchWindow search = window.folderSearch();
      search.names().setText("*.log");
      search.text().setText("hit");
      settle(display, 1000);
      probing = true;
      startTicks(display);
      for (String count : System.getProperty("qlogg.bench.folderFiles").split(",")) {
        int files = Integer.parseInt(count.strip());
        Path folder = write(files, lines);
        long start = System.nanoTime();
        FolderResults direct = new FolderResults(budget);
        OperationHandle handle =
            new FolderSearch(services)
                .start(
                    new FolderQuery(
                        folder.toString(),
                        NamePattern.of("*.log"),
                        false,
                        new SearchQuery(
                            ExpressionParser.parse("hit", MatchMode.LITERAL, false),
                            false,
                            false,
                            false,
                            null,
                            false),
                        NamePattern.of("")),
                    direct,
                    new FolderListener() {
                      @Override
                      public void progressed(FolderProgress progress) {
                        // Not timed.
                      }

                      @Override
                      public void finished(
                          OperationOutcome outcome, SearchLimit limit, Exception failure) {
                        // Awaited below.
                      }
                    });
        assertTrue(handle.await(BOUND.toMillis()));
        long core = System.nanoTime() - start;
        long coreMatches = direct.matches();
        direct.close();
        // The await above blocked the UI thread; the timer catches up before the window run.
        settle(display, 500);
        search.folder().setText(folder.toString());
        search.shell().getDefaultButton().notifyListeners(SWT.Selection, new Event());
        stallMax = 0;
        publishes = 0;
        start = System.nanoTime();
        assertTrue(
            EventLoop.runUntil(
                display,
                new BooleanSupplier() {
                  @Override
                  public boolean getAsBoolean() {
                    return folders.state().phase() == FolderSearchState.Phase.DONE;
                  }
                },
                BOUND));
        long shown = System.nanoTime() - start;
        System.out.println(
            String.format(
                Locale.ROOT,
                "probe.folder files=%d matches=%d/%d core=%.0fms window=%.0fms"
                    + " windowStall=%.1fms publishes=%d",
                files,
                folders.matches(),
                coreMatches,
                core / 1e6,
                shown / 1e6,
                stallMax / 1e6,
                publishes));
        folders.clear();
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

  /** Writes {@code files} files of {@code lines} lines into a new folder. */
  private Path write(int files, int lines) throws Exception {
    Path folder = Files.createDirectory(temp.resolve("f" + files));
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < lines; i++) {
      text.append(i % 20 == 7 ? "hit " : "line ")
          .append(i)
          .append(" mock payload text to fill the row\n");
    }
    for (int i = 0; i < files; i++) {
      Files.writeString(
          folder.resolve(String.format(Locale.ROOT, "app-%05d.log", i)),
          text,
          StandardCharsets.US_ASCII);
    }
    return folder;
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
