package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.Session;
import io.github.ajdailey.qlogg.app.theme.ThemeService;
import io.github.ajdailey.qlogg.app.view.ManualUiDispatcher;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * View > Always on Top on fake views: the toggle, an unsupported platform, the session round trip
 * per window, and new windows and handed files starting off.
 */
class AlwaysOnTopTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private final List<Run> runs = new ArrayList<Run>();

  /** One instance: its workers, window set and views. */
  private final class Run {
    final Workers workers;
    final WindowSet set;
    final List<FakeView> views = new ArrayList<FakeView>();
    final ApplicationController first;

    Run(final boolean supported) {
      workers =
          new Workers(
              2,
              new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable error) {
                  throw new AssertionError("worker failed", error);
                }
              });
      ConfigStore config = ApplicationControllerTest.config(temp);
      set =
          new WindowSet(
              workers,
              ui,
              ApplicationControllerTest.services(workers, MemoryBudget.of(1L << 28)),
              config,
              Preferences.DEFAULTS,
              ThemeService.LIGHT);
      set.setFactory(
          new WindowSet.Factory() {
            @Override
            public ApplicationController create(WindowSet owner, WindowGeometry.Request at) {
              return new ApplicationController(owner, view(supported));
            }
          });
      first = new ApplicationController(set, view(supported));
    }

    private FakeView view(boolean supported) {
      FakeView view = new FakeView();
      view.onTopSupported = supported;
      views.add(view);
      return view;
    }

    /** Quits and waits until every view is disposed and every worker ended. */
    void quit() throws InterruptedException {
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
                  return workers.liveThreads() == 0;
                }
              },
              BOUND));
      for (FakeView view : views) {
        view.assertConfined();
      }
    }
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    for (Run run : runs) {
      if (!run.set.isQuitting()) {
        run.quit();
      }
    }
  }

  private Run start(boolean supported) {
    Run run = new Run(supported);
    runs.add(run);
    return run;
  }

  /** Starts an instance that restores the saved session, one window per saved window. */
  private Run restore(boolean supported) {
    Run run = start(supported);
    List<Session> sessions = run.set.config().sessions().loadWindows();
    run.first.restoreSession(sessions.get(0));
    for (int i = 1; i < sessions.size(); i++) {
      run.set.openWindow().restoreSession(sessions.get(i));
    }
    return run;
  }

  @Test
  void theToggleSetsTheViewEachWay() {
    Run run = start(true);
    CommandStates states = new CommandStates(run.first);
    assertTrue(states.enabled(CommandId.ALWAYS_ON_TOP));
    assertFalse(states.checked(CommandId.ALWAYS_ON_TOP));
    run.first.execute(CommandId.ALWAYS_ON_TOP);
    assertTrue(states.checked(CommandId.ALWAYS_ON_TOP));
    run.first.execute(CommandId.ALWAYS_ON_TOP);
    assertFalse(states.checked(CommandId.ALWAYS_ON_TOP));
    assertEquals(List.of(true, false), run.views.get(0).onTopCalls);
  }

  @Test
  void anUnsupportedPlatformDisablesTheItem() {
    Run run = start(false);
    CommandStates states = new CommandStates(run.first);
    assertFalse(states.enabled(CommandId.ALWAYS_ON_TOP));
    run.first.execute(CommandId.ALWAYS_ON_TOP);
    assertFalse(states.checked(CommandId.ALWAYS_ON_TOP));
    assertFalse(run.first.alwaysOnTop().saved());
    assertEquals(List.of(), run.views.get(0).onTopCalls);
  }

  @Test
  void theFlagIsSavedPerWindowAndRestoredWithIt() throws Exception {
    Run before = start(true);
    before.first.setSessionOwner(true);
    ApplicationController second = before.set.openWindow();
    assertNotNull(second);
    second.execute(CommandId.ALWAYS_ON_TOP);
    before.quit();
    List<Session> saved = before.set.config().sessions().loadWindows();
    assertEquals(2, saved.size());
    assertFalse(saved.get(0).onTop());
    assertTrue(saved.get(1).onTop());

    Run after = restore(true);
    assertEquals(List.of(), after.views.get(0).onTopCalls);
    assertEquals(List.of(true), after.views.get(1).onTopCalls);
    assertFalse(after.first.alwaysOnTop().shown());
    assertTrue(after.set.windows().get(1).alwaysOnTop().shown());
  }

  /** A start where the platform is unsupported keeps the saved flag for the next start. */
  @Test
  void anUnsupportedStartKeepsTheSavedFlag() throws Exception {
    Run before = start(true);
    before.first.setSessionOwner(true);
    before.first.execute(CommandId.ALWAYS_ON_TOP);
    before.quit();

    Run unsupported = restore(false);
    assertFalse(unsupported.first.alwaysOnTop().shown());
    assertEquals(List.of(), unsupported.views.get(0).onTopCalls);
    unsupported.quit();
    assertTrue(unsupported.set.config().sessions().loadWindows().get(0).onTop());
  }

  @Test
  void aNewWindowAndHandedFilesDoNotInheritIt() throws Exception {
    final Run run = start(true);
    run.first.execute(CommandId.ALWAYS_ON_TOP);
    run.first.execute(CommandId.NEW_WINDOW);
    final ApplicationController second = run.set.windows().get(1);
    assertFalse(second.alwaysOnTop().saved());
    run.first.windowActivated();
    Path file = Files.writeString(temp.resolve("handed.log"), "one\n");
    run.set.openHandoff(List.of(new FileRequest(file, 0)), false);
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return run.first.documents().size() == 1;
              }
            },
            BOUND));
    assertTrue(run.first.alwaysOnTop().shown());
    assertEquals(List.of(true), run.views.get(0).onTopCalls);
    assertEquals(List.of(), run.views.get(1).onTopCalls);
  }
}
