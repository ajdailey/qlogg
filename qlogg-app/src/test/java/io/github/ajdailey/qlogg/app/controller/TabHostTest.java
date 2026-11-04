package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.theme.ThemeService;
import io.github.ajdailey.qlogg.app.view.ManualUiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link TabHost} routes activity so each window's busy count returns to 0 across moves. */
class TabHostTest {
  @TempDir Path temp;

  @Test
  void anOperationEndsTheBusyStateOfTheWindowItStartedIn() throws Exception {
    try (Workers workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                throw new AssertionError("worker failed", error);
              }
            })) {
      WindowSet set =
          new WindowSet(
              workers,
              new ManualUiDispatcher(),
              ApplicationControllerTest.services(workers, MemoryBudget.of(1L << 20)),
              ApplicationControllerTest.config(temp),
              Preferences.DEFAULTS,
              ThemeService.LIGHT);
      FakeView firstView = new FakeView();
      FakeView secondView = new FakeView();
      ApplicationController first = new ApplicationController(set, firstView);
      ApplicationController second = new ApplicationController(set, secondView);
      TabHost host = new TabHost(first, firstView);

      host.setActive();
      host.moveTo(second, secondView, set.windows());
      host.setActive();
      assertEquals(1, firstView.active.get());
      assertEquals(1, secondView.active.get());
      host.setIdle();
      assertEquals(0, firstView.active.get(), "the oldest count ends first");
      assertEquals(1, secondView.active.get());
      host.moveTo(first, firstView, set.windows());
      host.setIdle();
      host.setIdle();
      assertEquals(0, firstView.active.get());
      assertEquals(0, secondView.active.get(), "an idle with nothing active is ignored");

      host.setText("copied");
      assertEquals("copied", firstView.clipboard);
      host.moveTo(second, secondView, List.of(second));
      host.showNotice("note");
      assertEquals("note", secondView.lastNotice());
      assertNull(firstView.lastNotice());
    }
  }
}
