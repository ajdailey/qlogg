package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.view.ManualUiDispatcher;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LaunchCursor}: a launch an application took holds the busy cursor until its timer runs; a
 * refused one leaves it idle.
 */
class LaunchCursorTest {
  @TempDir Path temp;

  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private final FakeView view = new FakeView();

  @AfterEach
  void confined() {
    view.assertConfined();
  }

  @Test
  void aLaunchHoldsTheCursorUntilItsTimer() {
    ui.holdTimers(LaunchCursor.MILLIS);
    assertTrue(LaunchCursor.launch(view, ui, temp));
    assertTrue(LaunchCursor.launchUrl(view, ui, "https://example.invalid/"));
    assertEquals(List.of(temp), view.launched);
    assertEquals(List.of("https://example.invalid/"), view.urls);
    assertEquals(2, view.active.get());
    assertEquals(0, ui.runPending());
    assertEquals(2, ui.heldTimers());
    assertEquals(2, view.active.get());
    ui.runHeldTimers();
    assertEquals(2, ui.runPending());
    assertEquals(0, view.active.get());
  }

  @Test
  void aRefusedLaunchLeavesTheCursorIdle() {
    view.launchResult = false;
    assertFalse(LaunchCursor.launch(view, ui, temp));
    assertFalse(LaunchCursor.launchUrl(view, ui, "https://example.invalid/"));
    assertEquals(0, view.activations.get());
    assertEquals(0, ui.runPending());
  }
}
