package io.github.ajdailey.qlogg.core.watch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMonitorTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path dir;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Workers workers;

  @BeforeEach
  void createWorkers() {
    workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
  }

  @AfterEach
  void closeWorkers() {
    assertTrue(workers.close(Workers.CLOSE_BOUND));
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private static MonitorListener counting(final AtomicInteger calls) {
    return new MonitorListener() {
      @Override
      public void possiblyChanged(Path path) {
        calls.incrementAndGet();
      }
    };
  }

  private static BooleanSupplier atLeast(final AtomicInteger calls, final int count) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return calls.get() >= count;
      }
    };
  }

  @Test
  void pollsUntilCanceled() throws Exception {
    Path file = Files.writeString(dir.resolve("poll.log"), "x\n");
    FileMonitor monitor = new FileMonitor(workers, new MonitorSettings(20, false));
    AtomicInteger calls = new AtomicInteger();
    OperationHandle handle = monitor.watch(file, counting(calls));
    assertTrue(Poll.until(atLeast(calls, 3), BOUND));
    handle.cancel();
    assertTrue(handle.await(BOUND.toMillis()));
    assertTrue(handle.isDone());
    assertEquals(0, monitor.watchedDirectories());
  }

  /** A listener that throws is reported once per run of failures and keeps being called. */
  @Test
  void aThrowingListenerKeepsTheWatchRunning() throws Exception {
    Path file = Files.writeString(dir.resolve("throws.log"), "x\n");
    FileMonitor monitor = new FileMonitor(workers, new MonitorSettings(20, false));
    final AtomicInteger calls = new AtomicInteger();
    OperationHandle handle =
        monitor.watch(
            file,
            new MonitorListener() {
              @Override
              public void possiblyChanged(Path path) {
                int call = calls.incrementAndGet();
                if (call <= 2 || call == 4) {
                  throw new SecurityException("check " + call);
                }
              }
            });
    assertTrue(Poll.until(atLeast(calls, 6), BOUND));
    handle.cancel();
    assertTrue(handle.await(BOUND.toMillis()));
    assertEquals("[check 1, check 4]", messages(failures));
    failures.clear();
  }

  private static String messages(List<Throwable> errors) {
    List<String> messages = new ArrayList<String>();
    for (Throwable error : errors) {
      messages.add(error.getMessage());
    }
    return messages.toString();
  }

  /** A directory event wakes the watch long before its poll interval. */
  @Test
  void aDirectoryEventWakesTheWatch() throws Exception {
    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    Assumptions.assumeFalse(os.contains("mac"), "the macOS WatchService polls");
    Path file = Files.writeString(dir.resolve("event.log"), "x\n");
    FileMonitor monitor = new FileMonitor(workers, new MonitorSettings(600_000, true));
    final AtomicInteger calls = new AtomicInteger();
    OperationHandle handle = monitor.watch(file, counting(calls));
    assertTrue(Poll.until(atLeast(calls, 1), BOUND));
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return monitor.watchedDirectories() == 1;
              }
            },
            BOUND));
    Files.writeString(file, "y\n", StandardOpenOption.APPEND);
    assertTrue(Poll.until(atLeast(calls, 2), BOUND));
    handle.cancel();
    assertTrue(handle.await(BOUND.toMillis()));
    assertEquals(0, monitor.watchedDirectories());
  }
}
