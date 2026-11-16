package io.github.ajdailey.qlogg.core.concurrent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Runs the CPU-bound steps of a {@link Workers.Kind} task on the CPU pool ({@link
 * Workers#executeCpu}). The task's virtual thread parks while a step runs, so a long or spinning
 * step holds a pool thread, not a carrier the other kinds need.
 *
 * <p>A step must be bounded: work that scales with the file is cut into steps of a bounded slice,
 * such as {@link #STEP_LINES} lines, and {@link #run(Workers, Steps)} queues each step behind the
 * pool tasks submitted while the previous one ran. Reads of whole blocks stay on the virtual
 * thread; a step may read lines.
 *
 * <p>On a thread that is not virtual, or with null workers, the step runs on the calling thread, so
 * a pool thread never waits on the pool.
 */
public final class CpuSteps {
  /** Lines a step over lines covers. */
  public static final int STEP_LINES = 4096;

  /**
   * One step returning a value.
   *
   * @param <T> the value type
   */
  public interface Step<T> {
    /**
     * Runs the step.
     *
     * @return the value
     * @throws IOException on a read error
     */
    T run() throws IOException;
  }

  /** Bounded steps of one piece of work. */
  public interface Steps {
    /**
     * Runs one step.
     *
     * @return true while work remains
     * @throws IOException on a read error
     */
    boolean step() throws IOException;
  }

  private CpuSteps() {}

  /**
   * Runs one step on the CPU pool and waits for it. An {@link IOException}, {@link Error}, {@link
   * CancellationException}, {@link UncheckedIOException}, {@link IllegalArgumentException} or
   * {@link IllegalStateException} the step throws is thrown here; any other exception is thrown as
   * the cause of an {@link IllegalStateException}, so a step returns other outcomes as values.
   *
   * @param <T> the value type
   * @param workers runs the step, or null to run it on the calling thread
   * @param step the step
   * @return the step's value
   * @throws IOException when the step throws one
   * @throws CancellationException when the workers are closed, or the calling thread is interrupted
   *     while it waits (its interrupt flag set again)
   */
  public static <T> T call(Workers workers, Step<T> step) throws IOException {
    if (workers == null || !Thread.currentThread().isVirtual()) {
      return step.run();
    }
    Handoff<T> handoff = new Handoff<T>(step);
    submit(workers, handoff);
    try {
      handoff.done.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancellationException("Interrupted while a CPU step ran");
    }
    return handoff.result();
  }

  /**
   * Runs one step that reads nothing, as {@link #call} does.
   *
   * @param <T> the value type
   * @param workers runs the step, or null to run it on the calling thread
   * @param step the step
   * @return the step's value
   * @throws CancellationException as {@link #call} throws it
   */
  public static <T> T compute(Workers workers, final Supplier<T> step) {
    try {
      return call(
          workers,
          new Step<T>() {
            @Override
            public T run() {
              return step.get();
            }
          });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Runs steps one after another, each as {@link #call} runs it, until one returns false.
   *
   * @param workers runs the steps, or null to run them on the calling thread
   * @param steps the steps
   * @throws IOException when a step throws one
   * @throws CancellationException as {@link #call} throws it
   */
  public static void run(Workers workers, final Steps steps) throws IOException {
    Step<Boolean> one =
        new Step<Boolean>() {
          @Override
          public Boolean run() throws IOException {
            return steps.step();
          }
        };
    while (call(workers, one)) {
      // Each step queues behind the pool tasks submitted while the previous one ran.
    }
  }

  /** Submits, retrying while the pool's queue is full. */
  private static void submit(Workers workers, Runnable task) {
    while (true) {
      try {
        workers.executeCpu(task);
        return;
      } catch (RejectedExecutionException e) {
        if (workers.isClosed()) {
          throw new CancellationException("Workers closed");
        }
      }
      try {
        Thread.sleep(1);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CancellationException("Interrupted while the CPU pool was full");
      }
    }
  }

  /** One step and its outcome, published by the latch. */
  private static final class Handoff<T> implements Runnable {
    final CountDownLatch done = new CountDownLatch(1);
    private final Step<T> step;
    private T value;
    private Throwable failure;

    Handoff(Step<T> step) {
      this.step = step;
    }

    @Override
    public void run() {
      try {
        value = step.run();
      } catch (Exception | Error e) {
        failure = e;
      } finally {
        done.countDown();
      }
    }

    T result() throws IOException {
      if (failure == null) {
        return value;
      }
      if (failure instanceof IOException e) {
        throw e;
      }
      if (failure instanceof CancellationException e) {
        throw e;
      }
      if (failure instanceof UncheckedIOException e) {
        throw e;
      }
      if (failure instanceof IllegalArgumentException e) {
        throw e;
      }
      if (failure instanceof IllegalStateException e) {
        throw e;
      }
      if (failure instanceof Error e) {
        throw e;
      }
      throw new IllegalStateException("A CPU step failed", failure);
    }
  }
}
