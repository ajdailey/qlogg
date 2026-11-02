package io.github.ajdailey.qlogg.app.view;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coalesces update notices from worker threads into flushes on the UI thread, at most one per
 * interval. {@link #post} is callable from any thread and schedules at most one flush; a flush
 * sooner than the interval after the previous one waits on {@link UiDispatcher#timerExec}. The
 * flush reads the latest published state itself, so posts that arrive meanwhile need no flush of
 * their own.
 */
public final class UiUpdateQueue {
  private final UiDispatcher ui;
  private final long intervalNanos;
  private final Runnable flush;
  private final AtomicBoolean posted = new AtomicBoolean();
  private final Runnable check =
      new Runnable() {
        @Override
        public void run() {
          check();
        }
      };
  private final Runnable apply =
      new Runnable() {
        @Override
        public void run() {
          apply();
        }
      };
  private boolean timerSet;
  private long lastFlushNanos;
  private boolean closed;

  /**
   * Creates the queue.
   *
   * @param ui the UI-thread dispatcher
   * @param intervalMillis least time between flushes
   * @param flush runs on the UI thread
   */
  public UiUpdateQueue(UiDispatcher ui, int intervalMillis, Runnable flush) {
    this.ui = ui;
    this.intervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMillis);
    this.flush = flush;
    this.lastFlushNanos = System.nanoTime() - intervalNanos;
  }

  /** Schedules a flush; callable from any thread. */
  public void post() {
    if (posted.compareAndSet(false, true)) {
      ui.asyncExec(check);
    }
  }

  /** Stops every later flush, including one already scheduled. Call on the UI thread. */
  public void close() {
    closed = true;
  }

  private void check() {
    if (closed) {
      return;
    }
    long since = System.nanoTime() - lastFlushNanos;
    if (since < intervalNanos) {
      if (!timerSet) {
        timerSet = true;
        int delay = (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(intervalNanos - since));
        ui.timerExec(delay, apply);
      }
      return;
    }
    apply();
  }

  private void apply() {
    timerSet = false;
    posted.set(false);
    if (closed) {
      return;
    }
    lastFlushNanos = System.nanoTime();
    flush.run();
  }
}
