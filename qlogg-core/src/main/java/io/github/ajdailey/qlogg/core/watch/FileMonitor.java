package io.github.ajdailey.qlogg.core.watch;

import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Watches files by name. Each {@link #watch} runs a {@code qlogg-follow-} thread that calls its
 * listener every {@code pollMillis} and, with {@code useWatchService}, also when the parent
 * directory reports an event for the file name. The poll stays on while directory events work,
 * because a {@code WatchService} misses events on network mounts and polls on macOS.
 *
 * <p>One {@link WatchService} serves every watched directory; a {@code qlogg-follow-} thread
 * dispatches its events and ends when the last watch ends or the workers close. Registration runs
 * on the watch's own thread, so {@link #watch} does no I/O. A directory that cannot be registered
 * is polled only. Lock order: {@code lock} alone; it is never held during a listener call.
 */
public final class FileMonitor {
  /** Least interval between two listener calls of one watch. */
  public static final long MIN_GAP_MILLIS = 50;

  private static final long DISPATCH_SLICE_MILLIS = 250;

  private final Workers workers;
  private volatile MonitorSettings settings;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<Path, List<Watch>> watches = new HashMap<Path, List<Watch>>();
  private final Map<Path, WatchKey> keys = new HashMap<Path, WatchKey>();
  private WatchService service;

  /**
   * Creates the monitor.
   *
   * @param workers runs the watch and dispatch threads
   * @param settings poll interval and directory events
   */
  public FileMonitor(Workers workers, MonitorSettings settings) {
    this.workers = workers;
    this.settings = settings;
  }

  /**
   * Starts watching a path. Returns at once.
   *
   * @param path the file, absolute
   * @param listener receives the checks on the watch's thread
   * @return the handle; {@code cancel} stops the watch within one check
   * @throws RejectedExecutionException after the workers close
   */
  public OperationHandle watch(Path path, MonitorListener listener) {
    Watch watch = new Watch(path, listener);
    workers.start(Workers.Kind.FOLLOW, watch);
    return watch;
  }

  /**
   * Replaces the settings. The interval applies from each watch's next wait; directory events apply
   * to watches started afterwards.
   *
   * @param next the settings
   */
  public void setSettings(MonitorSettings next) {
    settings = next;
  }

  /**
   * Counts the directories registered with the {@code WatchService}.
   *
   * @return directories
   */
  public int watchedDirectories() {
    lock.lock();
    try {
      return keys.size();
    } finally {
      lock.unlock();
    }
  }

  private void register(Watch watch) {
    Path directory = watch.path.getParent();
    if (!settings.useWatchService() || directory == null) {
      return;
    }
    lock.lock();
    try {
      List<Watch> list = watches.get(directory);
      if (list == null) {
        WatchKey key = registerDirectory(directory);
        if (key == null) {
          return;
        }
        list = new ArrayList<Watch>();
        watches.put(directory, list);
        keys.put(directory, key);
      }
      list.add(watch);
    } finally {
      lock.unlock();
    }
  }

  /** Called with the lock held; starts the service and its dispatcher on first use. */
  private WatchKey registerDirectory(Path directory) {
    try {
      if (service == null) {
        WatchService created = directory.getFileSystem().newWatchService();
        final WatchService started = created;
        try {
          workers.start(
              Workers.Kind.FOLLOW,
              new Runnable() {
                @Override
                public void run() {
                  dispatch(started);
                }
              });
        } catch (RejectedExecutionException e) {
          created.close();
          return null;
        }
        service = created;
      }
      return directory.register(
          service,
          StandardWatchEventKinds.ENTRY_CREATE,
          StandardWatchEventKinds.ENTRY_DELETE,
          StandardWatchEventKinds.ENTRY_MODIFY);
    } catch (IOException | UnsupportedOperationException | ClosedWatchServiceException e) {
      return null;
    }
  }

  private void unregister(Watch watch) {
    Path directory = watch.path.getParent();
    lock.lock();
    try {
      List<Watch> list = watches.get(directory);
      if (list == null || !list.remove(watch) || !list.isEmpty()) {
        return;
      }
      watches.remove(directory);
      WatchKey key = keys.remove(directory);
      if (key != null) {
        key.cancel();
      }
    } finally {
      lock.unlock();
    }
  }

  /** Runs on the dispatcher thread until no directory is registered or the workers close. */
  private void dispatch(WatchService watching) {
    try {
      while (true) {
        WatchKey key = watching.poll(DISPATCH_SLICE_MILLIS, TimeUnit.MILLISECONDS);
        if (key != null) {
          deliver(key);
        }
        if (finished(watching, false)) {
          return;
        }
      }
    } catch (ClosedWatchServiceException e) {
      finished(watching, true);
    } catch (InterruptedException e) {
      finished(watching, true);
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Closes the service when forced, idle or the workers close; true when the dispatcher ends. Later
   * watches start a new service.
   */
  private boolean finished(WatchService watching, boolean force) {
    lock.lock();
    try {
      boolean ending = force || service != watching || keys.isEmpty() || workers.isClosed();
      if (!ending) {
        return false;
      }
      if (service == watching) {
        service = null;
        for (WatchKey key : keys.values()) {
          key.cancel();
        }
        keys.clear();
        watches.clear();
      }
      try {
        watching.close();
      } catch (IOException e) {
        // Closing releases the native handle; nothing else to do.
      }
      return true;
    } finally {
      lock.unlock();
    }
  }

  private void deliver(WatchKey key) {
    List<Watch> targets = new ArrayList<Watch>();
    List<WatchEvent<?>> events = key.pollEvents();
    key.reset();
    lock.lock();
    try {
      Object watchable = key.watchable();
      List<Watch> list = watchable instanceof Path directory ? watches.get(directory) : null;
      if (list == null) {
        return;
      }
      for (WatchEvent<?> event : events) {
        for (Watch watch : list) {
          if (event.kind() == StandardWatchEventKinds.OVERFLOW
              || (watch.name != null && watch.name.equals(event.context()))) {
            targets.add(watch);
          }
        }
      }
    } finally {
      lock.unlock();
    }
    for (Watch watch : targets) {
      watch.wake.release();
    }
  }

  /** One watched path and its thread. */
  private final class Watch implements OperationHandle, Runnable {
    final Path path;
    final Path name;
    final MonitorListener listener;
    final Semaphore wake = new Semaphore(0);
    final CountDownLatch done = new CountDownLatch(1);
    volatile boolean canceled;

    Watch(Path path, MonitorListener listener) {
      this.path = path;
      this.name = path.getFileName();
      this.listener = listener;
    }

    @Override
    public void cancel() {
      canceled = true;
      wake.release();
    }

    @Override
    public boolean isDone() {
      return done.getCount() == 0;
    }

    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
      return done.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void run() {
      try {
        register(this);
        while (!canceled) {
          long started = System.nanoTime();
          listener.possiblyChanged(path);
          long spent = (System.nanoTime() - started) / 1_000_000L;
          if (spent < MIN_GAP_MILLIS && pause(MIN_GAP_MILLIS - spent)) {
            break;
          }
          if (wake.tryAcquire(settings.pollMillis(), TimeUnit.MILLISECONDS)) {
            wake.drainPermits();
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        unregister(this);
        done.countDown();
      }
    }

    /** Sleeps unless canceled meanwhile; true when canceled. */
    private boolean pause(long millis) throws InterruptedException {
      long deadline = System.nanoTime() + millis * 1_000_000L;
      while (!canceled) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          return false;
        }
        if (wake.tryAcquire(left, TimeUnit.NANOSECONDS)) {
          wake.release();
          return canceled;
        }
      }
      return true;
    }
  }
}
