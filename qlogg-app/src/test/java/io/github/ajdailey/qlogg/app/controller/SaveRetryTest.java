package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SessionStore;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.theme.ThemeService;
import io.github.ajdailey.qlogg.app.view.ManualUiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A save whose settings or session write fails: a non-empty folder at the file's name fails every
 * write, on every OS, until the test deletes it. Every timer is held, so each step runs one save
 * and the files are read only after its worker ended.
 */
class SaveRetryTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private final FakeView view = new FakeView();
  private Workers workers;
  private ConfigStore config;
  private WindowSet set;
  private ApplicationController window;

  @BeforeEach
  void setUp() throws InterruptedException {
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
            ApplicationControllerTest.services(workers, MemoryBudget.of(1L << 28)),
            config,
            Preferences.DEFAULTS,
            ThemeService.LIGHT);
    window = new ApplicationController(set, view);
    ui.holdTimers(ManualUiDispatcher.EVERY_DELAY);
    ui.runPending();
    step();
  }

  @AfterEach
  void tearDown() throws IOException, InterruptedException {
    unblock(SessionStore.FILE);
    unblock(SettingsLocation.SETTINGS_FILE);
    if (!set.isQuitting()) {
      quit();
    }
    view.assertConfined();
  }

  private void block(String name) throws IOException {
    Path blocker = Files.createDirectories(temp.resolve(name));
    Files.writeString(blocker.resolve("keep.txt"), "mock\n");
  }

  private void unblock(String name) throws IOException {
    Path blocker = temp.resolve(name);
    if (Files.isDirectory(blocker)) {
      Files.delete(blocker.resolve("keep.txt"));
      Files.delete(blocker);
    }
  }

  /**
   * Fires the held timers, runs their save, and returns the delays of the timers armed then. A
   * timer starts its save's worker before the wait, so the wait ends only after that worker.
   */
  private List<Integer> step() throws InterruptedException {
    ui.runHeldTimers();
    ui.runPending();
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return workers.liveThreads() == 0;
              }
            },
            BOUND));
    ui.runPending();
    return ui.heldDelays();
  }

  private void quit() throws InterruptedException {
    set.quit();
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return view.disposed && workers.liveThreads() == 0;
              }
            },
            BOUND));
  }

  @Test
  void aFailedSessionSaveRetriesAtADoublingDelayAndReachesTheFileWithoutAChange() throws Exception {
    window.setSessionOwner(true);
    block(SessionStore.FILE);
    set.scheduleSave();
    assertEquals(List.of(ApplicationController.SAVE_DELAY_MILLIS), step());
    assertEquals(List.of(2000), step());
    assertEquals(List.of(4000), step());
    unblock(SessionStore.FILE);
    assertEquals(List.of(), step());
    assertEquals(1, config.sessions().loadWindows().size());
  }

  @Test
  void aFailedSettingsSaveReachesTheFileWithoutAChange() throws Exception {
    block(SettingsLocation.SETTINGS_FILE);
    set.setPreferences(Preferences.DEFAULTS.withSplitPercent(42));
    assertEquals(List.of(ApplicationController.SAVE_DELAY_MILLIS), step());
    assertEquals(List.of(2000), step());
    unblock(SettingsLocation.SETTINGS_FILE);
    assertEquals(List.of(), step());
    assertEquals(42, config.load().splitPercent());
  }

  @Test
  void quitArmsNoRetryForAFailurePostedBeforeItOrForItsOwnSave() throws Exception {
    window.setSessionOwner(true);
    block(SessionStore.FILE);
    set.scheduleSave();
    assertEquals(List.of(ApplicationController.SAVE_DELAY_MILLIS), step());
    ui.runHeldTimers();
    ui.runPending();
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return workers.liveThreads() == 0 && ui.queued() > 0;
              }
            },
            BOUND),
        "the failed save posted its retry");
    quit();
    assertEquals(List.of(), ui.heldDelays());
  }
}
