package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.theme.ThemeListener;
import io.github.ajdailey.qlogg.app.theme.ThemeService;
import io.github.ajdailey.qlogg.app.view.ManualUiDispatcher;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.io.FileByteSource;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Interleaves open, paste, reload, search, copy, mark toggle, quick find, tab close, tab move,
 * window close, time sync joins and caret moves across several windows at random, draining the UI
 * queue at random points, then quits. Afterwards every file a tab showed is closed, no thread
 * survives, and the {@code RESULTS}, {@code LINE_INDEX}, {@code HIGHLIGHT}, {@code COPY} and {@code
 * PASTED} reservations and the activity counters are 0. A document an open finished but no window
 * claimed never reaches a tab; its {@code LINE_INDEX} reservation exposes it.
 *
 * <p>The seed is random per run and is in every failure message; {@code
 * -Dqlogg.closeLifecycle.seed=N} replays one.
 */
class CloseLifecycleTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final int ROUNDS = 40;
  private static final int ACTIONS = 14;
  private static final SearchOptions REGEX =
      new SearchOptions(MatchMode.REGEX, true, false, false, false, false, true, false);

  @TempDir Path temp;

  @Test
  void randomCloseUnderLoadLeavesNothingOpen() throws Exception {
    long seed = Long.getLong("qlogg.closeLifecycle.seed", System.nanoTime());
    final List<Path> files = new ArrayList<Path>();
    files.add(lines("big.log", 60_000));
    for (int i = 0; i < 5; i++) {
      files.add(lines("small-" + i + ".log", 2_000));
    }
    final SplittableRandom random = new SplittableRandom(seed);
    assertDoesNotThrow(
        new Executable() {
          @Override
          public void execute() throws Throwable {
            for (int round = 0; round < ROUNDS; round++) {
              new Round(temp.resolve("settings-" + round), files, random.split()).run();
            }
          }
        },
        "seed " + seed);
  }

  private Path lines(String name, int count) throws IOException {
    Path file = temp.resolve(name);
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.US_ASCII)) {
      for (int i = 0; i < count; i++) {
        String stamp = String.format("2026-03-01 %02d:%02d:%02d ", i / 3600, i / 60 % 60, i % 60);
        out.write(stamp + (i % 7 == 3 ? "hit " : "line ") + i + "\n");
      }
    }
    return file;
  }

  /** One window set driven through random actions, then quit and checked. */
  private static final class Round {
    private final ManualUiDispatcher ui = new ManualUiDispatcher();
    private final MemoryBudget budget = MemoryBudget.of(1L << 28);
    private final List<Throwable> failures =
        Collections.synchronizedList(new ArrayList<Throwable>());
    private final List<FakeView> views = new ArrayList<FakeView>();
    private final List<FileByteSource> sources = new ArrayList<FileByteSource>();
    private final List<Path> files;
    private final SplittableRandom random;
    private final Workers workers;
    private final WindowSet set;

    Round(Path settings, List<Path> files, SplittableRandom random) {
      this.files = files;
      this.random = random;
      workers =
          new Workers(
              2,
              new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable error) {
                  failures.add(error);
                }
              });
      set =
          new WindowSet(
              workers,
              ui,
              ApplicationControllerTest.services(workers, budget),
              ApplicationControllerTest.config(settings),
              Preferences.DEFAULTS,
              new ThemeService() {
                @Override
                public boolean systemDark() {
                  return false;
                }

                @Override
                public void addListener(ThemeListener listener) {
                  // The setting never changes.
                }

                @Override
                public void preferDark(boolean dark) {
                  // No native controls.
                }

                @Override
                public boolean showsNow(boolean dark) {
                  return true;
                }
              });
      set.setFactory(
          new WindowSet.Factory() {
            @Override
            public ApplicationController create(WindowSet owner, WindowGeometry.Request at) {
              return window(owner);
            }
          });
      window(set);
    }

    private ApplicationController window(WindowSet owner) {
      FakeView view = new FakeView();
      views.add(view);
      ApplicationController window = new ApplicationController(owner, view);
      window.addListener(
          ui.confined(
              ApplicationListener.class,
              new ApplicationListener() {
                @Override
                public void documentOpened(DocumentController document) {
                  track(document);
                }

                @Override
                public void documentClosed(DocumentController document) {
                  // Checked after quit.
                }

                @Override
                public void documentReplaced(
                    DocumentController closed, DocumentController replacement) {
                  track(replacement);
                }
              }));
      return window;
    }

    private void track(DocumentController document) {
      document.viewportResized(10, 80);
      if (document.document().bytes() instanceof FileByteSource source) {
        sources.add(source);
      }
    }

    void run() throws Exception {
      for (int i = 0; i < ACTIONS; i++) {
        act();
        int drains = random.nextInt(0, 4);
        for (int d = 0; d < drains; d++) {
          ui.runPending();
        }
      }
      set.quit();
      assertTrue(
          ui.runUntil(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  for (FakeView view : views) {
                    if (!view.disposed) {
                      return false;
                    }
                  }
                  return true;
                }
              },
              BOUND),
          "a window was not disposed");
      assertTrue(
          Poll.until(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return workers.liveThreads() == 0;
                }
              },
              BOUND),
          "threads alive: " + workers.liveThreadNames());
      assertEquals(List.of(), failures);
      for (FileByteSource source : sources) {
        assertFalse(source.isOpen(), source.path().toString());
      }
      assertEquals(0, budget.reserved(MemoryShare.RESULTS), "RESULTS");
      assertEquals(0, budget.reserved(MemoryShare.LINE_INDEX), "LINE_INDEX");
      assertEquals(0, budget.reserved(MemoryShare.HIGHLIGHT), "HIGHLIGHT");
      assertEquals(0, budget.reserved(MemoryShare.COPY), "COPY");
      assertEquals(0, budget.reserved(MemoryShare.PASTED), "PASTED");
      for (FakeView view : views) {
        assertEquals(0, view.active.get(), "activity counter");
        view.assertConfined();
      }
    }

    private void act() {
      List<ApplicationController> windows = set.windows();
      ApplicationController window = windows.get(random.nextInt(windows.size()));
      List<DocumentController> tabs = window.documents();
      DocumentController tab = tabs.isEmpty() ? null : tabs.get(random.nextInt(tabs.size()));
      int action = random.nextInt(15);
      if (tab == null && action >= 3 && action != 10) {
        action = 0;
      }
      if (action == 10 && windows.size() == 1) {
        action = 2;
      }
      switch (action) {
        case 0 -> window.open(requests(), false);
        case 1 -> paste(window);
        case 2 -> set.newWindow();
        case 3 -> window.reload(tab);
        case 4 -> tab.search().run("hit 1[0-9]*7$", REGEX);
        case 5 -> {
          window.setFocusedView(tab);
          tab.selectAll();
          window.execute(CommandId.COPY);
        }
        case 6 -> {
          tab.selectAll();
          tab.toggleMarks();
        }
        case 7 -> {
          window.activate(tab);
          window.quickFind().open(true);
          window.quickFind().setText("line 59999");
        }
        case 8 -> window.closeDocument(tab);
        case 9 -> move(window, tab, windows);
        // Time sync.
        case 11 -> join(tab);
        case 12 -> tab.select(Selection.caretAt(new TextPosition(random.nextInt(2_000), 0)));
        case 13 -> {
          window.setFocusedView(tab);
          window.activate(tab);
          window.execute(CommandId.SCROLL_TABS_TO_TIME);
        }
        case 14 -> set.timeSync().leave(tab);
        default -> window.closeWindow();
      }
    }

    /** Toggles a random other tab of any window in a tab's Time Sync cascade. */
    private void join(DocumentController tab) {
      List<TimeSyncGroups.Entry> entries = set.timeSync().entries(tab);
      if (!entries.isEmpty()) {
        DocumentController other = entries.get(random.nextInt(entries.size())).document();
        set.timeSync().toggle(tab, other);
      }
    }

    /** Moves a tab to a new window or to another window at a random position. */
    private void move(
        ApplicationController window, DocumentController tab, List<ApplicationController> windows) {
      ApplicationController target = windows.get(random.nextInt(windows.size()));
      if (target == window) {
        window.moveToNewWindow(tab, null, null);
      } else {
        window.moveToWindow(tab, target, random.nextInt(target.documents().size() + 1));
      }
    }

    private void paste(ApplicationController window) {
      for (FakeView view : views) {
        view.clipboard = "pasted one\npasted two\n";
      }
      window.openClipboard();
    }

    private List<FileRequest> requests() {
      int count = random.nextInt(1, 5);
      List<FileRequest> requests = new ArrayList<FileRequest>();
      for (int i = 0; i < count; i++) {
        requests.add(new FileRequest(files.get(random.nextInt(files.size())), 0));
      }
      return requests;
    }
  }
}
