package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.ui.swt.platform.LaunchDocuments;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * Collects the files a launch receives as {@code SWT.OpenDocument} events before it hands off to
 * the running instance, on platforms that send them ({@link LaunchDocuments}). {@link #await} runs
 * the event loop until events arrive and {@link #QUIET_NANOS} pass with no further one, or {@link
 * #BOUND_NANOS} pass with none.
 */
public final class OpenDocuments {
  /** Quiet time after the last event that ends the wait. */
  static final long QUIET_NANOS = 200_000_000L;

  /** Wait with no event at all. */
  static final long BOUND_NANOS = 1_000_000_000L;

  private OpenDocuments() {}

  /** Receives one file. */
  interface Receiver {
    /**
     * Takes a file.
     *
     * @param file the path the event names
     */
    void received(String file);
  }

  /** The event loop the wait runs. */
  interface Source {
    /**
     * Starts delivering files.
     *
     * @param receiver takes each file
     */
    void start(Receiver receiver);

    /**
     * Dispatches pending events, or waits for one up to a bound.
     *
     * @param maxNanos the longest wait
     */
    void step(long maxNanos);
  }

  /**
   * Collects the files of the launch's {@code OpenDocument} events, on platforms that deliver files
   * that way; creates the Display on the calling thread and disposes it before returning.
   *
   * @return the file paths in arrival order, empty elsewhere or when none came
   */
  public static List<String> await() {
    return LaunchDocuments.PLATFORM.await(
        new LaunchDocuments.Wait() {
          @Override
          public List<String> run() {
            Display display = new Display();
            try {
              return collect(new DisplaySource(display), System::nanoTime);
            } finally {
              display.dispose();
            }
          }
        });
  }

  /**
   * Runs a source until files arrived and {@link #QUIET_NANOS} passed with no further one, or
   * {@link #BOUND_NANOS} passed with none.
   *
   * @param source the event loop
   * @param clock nanoseconds
   * @return the files in arrival order
   */
  static List<String> collect(Source source, final LongSupplier clock) {
    final List<String> files = new ArrayList<String>();
    final long start = clock.getAsLong();
    final long[] last = {start};
    source.start(
        new Receiver() {
          @Override
          public void received(String file) {
            files.add(file);
            last[0] = clock.getAsLong();
          }
        });
    while (true) {
      long now = clock.getAsLong();
      long end = files.isEmpty() ? start + BOUND_NANOS : last[0] + QUIET_NANOS;
      if (now - end >= 0) {
        return files;
      }
      source.step(end - now);
    }
  }

  /** Dispatches a Display's events; an idle step sleeps until an event or a timer wakes it. */
  private static final class DisplaySource implements Source {
    private final Display display;
    private final SwtUiDispatcher timers;

    DisplaySource(Display display) {
      this.display = display;
      timers = new SwtUiDispatcher(display);
    }

    @Override
    public void start(final Receiver receiver) {
      display.addListener(
          SWT.OpenDocument,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              if (event.text != null && !event.text.isEmpty()) {
                receiver.received(event.text);
              }
            }
          });
    }

    @Override
    public void step(long maxNanos) {
      if (!display.readAndDispatch()) {
        timers.timerExec(
            (int) Math.max(1, maxNanos / 1_000_000L),
            new Runnable() {
              @Override
              public void run() {
                // Wakes the sleep.
              }
            });
        display.sleep();
      }
    }
  }
}
