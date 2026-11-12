package io.github.ajdailey.qlogg.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class UiFailuresTest {
  private long now;
  private int quits;

  private UiFailures failures() {
    return new UiFailures(
        new LongSupplier() {
          @Override
          public long getAsLong() {
            return now;
          }
        },
        new Runnable() {
          @Override
          public void run() {
            quits++;
          }
        });
  }

  private static RuntimeException failure() {
    return new IllegalStateException("mock failure");
  }

  @Test
  void tenFailuresWithinOneSecondKeepRunning() {
    UiFailures failures = failures();
    for (int i = 0; i < UiFailures.LIMIT; i++) {
      failures.runtime(failure());
    }
    assertFalse(failures.failed());
    assertEquals(0, quits);
  }

  @Test
  void theEleventhFailureWithinOneSecondQuitsOnce() {
    UiFailures failures = failures();
    for (int i = 0; i <= UiFailures.LIMIT; i++) {
      now += UiFailures.WINDOW_NANOS / UiFailures.LIMIT;
      failures.runtime(failure());
    }
    assertTrue(failures.failed());
    assertEquals(1, quits);
    failures.runtime(failure());
    failures.error(new Error("mock error"));
    assertEquals(1, quits);
  }

  @Test
  void failuresSpreadOverMoreThanOneSecondKeepRunning() {
    UiFailures failures = failures();
    for (int i = 0; i < 100; i++) {
      now += UiFailures.WINDOW_NANOS / UiFailures.LIMIT + 1;
      failures.runtime(failure());
    }
    assertFalse(failures.failed());
    assertEquals(0, quits);
  }

  @Test
  void anErrorQuits() {
    UiFailures failures = failures();
    failures.error(new Error("mock error"));
    assertTrue(failures.failed());
    assertEquals(1, quits);
  }
}
