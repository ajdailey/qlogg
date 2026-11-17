package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.view.UiDispatcher;

/**
 * The timer of {@link WindowSet}'s debounced save and of the retry of a failed one; at most one
 * timer runs the save. Confined to the UI thread.
 */
final class SaveTimer {
  private final UiDispatcher ui;
  private final Runnable save;
  private long generation;
  private boolean armed;

  /**
   * Creates the timer.
   *
   * @param ui the dispatcher whose timer runs the save
   * @param save runs on the UI thread when a timer fires
   */
  SaveTimer(UiDispatcher ui, Runnable save) {
    this.ui = ui;
    this.save = save;
  }

  /** Runs the save after the delay, replacing a waiting timer. */
  void restart(int delayMillis) {
    final long armedAt = ++generation;
    armed = true;
    ui.timerExec(
        delayMillis,
        new Runnable() {
          @Override
          public void run() {
            if (armedAt == generation) {
              armed = false;
              save.run();
            }
          }
        });
  }

  /** Runs the save after the delay unless a timer waits; that timer's save covers the retry. */
  void retry(int delayMillis) {
    if (!armed) {
      restart(delayMillis);
    }
  }

  /** Drops a waiting timer. */
  void cancel() {
    generation++;
    armed = false;
  }
}
