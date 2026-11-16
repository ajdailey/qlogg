package io.github.ajdailey.qlogg.app.view;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Coalescing in {@link UiUpdateQueue}: posts before a flush share it, a flush inside the interval
 * waits on one timer, and {@code close} stops a scheduled flush. The interval is an hour, so every
 * flush after the first takes the timer path.
 */
class UiUpdateQueueTest {
  private static final int HOUR = 3_600_000;

  /** Records submissions; the test runs them. */
  private static final class Recorder implements UiDispatcher {
    final List<Runnable> posted = new ArrayList<Runnable>();
    final List<Runnable> timers = new ArrayList<Runnable>();

    @Override
    public void asyncExec(Runnable task) {
      posted.add(task);
    }

    @Override
    public void timerExec(int delayMillis, Runnable task) {
      timers.add(task);
    }

    @Override
    public boolean isUiThread() {
      return true;
    }

    void runPosted() {
      List<Runnable> tasks = new ArrayList<Runnable>(posted);
      posted.clear();
      for (Runnable task : tasks) {
        task.run();
      }
    }

    void runTimers() {
      List<Runnable> tasks = new ArrayList<Runnable>(timers);
      timers.clear();
      for (Runnable task : tasks) {
        task.run();
      }
    }
  }

  private static final class Counter implements Runnable {
    int flushes;

    @Override
    public void run() {
      flushes++;
    }
  }

  @Test
  void postsBeforeAFlushShareIt() {
    Recorder ui = new Recorder();
    Counter flush = new Counter();
    UiUpdateQueue queue = new UiUpdateQueue(ui, HOUR, flush);
    queue.post();
    queue.post();
    queue.post();
    assertEquals(1, ui.posted.size());
    ui.runPosted();
    assertEquals(1, flush.flushes);
    queue.post();
    assertEquals(1, ui.posted.size());
  }

  @Test
  void aFlushInsideTheIntervalWaitsOnOneTimer() {
    Recorder ui = new Recorder();
    Counter flush = new Counter();
    UiUpdateQueue queue = new UiUpdateQueue(ui, HOUR, flush);
    queue.post();
    ui.runPosted();
    queue.post();
    ui.runPosted();
    assertEquals(1, flush.flushes);
    assertEquals(1, ui.timers.size());
    queue.post();
    ui.runPosted();
    assertEquals(0, ui.posted.size());
    assertEquals(1, ui.timers.size());
    ui.runTimers();
    assertEquals(2, flush.flushes);
    queue.post();
    ui.runPosted();
    assertEquals(2, flush.flushes);
    assertEquals(1, ui.timers.size());
  }

  @Test
  void closeStopsAScheduledFlush() {
    Recorder ui = new Recorder();
    Counter flush = new Counter();
    UiUpdateQueue queue = new UiUpdateQueue(ui, HOUR, flush);
    queue.post();
    queue.close();
    ui.runPosted();
    assertEquals(0, flush.flushes);

    UiUpdateQueue timed = new UiUpdateQueue(ui, HOUR, flush);
    timed.post();
    ui.runPosted();
    timed.post();
    ui.runPosted();
    timed.close();
    ui.runTimers();
    assertEquals(1, flush.flushes);
  }
}
