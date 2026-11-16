package io.github.ajdailey.qlogg.app.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ManualUiDispatcherTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @Test
  void runsInOrderAndDropsAfterDispose() {
    final ManualUiDispatcher ui = new ManualUiDispatcher();
    final List<String> ran = new ArrayList<String>();
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            ran.add("a");
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    ran.add("c");
                  }
                });
          }
        });
    ui.timerExec(
        50,
        new Runnable() {
          @Override
          public void run() {
            ran.add("b");
          }
        });
    assertTrue(ui.isUiThread());
    assertEquals(3, ui.runPending());
    assertEquals(List.of("a", "b", "c"), ran);
    ui.dispose();
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            ran.add("late");
          }
        });
    assertEquals(0, ui.runPending());
  }

  /** A timer armed off the UI thread throws there and fails the next drain, though caught. */
  @Test
  void timerExecOffTheUiThreadIsRefused() throws InterruptedException {
    final ManualUiDispatcher ui = new ManualUiDispatcher();
    final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
    Thread worker =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                try {
                  ui.timerExec(
                      50,
                      new Runnable() {
                        @Override
                        public void run() {}
                      });
                } catch (IllegalStateException e) {
                  thrown.set(e);
                }
              }
            });
    worker.start();
    worker.join();
    assertTrue(thrown.get() instanceof IllegalStateException);
    assertEquals(0, ui.queued());
    AssertionError error =
        assertThrows(
            AssertionError.class,
            new Executable() {
              @Override
              public void execute() {
                ui.runPending();
              }
            });
    assertTrue(error.getMessage().startsWith("timerExec ran on "), error.getMessage());
  }

  /** A condition another thread sets ends the wait though no UI task arrives. */
  @Test
  void runUntilSeesAConditionSetOffTheUiThread() throws InterruptedException {
    ManualUiDispatcher ui = new ManualUiDispatcher();
    final AtomicBoolean set = new AtomicBoolean();
    final CountDownLatch returned = new CountDownLatch(1);
    final AtomicBoolean late = new AtomicBoolean();
    Thread worker =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                set.set(true);
                try {
                  // Set when runUntil waits out most of its bound, as before the recheck.
                  late.set(!returned.await(BOUND.toSeconds() / 2, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            });
    worker.start();
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return set.get();
              }
            },
            BOUND));
    returned.countDown();
    worker.join();
    assertFalse(late.get());
  }
}
