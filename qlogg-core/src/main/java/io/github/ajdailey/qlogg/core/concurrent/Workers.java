package io.github.ajdailey.qlogg.core.concurrent;

import io.github.ajdailey.qlogg.core.util.Checks;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Owns every thread QLogg starts: a fixed pool of platform threads for CPU-bound work ({@code
 * qlogg-cpu-N}) and virtual threads for blocking work, named by {@link Kind}. Nothing else creates
 * a thread or an executor. The launcher creates one instance and closes it at shutdown.
 *
 * <p>Every task runs inside a wrapper that passes an exception it ends with to the failure handler;
 * the same handler is each thread's uncaught exception handler. No task returns a {@code Future},
 * so no failure is lost in a discarded one.
 *
 * <p>Pool threads are daemon threads: a missed {@link #close} cannot keep the JVM alive. Tests
 * detect a leak through {@link #liveThreads}.
 */
public final class Workers implements AutoCloseable {
  /** Default bound for {@link #close()}. */
  public static final Duration CLOSE_BOUND = Duration.ofSeconds(5);

  private static final int CPU_QUEUE = 4096;

  /** Virtual thread kinds, each with its own name prefix. */
  public enum Kind {
    /** File open and indexing coordinators. */
    INDEX("qlogg-index-"),
    /** Line reads for painting and copy. */
    READ("qlogg-read-"),
    /** Search coordinators. */
    SEARCH("qlogg-search-"),
    /** File watching and follow polling. */
    FOLLOW("qlogg-follow-"),
    /** Single-instance listener and connections. */
    IPC("qlogg-ipc-"),
    /** Configuration saves and shutdown. */
    LIFECYCLE("qlogg-lifecycle-"),
    /** The event-loop watchdog. */
    WATCHDOG("qlogg-watchdog-");

    private final String prefix;

    Kind(String prefix) {
      this.prefix = prefix;
    }

    /**
     * Returns the thread name prefix; a counter follows it.
     *
     * @return for example {@code qlogg-index-}
     */
    public String prefix() {
      return prefix;
    }
  }

  private final Thread.UncaughtExceptionHandler failures;
  private final ThreadPoolExecutor cpu;
  private final ThreadFactory[] factories;
  private final Set<Thread> live = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean closed = new AtomicBoolean();

  /** Held shared by {@link #start}, exclusive while {@link #close} sets {@code closed}. */
  private final ReadWriteLock gate = new ReentrantReadWriteLock();

  /**
   * Creates the pools.
   *
   * @param cpuThreads CPU pool size, at least 1
   * @param failures receives every exception a task ends with
   */
  public Workers(int cpuThreads, Thread.UncaughtExceptionHandler failures) {
    Checks.atLeast(cpuThreads, 1, "cpuThreads");
    this.failures = failures;
    ThreadFactory cpuFactory =
        Thread.ofPlatform()
            .name("qlogg-cpu-", 1)
            .daemon(true)
            .uncaughtExceptionHandler(failures)
            .factory();
    this.cpu =
        new ThreadPoolExecutor(
            cpuThreads,
            cpuThreads,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(CPU_QUEUE),
            cpuFactory);
    Kind[] kinds = Kind.values();
    this.factories = new ThreadFactory[kinds.length];
    for (Kind kind : kinds) {
      factories[kind.ordinal()] =
          Thread.ofVirtual().name(kind.prefix(), 1).uncaughtExceptionHandler(failures).factory();
    }
  }

  /**
   * Runs a CPU-bound task on the pool.
   *
   * @param task the task
   * @throws RejectedExecutionException after {@link #close} or when the queue is full
   */
  public void executeCpu(Runnable task) {
    if (closed.get()) {
      throw new RejectedExecutionException("Workers are closed");
    }
    cpu.execute(guard(task));
  }

  /**
   * Starts a blocking task on a new virtual thread. A start that {@link #close} rejects never
   * touches the live set; one it admits has started before {@code close} reads the set.
   *
   * @param kind names the thread
   * @param task the task
   * @return the started thread
   * @throws RejectedExecutionException after {@link #close}
   */
  public Thread start(Kind kind, Runnable task) {
    final Runnable guarded = guard(task);
    final Thread[] self = new Thread[1];
    gate.readLock().lock();
    try {
      if (closed.get()) {
        throw new RejectedExecutionException("Workers are closed");
      }
      Thread thread =
          factories[kind.ordinal()].newThread(
              new Runnable() {
                @Override
                public void run() {
                  try {
                    guarded.run();
                  } finally {
                    live.remove(self[0]);
                  }
                }
              });
      self[0] = thread;
      live.add(thread);
      boolean started = false;
      try {
        thread.start();
        started = true;
      } finally {
        if (!started) {
          live.remove(thread);
        }
      }
      return thread;
    } finally {
      gate.readLock().unlock();
    }
  }

  /**
   * Starts a blocking task as {@link #start} does, unless the workers are closed.
   *
   * @param kind names the thread
   * @param task the task
   * @return false, starting nothing, after {@link #close}
   */
  public boolean tryStart(Kind kind, Runnable task) {
    try {
      start(kind, task);
      return true;
    } catch (RejectedExecutionException e) {
      return false;
    }
  }

  /**
   * Returns the CPU pool size.
   *
   * @return the size given to the constructor
   */
  public int cpuThreads() {
    return cpu.getCorePoolSize();
  }

  /**
   * Reports whether {@link #close} has begun.
   *
   * @return true once closing
   */
  public boolean isClosed() {
    return closed.get();
  }

  /**
   * Counts threads that have not ended: started virtual threads plus pool threads. Counts by
   * iterating the live set, as {@link #close} reads it: {@code ConcurrentHashMap.size} lags a
   * removal that iteration already shows.
   *
   * @return the count; 0 after {@link #close} returns true
   */
  public int liveThreads() {
    int count = 0;
    Iterator<Thread> threads = live.iterator();
    while (threads.hasNext()) {
      threads.next();
      count++;
    }
    return count + cpu.getPoolSize();
  }

  /**
   * Names the threads that have not ended, for a log line.
   *
   * @return the names, comma-separated, pool threads as {@code qlogg-cpu-*} with their count
   */
  public String liveThreadNames() {
    List<String> names = new ArrayList<String>();
    for (Thread thread : live) {
      names.add(thread.getName());
    }
    Collections.sort(names);
    int pool = cpu.getPoolSize();
    if (pool > 0) {
      names.add("qlogg-cpu-* x" + pool);
    }
    return String.join(", ", names);
  }

  /**
   * Creates the JVM shutdown hook's thread, {@code qlogg-shutdown}, not started; the caller
   * registers it with {@code Runtime.addShutdownHook}. It does not join the live set, so {@link
   * #close} neither waits for nor interrupts it.
   *
   * @param task what the hook runs
   * @return the unstarted platform thread
   */
  public Thread shutdownHook(Runnable task) {
    return Thread.ofPlatform()
        .name("qlogg-shutdown")
        .uncaughtExceptionHandler(failures)
        .unstarted(guard(task));
  }

  /** Closes with {@link #CLOSE_BOUND}. */
  @Override
  public void close() {
    close(CLOSE_BOUND);
  }

  /**
   * Stops accepting tasks, waits up to {@code bound} for running ones, then interrupts what is left
   * ({@code shutdownNow} for the pool). A virtual thread calling this method does not wait for
   * itself. Once {@code closed} is set the live set only shrinks, so one read of it names every
   * thread to wait for.
   *
   * @param bound the longest wait
   * @return true when every thread except the caller ended within the bound
   */
  public boolean close(Duration bound) {
    gate.writeLock().lock();
    try {
      closed.set(true);
    } finally {
      gate.writeLock().unlock();
    }
    cpu.shutdown();
    long deadline = System.nanoTime() + bound.toNanos();
    try {
      if (!cpu.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)) {
        cpu.shutdownNow();
      }
      for (Thread thread : others(Thread.currentThread())) {
        if (!thread.join(Duration.ofNanos(remaining(deadline)))) {
          thread.interrupt();
        }
      }
    } catch (InterruptedException e) {
      cpu.shutdownNow();
      Thread.currentThread().interrupt();
      return false;
    }
    return cpu.isTerminated() && others(Thread.currentThread()).isEmpty();
  }

  /** The live threads except {@code current}. */
  private List<Thread> others(Thread current) {
    List<Thread> threads = new ArrayList<Thread>(live);
    threads.remove(current);
    return threads;
  }

  private static long remaining(long deadline) {
    return Math.max(0, deadline - System.nanoTime());
  }

  private Runnable guard(final Runnable task) {
    return new Runnable() {
      @Override
      public void run() {
        try {
          task.run();
        } catch (Exception e) {
          failures.uncaughtException(Thread.currentThread(), e);
        }
      }
    };
  }
}
