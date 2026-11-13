package io.github.ajdailey.qlogg.ui.swt;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.widgets.Display;

/** Drives an SWT event loop in tests until a condition holds, with no fixed sleep. */
public final class EventLoop {
  private EventLoop() {}

  /**
   * Dispatches events until the condition holds or the bound passes. A repeating 20 ms timer wakes
   * {@code Display.sleep} so the condition is rechecked without other events. Do not use an empty
   * queue as the condition: the timer queues an event after every sleep, so the condition holds
   * only by chance and the call can run to its bound (measured on the Windows runners). Drain with
   * {@code while (display.readAndDispatch())} instead.
   *
   * @param display the Display, on the calling thread
   * @param condition checked between events
   * @param bound the longest wait
   * @return true when the condition held
   */
  public static boolean runUntil(final Display display, BooleanSupplier condition, Duration bound) {
    long deadline = System.nanoTime() + bound.toNanos();
    final boolean[] stop = new boolean[1];
    Runnable wake =
        new Runnable() {
          @Override
          public void run() {
            if (!stop[0] && !display.isDisposed()) {
              display.timerExec(20, this);
            }
          }
        };
    display.timerExec(20, wake);
    try {
      while (!condition.getAsBoolean()) {
        if (System.nanoTime() > deadline || display.isDisposed()) {
          return condition.getAsBoolean();
        }
        if (!display.readAndDispatch()) {
          display.sleep();
        }
      }
      return true;
    } finally {
      stop[0] = true;
    }
  }
}
