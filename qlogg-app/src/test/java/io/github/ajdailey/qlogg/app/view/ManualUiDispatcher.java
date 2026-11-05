package io.github.ajdailey.qlogg.app.view;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * A {@link UiDispatcher} for tests: submissions queue until the test runs them on its own thread,
 * which acts as the UI thread. Timer delays are ignored; timer tasks queue in submission order
 * unless {@link #holdTimers} keeps those of one delay for {@link #runHeldTimers}. Draining from
 * another thread throws; a listener call recorded off the UI thread by {@link #checkUiThread} fails
 * the next drain.
 */
public final class ManualUiDispatcher implements UiDispatcher {
  private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();
  private final UiThreadCheck uiThread = new UiThreadCheck();
  private final List<Runnable> heldTimers = new ArrayList<Runnable>();
  private int heldDelay = -1;
  private volatile boolean disposed;

  @Override
  public void asyncExec(Runnable task) {
    if (!disposed && !queue.offer(task)) {
      throw new IllegalStateException("queue refused a task");
    }
  }

  @Override
  public void timerExec(int delayMillis, Runnable task) {
    if (delayMillis == heldDelay) {
      heldTimers.add(task);
    } else {
      asyncExec(task);
    }
  }

  /**
   * Keeps later timer tasks of one delay until {@link #runHeldTimers}, as timers not yet expired.
   *
   * @param delayMillis the delay to hold
   */
  public void holdTimers(int delayMillis) {
    heldDelay = delayMillis;
  }

  /**
   * Counts the timer tasks held.
   *
   * @return the count
   */
  public int heldTimers() {
    return heldTimers.size();
  }

  /** Queues the held timer tasks, as if their delays had passed. */
  public void runHeldTimers() {
    for (Runnable task : heldTimers) {
      asyncExec(task);
    }
    heldTimers.clear();
  }

  @Override
  public boolean isUiThread() {
    return uiThread.isUiThread();
  }

  /**
   * Records the call when the caller is not on the UI thread; the next drain throws it.
   *
   * @param call what was called, for the failure message
   */
  public void checkUiThread(String call) {
    uiThread.check(call);
  }

  /**
   * Wraps a listener so each call off the UI thread is recorded before it is passed on.
   *
   * @param <T> the listener interface
   * @param type the listener interface
   * @param listener the listener
   * @return a proxy, equal only to itself, to register in place of the listener
   */
  public <T> T confined(Class<T> type, final T listener) {
    InvocationHandler handler =
        new InvocationHandler() {
          @Override
          public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
              return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> "confined " + listener;
              };
            }
            checkUiThread(type.getSimpleName() + "." + method.getName());
            try {
              return method.invoke(listener, args);
            } catch (InvocationTargetException e) {
              throw e.getCause();
            }
          }
        };
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }

  /** Throws unless on the UI thread with no call recorded off it. */
  private void checkDrain() {
    if (!uiThread.isUiThread()) {
      throw new IllegalStateException("drained off the UI thread");
    }
    uiThread.assertConfined();
  }

  /** Drops queued and later submissions, as a disposed Display does. */
  public void dispose() {
    disposed = true;
    queue.clear();
  }

  /**
   * Runs the tasks queued now, and any they queue, until the queue is empty.
   *
   * @return the number run
   */
  public int runPending() {
    checkDrain();
    int count = 0;
    Runnable task = queue.poll();
    while (task != null) {
      task.run();
      count++;
      task = queue.poll();
    }
    checkDrain();
    return count;
  }

  /**
   * Runs tasks as they arrive until the condition holds.
   *
   * @param condition checked before each wait
   * @param bound the longest total wait
   * @return true when the condition held within the bound
   * @throws InterruptedException when interrupted while waiting
   */
  public boolean runUntil(BooleanSupplier condition, Duration bound) throws InterruptedException {
    checkDrain();
    long deadline = System.nanoTime() + bound.toNanos();
    while (!condition.getAsBoolean()) {
      checkDrain();
      long left = deadline - System.nanoTime();
      if (left <= 0) {
        return false;
      }
      Runnable task = queue.poll(left, TimeUnit.NANOSECONDS);
      if (task != null) {
        task.run();
      }
    }
    return true;
  }
}
