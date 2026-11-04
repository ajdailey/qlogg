package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.about.AboutInfo;
import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.Session;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.log.LogFiles;
import io.github.ajdailey.qlogg.app.log.RecentLog;
import io.github.ajdailey.qlogg.app.theme.ThemeListener;
import io.github.ajdailey.qlogg.app.theme.ThemeService;
import io.github.ajdailey.qlogg.app.view.ManualUiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Several main windows over one {@link WindowSet}, the handoff target and the Help items. */
class WindowSetTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private final List<FakeView> views = new ArrayList<FakeView>();
  private final FakeLogFiles logFiles = new FakeLogFiles();
  private final RecentLog recent = new RecentLog(10, 200);
  private final MemoryBudget budget = MemoryBudget.of(1L << 28);
  private final GatedCharset gate = new GatedCharset();
  private Workers workers;
  private ConfigStore config;
  private WindowSet set;
  private ApplicationController first;
  private FakeView firstView;
  private boolean nativeLive = true;

  /** A log file that opens unless told to fail. */
  private final class FakeLogFiles implements LogFiles {
    volatile boolean attached;
    volatile boolean fail;

    @Override
    public Path directory() {
      return temp.resolve("logs");
    }

    @Override
    public boolean attached() {
      return attached;
    }

    @Override
    public void attach() throws IOException {
      if (fail) {
        throw new IOException("mock failure");
      }
      attached = true;
    }

    @Override
    public void detach() {
      attached = false;
    }
  }

  @BeforeEach
  void setUp() {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                throw new AssertionError("worker failed", error);
              }
            });
    config = ApplicationControllerTest.config(temp);
    set =
        new WindowSet(
            workers,
            ui,
            ApplicationControllerTest.services(workers, budget, gate),
            config,
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
                return nativeLive;
              }
            });
    set.setLogging(recent, logFiles);
    set.setFactory(
        new WindowSet.Factory() {
          @Override
          public ApplicationController create(WindowSet owner) {
            FakeView view = new FakeView();
            views.add(view);
            return new ApplicationController(owner, view);
          }
        });
    firstView = new FakeView();
    views.add(firstView);
    first = new ApplicationController(set, firstView);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    set.quit();
    runUntil(
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
        });
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return workers.liveThreads() == 0;
              }
            },
            BOUND));
  }

  private void runUntil(BooleanSupplier condition) throws InterruptedException {
    assertTrue(ui.runUntil(condition, BOUND));
  }

  private Path log(String name) throws IOException {
    return Files.writeString(temp.resolve(name), "one\ntwo\n");
  }

  private void openIn(final ApplicationController window, Path file) throws InterruptedException {
    final int before = window.documents().size();
    window.open(List.of(new FileRequest(file, 0)), false);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.documents().size() == before + 1;
          }
        });
  }

  @Test
  void aThemeNativeControlsShowOnlyAfterRestartIsAnnounced() {
    first.execute(CommandId.THEME_DARK);
    assertEquals(List.of(), firstView.infos);
    nativeLive = false;
    first.execute(CommandId.THEME_LIGHT);
    assertEquals(
        List.of(UiText.THEME_RESTART_TITLE.text() + ": " + UiText.THEME_RESTART_MESSAGE.text()),
        firstView.infos);
  }

  @Test
  void handedFilesGoToTheWindowActivatedLast() throws Exception {
    first.execute(CommandId.NEW_WINDOW);
    assertEquals(2, set.windows().size());
    final ApplicationController second = set.windows().get(1);
    assertSame(second, set.target());
    set.openHandoff(List.of(new FileRequest(log("a.log"), 0)), false);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return second.documents().size() == 1;
          }
        });
    assertEquals(1, views.get(1).raised);
    assertTrue(first.documents().isEmpty());

    first.windowActivated();
    set.openHandoff(List.of(new FileRequest(log("b.log"), 0)), true);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return first.documents().size() == 1;
          }
        });
    assertTrue(first.documents().get(0).following());
    assertEquals(1, firstView.raised);
  }

  @Test
  void quitSavesEveryWindowsTabs() throws Exception {
    first.setSessionOwner(true);
    first.execute(CommandId.NEW_WINDOW);
    ApplicationController second = set.windows().get(1);
    openIn(first, log("a.log"));
    openIn(second, log("b.log"));
    final List<String> order = new ArrayList<String>();
    set.addQuitListener(
        new Runnable() {
          @Override
          public void run() {
            order.add("quit");
          }
        });
    set.addFinishListener(
        new Runnable() {
          @Override
          public void run() {
            order.add("finish");
          }
        });
    first.execute(CommandId.QUIT);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return views.get(0).disposed && views.get(1).disposed;
          }
        });
    assertEquals(List.of("quit", "finish"), order);
    List<Session> saved = config.sessions().loadWindows();
    assertEquals(2, saved.size());
    assertTrue(saved.get(0).tabs().get(0).path().endsWith("a.log"));
    assertTrue(saved.get(1).tabs().get(0).path().endsWith("b.log"));
  }

  /** A log that is not valid UTF-8, so its open falls back to the gated charset. */
  private Path latin1(String name) throws IOException {
    return Files.write(temp.resolve(name), new byte[] {'o', 'n', 'e', (byte) 0xE9, '\n'});
  }

  private static FileRequest request(Path file) {
    return new FileRequest(file, 0);
  }

  /**
   * Closes the second window while the gate holds an open, lets the open finish, and checks that no
   * index reservation survives the open worker.
   */
  private void closeWhileHeld(ApplicationController window) throws InterruptedException {
    assertTrue(gate.awaitHeld());
    window.closeWindow();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return views.get(1).disposed;
          }
        });
    gate.release();
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !workers.liveThreadNames().contains(Workers.Kind.INDEX.prefix());
              }
            },
            BOUND));
    assertEquals(0, budget.reserved(MemoryShare.LINE_INDEX));
  }

  @Test
  void closingAWindowDuringAnOpenClosesTheFileAndSkipsTheRest() throws Exception {
    first.execute(CommandId.NEW_WINDOW);
    ApplicationController second = set.windows().get(1);
    gate.arm();
    second.open(
        List.of(request(latin1("a.log")), request(latin1("b.log")), request(latin1("c.log"))),
        false);
    closeWhileHeld(second);
    assertEquals(1, gate.opens());
  }

  @Test
  void closingAWindowDuringAReloadClosesTheReloadedFile() throws Exception {
    first.execute(CommandId.NEW_WINDOW);
    ApplicationController second = set.windows().get(1);
    openIn(second, latin1("a.log"));
    gate.arm();
    second.reload(second.documents().get(0));
    closeWhileHeld(second);
  }

  @Test
  void closingAWindowDuringASessionRestoreClosesTheFileAndSkipsTheRest() throws Exception {
    first.execute(CommandId.NEW_WINDOW);
    ApplicationController second = set.windows().get(1);
    List<Session.Tab> tabs = new ArrayList<Session.Tab>();
    for (String name : List.of("a.log", "b.log", "c.log")) {
      tabs.add(new Session.Tab(latin1(name).toString(), "", false, new long[0]));
    }
    gate.arm();
    second.restoreSession(new Session(tabs, 0));
    closeWhileHeld(second);
    assertEquals(1, gate.opens());
  }

  @Test
  void closingOneWindowKeepsTheOthersAndDropsItsTabs() throws Exception {
    first.setSessionOwner(true);
    first.execute(CommandId.NEW_WINDOW);
    final ApplicationController second = set.windows().get(1);
    openIn(first, log("a.log"));
    openIn(second, log("b.log"));
    second.closeWindow();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return views.get(1).disposed;
          }
        });
    assertFalse(firstView.hidden);
    assertEquals(List.of(first), set.windows());
    assertSame(first, set.target());
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return config.sessions().loadWindows().size() == 1;
              }
            },
            BOUND));

    first.closeWindow();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return firstView.disposed;
          }
        });
    assertTrue(set.isQuitting());
    assertEquals(1, config.sessions().loadWindows().size());
  }

  @Test
  void showLogListsTheLinesAndClearsTheWarningMarker() throws Exception {
    recent.append("2026-11-02 INFO started");
    assertEquals(null, firstView.warningMarker);
    recent.append("2026-11-02 WARN mock warning", true);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return Boolean.TRUE.equals(firstView.warningMarker);
          }
        });
    assertTrue(first.newWarnings());
    first.execute(CommandId.SHOW_LOG);
    assertEquals(1, firstView.logs.size());
    assertTrue(firstView.logs.get(0).contains("INFO started"));
    assertTrue(firstView.logs.get(0).contains("WARN mock warning"));
    assertEquals(Boolean.FALSE, firstView.warningMarker);
    assertFalse(first.newWarnings());
  }

  @Test
  void debugLogFileFollowsTheFileAndIsSaved() throws Exception {
    first.execute(CommandId.DEBUG_LOG_FILE);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return firstView.notices.contains(
                UiText.NOTICE_LOG_FILE_ON.format(logFiles.directory()));
          }
        });
    assertTrue(first.debugLogFile());
    assertTrue(first.preferences().options().debugLogFile());

    first.execute(CommandId.DEBUG_LOG_FILE);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return firstView.notices.contains(UiText.NOTICE_LOG_FILE_OFF.text());
          }
        });
    assertFalse(first.debugLogFile());
    assertFalse(first.preferences().options().debugLogFile());

    logFiles.fail = true;
    first.execute(CommandId.DEBUG_LOG_FILE);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return firstView.notices.contains(UiText.NOTICE_LOG_FILE_FAILED.format("mock failure"));
          }
        });
    assertFalse(first.debugLogFile());
  }

  @Test
  void folderItemsCreateTheFolderAndOpenIt() throws Exception {
    first.execute(CommandId.OPEN_LOG_FOLDER);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return firstView.launched.size() == 1;
          }
        });
    assertTrue(Files.isDirectory(logFiles.directory()));
    assertEquals(logFiles.directory().toRealPath(), firstView.launched.get(0));
    first.execute(CommandId.OPEN_SETTINGS_FOLDER);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return firstView.launched.size() == 2;
          }
        });
    assertEquals(temp.toRealPath(), firstView.launched.get(1));
  }

  @Test
  void reportAnIssueCopiesTheDetailsThenOpensTheIssuePage() throws Exception {
    first.execute(CommandId.REPORT_ISSUE);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !firstView.urls.isEmpty();
          }
        });
    assertEquals(List.of(AboutInfo.ISSUES_URL), firstView.urls);
    assertNotNull(firstView.clipboard);
    assertTrue(firstView.clipboard.contains("Fake toolkit"), firstView.clipboard);
    assertEquals(UiText.NOTICE_ISSUE_DETAILS.text(), firstView.lastNotice());
  }

  // User guide.

  @Test
  void documentationWritesTheGuideThenShowsItOrNamesTheFile() throws Exception {
    final Path copy = temp.toRealPath().resolve("cache").resolve("guide.html");
    firstView.browser = true;
    first.execute(CommandId.DOCUMENTATION);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return firstView.guides.size() == 1;
          }
        });
    assertEquals(copy, firstView.guides.get(0));
    assertTrue(Files.readString(copy).startsWith("<!DOCTYPE html>"));
    assertTrue(firstView.launched.isEmpty());

    firstView.browser = false;
    first.execute(CommandId.DOCUMENTATION);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return firstView.launched.size() == 1;
          }
        });
    assertEquals(copy, firstView.launched.get(0));

    firstView.launchResult = false;
    first.execute(CommandId.DOCUMENTATION);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return firstView.launched.size() == 2;
          }
        });
    assertEquals(UiText.NOTICE_GUIDE_AT.format(copy), firstView.lastNotice());
  }

  @Test
  void aGuideWindowFailingLaterOpensTheGuideElsewhere() {
    Path copy = temp.resolve("guide.html");
    first.showGuideElsewhere(copy);
    assertEquals(List.of(copy), firstView.launched);

    firstView.launchResult = false;
    first.showGuideElsewhere(copy);
    assertEquals(2, firstView.launched.size());
    assertEquals(UiText.NOTICE_GUIDE_AT.format(copy), firstView.lastNotice());
  }

  // Handed files already open.

  @Test
  void aHandedFileOpenInAnotherWindowActivatesItsTab() throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 1; i <= 50; i++) {
      text.append("line ").append(i).append('\n');
    }
    Path file = Files.writeString(temp.resolve("handed.log"), text.toString());
    openIn(first, file);
    final DocumentController shown = first.documents().get(0);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !shown.state().indexing();
          }
        });
    first.execute(CommandId.NEW_WINDOW);
    final ApplicationController second = set.windows().get(1);
    assertSame(second, set.target());

    set.openHandoff(List.of(new FileRequest(file, 30)), false);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return firstView.raised == 1;
          }
        });
    assertTrue(second.documents().isEmpty());
    assertEquals(1, first.documents().size());
    assertSame(shown, first.activeDocument());
    assertEquals(29, shown.sourceLine(shown.selection().caret().line()));
    assertFalse(shown.following());

    set.openHandoff(List.of(new FileRequest(file, 0)), true);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return firstView.raised == 2;
          }
        });
    assertTrue(second.documents().isEmpty());
    assertTrue(shown.following());
  }
}
