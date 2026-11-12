package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.controller.WindowSet;
import io.github.ajdailey.qlogg.app.theme.ThemeService;
import io.github.ajdailey.qlogg.app.view.FolderRequest;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.folder.NamePattern;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two real windows over one {@link WindowSet}: a tab and then a whole window close while a search,
 * Search All Open Files, Search in Folder and an open of several files run. No exception reaches
 * the event loop, the other window opens a file afterwards, and after the quit no thread, open file
 * or reservation is left.
 */
@Tag("swt")
class CloseWithWorkGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(30);
  private static final SearchOptions LITERAL =
      new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, false, false);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final List<MainWindow> windows = new ArrayList<MainWindow>();
  private Display display;

  @Test
  void closingATabAndAWindowWithWorkRunningLeavesTheOtherWindowWorking() throws Exception {
    display = new Display();
    final Workers workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    MemoryBudget budget = MemoryBudget.of(1L << 28);
    WindowSet set =
        new WindowSet(
            workers,
            new SwtUiDispatcher(display),
            new DocumentServices(
                workers,
                new Indexer(workers, IndexSettings.defaults(2, budget)),
                new SearchEngine(
                    workers, StandardMatchers.INSTANCE, SearchSettings.defaults(2, budget)),
                new BlockCache(65536, 1 << 20),
                budget,
                StandardCharsets.ISO_8859_1),
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            ThemeService.LIGHT);
    set.setFactory(
        new WindowSet.Factory() {
          @Override
          public ApplicationController create(WindowSet owner, WindowGeometry.Request at) {
            return window(owner, 1150);
          }
        });
    Path logs = Files.createDirectory(temp.resolve("logs"));
    List<FileRequest> files = new ArrayList<FileRequest>();
    for (int i = 0; i < 4; i++) {
      files.add(new FileRequest(write(logs.resolve("app-" + i + ".log"), 200_000), 0));
    }
    try {
      final ApplicationController closing = window(set, 0);
      final MainWindow closingWindow = windows.get(0);
      closing.execute(CommandId.NEW_WINDOW);
      final ApplicationController other = set.windows().get(1);
      closing.open(files.subList(0, 2));
      assertTrue(
          runUntil(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return closing.documents().size() == 2 && closingWindow.canvas() != null;
                }
              }));
      DocumentController shown = closing.activeDocument();
      shown.search().run("hit", LITERAL);
      closing.execute(CommandId.SEARCH_ALL_FILES);
      closing
          .folderSearch()
          .run(new FolderRequest(logs.toString(), NamePattern.DEFAULT, false, "hit", LITERAL, ""));
      closing.open(files.subList(2, 4));
      closing.closeDocument(shown);
      closing.closeWindow();
      assertTrue(
          runUntil(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return closingWindow.shell().isDisposed();
                }
              }));
      assertEquals(List.of(other), set.windows());
      other.open(files.subList(0, 1));
      assertTrue(
          runUntil(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  DocumentController opened = other.activeDocument();
                  return opened != null && !opened.state().indexing();
                }
              }));
      set.quit();
      assertTrue(
          runUntil(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  for (MainWindow window : windows) {
                    if (!window.shell().isDisposed()) {
                      return false;
                    }
                  }
                  return true;
                }
              }));
      assertTrue(
          runUntil(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return workers.liveThreads() == 0;
                }
              }),
          "threads alive: " + workers.liveThreadNames());
      assertEquals(List.of(), failures);
      assertEquals(0, budget.reserved(MemoryShare.RESULTS), "RESULTS");
      assertEquals(0, budget.reserved(MemoryShare.LINE_INDEX), "LINE_INDEX");
    } finally {
      set.quit();
      EventLoop.runUntil(
          display,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return set.windows().isEmpty();
            }
          },
          BOUND);
      display.dispose();
      workers.close();
    }
  }

  private ApplicationController window(WindowSet owner, int x) {
    MainWindow window = new MainWindow(display);
    windows.add(window);
    ApplicationController created = new ApplicationController(owner, window);
    window.attach(created);
    window.place(new WindowGeometry.Request(x, 0, 1100, 700), null);
    window.open();
    return created;
  }

  private static Path write(Path file, int lines) throws Exception {
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.US_ASCII)) {
      for (int i = 0; i < lines; i++) {
        out.write((i % 7 == 3 ? "hit " : "line ") + i + " mock payload\n");
      }
    }
    return file;
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }
}
