package io.github.ajdailey.qlogg.app.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManualUiDispatcherTest {
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
}
