package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The retry delay of a save with a failed write. */
class SaveBackoffTest {
  @Test
  void theDelayDoublesPerFailureUpToTheCapAndASaveWithoutAFailureEndsTheRun() {
    SaveBackoff backoff = new SaveBackoff();
    assertEquals(0, backoff.record(false));
    List<Integer> delays = new ArrayList<Integer>();
    for (int i = 0; i < 8; i++) {
      delays.add(backoff.record(true));
    }
    assertEquals(List.of(1000, 2000, 4000, 8000, 16000, 32000, 60000, 60000), delays);
    int delay = 0;
    for (int i = 0; i < 100; i++) {
      delay = backoff.record(true);
    }
    assertEquals(SaveBackoff.MAX_MILLIS, delay);
    assertEquals(0, backoff.record(false));
    assertEquals(ApplicationController.SAVE_DELAY_MILLIS, backoff.record(true));
  }
}
