package io.github.ajdailey.qlogg.app.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class BusyCounterTest {
  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private final List<Boolean> shown = new ArrayList<Boolean>();
  private final BusyCounter busy =
      new BusyCounter(
          ui,
          new BusyCounter.Indicator() {
            @Override
            public void busyChanged(boolean active) {
              shown.add(active);
            }
          });

  @Test
  void overlappingOperationsKeepTheIndicatorUntilTheLastEnds() {
    busy.setActive();
    busy.setActive();
    busy.setIdle();
    ui.runPending();
    assertEquals(List.of(true), shown);
    assertEquals(1, busy.active());
    busy.setIdle();
    ui.runPending();
    assertEquals(List.of(true, false), shown);
    busy.setActive();
    busy.setIdle();
    ui.runPending();
    assertEquals(List.of(true, false, true, false), shown);
  }

  @Test
  void anIdleWithNothingActiveIsIgnored() {
    busy.setIdle();
    busy.setActive();
    ui.runPending();
    assertEquals(List.of(true), shown);
    assertEquals(1, busy.active());
  }

  @Test
  void callsFromAWorkerApplyOnTheUiThread() throws Exception {
    final CountDownLatch posted = new CountDownLatch(1);
    Thread worker =
        Thread.ofPlatform()
            .start(
                new Runnable() {
                  @Override
                  public void run() {
                    busy.setActive();
                    busy.setIdle();
                    posted.countDown();
                  }
                });
    assertTrue(posted.await(10, TimeUnit.SECONDS));
    assertEquals(List.of(), shown);
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return shown.size() == 2;
              }
            },
            Duration.ofSeconds(10)));
    assertEquals(List.of(true, false), shown);
    assertTrue(worker.join(Duration.ofSeconds(10)));
  }
}
