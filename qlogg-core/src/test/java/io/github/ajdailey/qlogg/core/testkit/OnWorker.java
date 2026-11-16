package io.github.ajdailey.qlogg.core.testkit;

import io.github.ajdailey.qlogg.core.concurrent.CpuSteps;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs a body on a {@code qlogg-search-} virtual thread and waits for it, bounded. */
public final class OnWorker {
  private static final long BOUND_SECONDS = 30;

  private OnWorker() {}

  /**
   * Runs the body on a worker and returns its value.
   *
   * @param <T> the value type
   * @param workers starts the thread
   * @param body the body
   * @return the body's value
   * @throws Exception what the body threw
   * @throws AssertionError when the body did not end within the bound
   */
  public static <T> T call(Workers workers, CpuSteps.Step<T> body) throws Exception {
    AtomicReference<T> value = new AtomicReference<T>();
    Throwable thrown = run(workers, body, value);
    if (thrown instanceof Exception e) {
      throw e;
    }
    if (thrown instanceof Error e) {
      throw e;
    }
    return value.get();
  }

  /**
   * Runs the body on a worker and returns what it threw.
   *
   * @param workers starts the thread
   * @param body the body
   * @return what the body threw, or null when it returned
   * @throws InterruptedException when interrupted while waiting
   * @throws AssertionError when the body did not end within the bound
   */
  public static Throwable thrown(Workers workers, CpuSteps.Step<?> body)
      throws InterruptedException {
    return run(workers, body, new AtomicReference<Object>());
  }

  private static <T> Throwable run(
      Workers workers, final CpuSteps.Step<? extends T> body, final AtomicReference<T> value)
      throws InterruptedException {
    final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
    final CountDownLatch done = new CountDownLatch(1);
    workers.start(
        Workers.Kind.SEARCH,
        new Runnable() {
          @Override
          public void run() {
            try {
              value.set(body.run());
            } catch (Exception | Error e) {
              thrown.set(e);
            } finally {
              done.countDown();
            }
          }
        });
    if (!done.await(BOUND_SECONDS, TimeUnit.SECONDS)) {
      throw new AssertionError("The worker did not end within " + BOUND_SECONDS + " s");
    }
    return thrown.get();
  }
}
