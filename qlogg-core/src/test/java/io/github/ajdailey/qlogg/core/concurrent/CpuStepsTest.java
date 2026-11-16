package io.github.ajdailey.qlogg.core.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.testkit.OnWorker;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CpuStepsTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final AtomicBoolean stop = new AtomicBoolean();
  private final Workers workers =
      new Workers(
          Runtime.getRuntime().availableProcessors(),
          new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
              failures.add(error);
            }
          });

  @AfterEach
  void close() {
    stop.set(true);
    assertTrue(workers.close(Workers.CLOSE_BOUND));
    assertTrue(failures.isEmpty(), failures.toString());
  }

  @Test
  void aStepFromAVirtualThreadRunsOnThePool() throws Exception {
    String name =
        OnWorker.call(
            workers,
            new CpuSteps.Step<String>() {
              @Override
              public String run() throws IOException {
                return CpuSteps.compute(
                    workers,
                    new Supplier<String>() {
                      @Override
                      public String get() {
                        return Thread.currentThread().getName();
                      }
                    });
              }
            });
    assertTrue(name.startsWith("qlogg-cpu-"), name);
  }

  @Test
  void aStepOnAPlatformThreadRunsThere() throws Exception {
    final Thread caller = Thread.currentThread();
    Thread ran =
        CpuSteps.call(
            workers,
            new CpuSteps.Step<Thread>() {
              @Override
              public Thread run() {
                return Thread.currentThread();
              }
            });
    assertSame(caller, ran);
  }

  @Test
  void whatAStepThrowsReachesTheCaller() throws Exception {
    final IOException unreadable = new IOException("mock read failure");
    final StackOverflowError deep = new StackOverflowError("mock overflow");
    final AtomicReference<Throwable> first = new AtomicReference<Throwable>();
    final AtomicReference<Throwable> second = new AtomicReference<Throwable>();
    OnWorker.call(
        workers,
        new CpuSteps.Step<Void>() {
          @Override
          public Void run() {
            try {
              CpuSteps.call(
                  workers,
                  new CpuSteps.Step<Void>() {
                    @Override
                    public Void run() throws IOException {
                      throw unreadable;
                    }
                  });
            } catch (IOException e) {
              first.set(e);
            }
            try {
              CpuSteps.call(
                  workers,
                  new CpuSteps.Step<Void>() {
                    @Override
                    public Void run() {
                      throw deep;
                    }
                  });
            } catch (StackOverflowError | IOException e) {
              second.set(e);
            }
            return null;
          }
        });
    assertSame(unreadable, first.get());
    assertSame(deep, second.get());
  }

  @Test
  void aCanceledTokenEndsTheStepsAtTheNextCheck() throws Exception {
    final CancellationToken token = new CancellationToken();
    final AtomicInteger steps = new AtomicInteger();
    Throwable thrown =
        OnWorker.thrown(
            workers,
            new CpuSteps.Step<Void>() {
              @Override
              public Void run() throws IOException {
                CpuSteps.run(
                    workers,
                    new CpuSteps.Steps() {
                      @Override
                      public boolean step() {
                        token.check();
                        if (steps.incrementAndGet() == 3) {
                          token.cancel();
                        }
                        return true;
                      }
                    });
                return null;
              }
            });
    assertInstanceOf(CancellationException.class, thrown);
    assertEquals(3, steps.get());
  }

  @Test
  void aStepAfterCloseIsCanceled() throws Exception {
    final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
    workers.start(
        Workers.Kind.SEARCH,
        new Runnable() {
          @Override
          public void run() {
            try {
              Poll.until(
                  new BooleanSupplier() {
                    @Override
                    public boolean getAsBoolean() {
                      return workers.isClosed();
                    }
                  },
                  BOUND);
              CpuSteps.call(
                  workers,
                  new CpuSteps.Step<Void>() {
                    @Override
                    public Void run() {
                      return null;
                    }
                  });
            } catch (CancellationException | IOException e) {
              thrown.set(e);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        });
    assertTrue(workers.close(BOUND));
    assertInstanceOf(CancellationException.class, thrown.get());
  }

  /**
   * One spinning step per core holds every pool thread; the virtual kinds still run, because each
   * spinning task's virtual thread parks while its step runs.
   */
  @Test
  void spinningStepsLeaveTheVirtualKindsRunnable() throws Exception {
    final int cores = Runtime.getRuntime().availableProcessors();
    final AtomicInteger spinning = new AtomicInteger();
    for (int i = 0; i < cores; i++) {
      workers.start(
          Workers.Kind.SEARCH,
          new Runnable() {
            @Override
            public void run() {
              CpuSteps.compute(
                  workers,
                  new Supplier<Void>() {
                    @Override
                    public Void get() {
                      spinning.incrementAndGet();
                      while (!stop.get()) {
                        Thread.onSpinWait();
                      }
                      return null;
                    }
                  });
            }
          });
    }
    assertTrue(Poll.until(atLeast(spinning, cores), BOUND), "spinning " + spinning.get());
    final CountDownLatch ran = new CountDownLatch(2);
    Runnable count =
        new Runnable() {
          @Override
          public void run() {
            ran.countDown();
          }
        };
    workers.start(Workers.Kind.LIFECYCLE, count);
    workers.start(Workers.Kind.READ, count);
    assertTrue(ran.await(BOUND.toSeconds(), TimeUnit.SECONDS), "a virtual kind did not run");
  }

  /**
   * Long work cut into steps lets a short pool task through while every pool thread is busy: each
   * step queues behind it. As one step, the short task would wait for the long work to end, and the
   * long work ends only when the short task runs.
   */
  @Test
  void longWorkInStepsLeavesThePoolToShortTasks() throws Exception {
    final int cores = Runtime.getRuntime().availableProcessors();
    final AtomicInteger stepping = new AtomicInteger();
    for (int i = 0; i < cores; i++) {
      workers.start(
          Workers.Kind.SEARCH,
          new Runnable() {
            @Override
            public void run() {
              final AtomicBoolean counted = new AtomicBoolean();
              try {
                CpuSteps.run(
                    workers,
                    new CpuSteps.Steps() {
                      @Override
                      public boolean step() {
                        if (counted.compareAndSet(false, true)) {
                          stepping.incrementAndGet();
                        }
                        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1);
                        while (System.nanoTime() < end) {
                          Thread.onSpinWait();
                        }
                        return !stop.get();
                      }
                    });
              } catch (IOException e) {
                failures.add(e);
              } catch (CancellationException e) {
                // Closed between steps by the teardown.
              }
            }
          });
    }
    assertTrue(Poll.until(atLeast(stepping, cores), BOUND), "stepping " + stepping.get());
    workers.executeCpu(
        new Runnable() {
          @Override
          public void run() {
            stop.set(true);
          }
        });
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return stop.get();
              }
            },
            BOUND),
        "the short task did not run");
  }

  private static BooleanSupplier atLeast(final AtomicInteger counter, final int target) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return counter.get() >= target;
      }
    };
  }
}
