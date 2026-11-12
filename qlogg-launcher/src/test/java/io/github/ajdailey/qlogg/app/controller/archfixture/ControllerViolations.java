package io.github.ajdailey.qlogg.app.controller.archfixture;

import io.github.ajdailey.qlogg.app.controller.WorkerThread;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Timer;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Controller classes that each break one architecture rule; never instantiated. */
final class ControllerViolations {
  private ControllerViolations() {}

  /** Returns a read-only set, which is allowed, and a mutable one. */
  static final class ReturnsMutableSet {
    private final MutableLineSet lines = new MutableLineSet();

    public LineSet lines() {
      return lines;
    }

    public MutableLineSet mutable() {
      return lines;
    }
  }

  /** Reads the platform from a system property. */
  static final class ReadsPlatform {
    boolean windows() {
      return System.getProperty("os.name", "").startsWith("Windows");
    }
  }

  /** Starts a timer thread. */
  static final class StartsTimer {
    Timer timer() {
      return new Timer(true);
    }
  }

  /** Makes threads outside the thread owner. */
  static final class MakesThreads implements ThreadFactory {
    @Override
    public Thread newThread(Runnable task) {
      return null;
    }
  }

  /** Runs tasks outside the thread owner. */
  abstract static class RunsTasks extends AbstractExecutorService {
    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return true;
    }

    @Override
    public boolean isTerminated() {
      return true;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }

  // Document opening.

  /** Opens documents outside the opener. */
  static final class OpensDocuments {
    LogDocument file(Path path, DocumentServices services) throws IOException {
      return LogDocument.open(path, null, services);
    }

    LogDocument text(DocumentServices services) throws IOException {
      return LogDocument.ofText("mock", "text", 16, services);
    }
  }

  // Blocking calls in controllers.

  /**
   * Blocks on the UI thread and calls a worker method from it; {@code size}, {@code
   * workerCallsWorker} and the task in {@code submit} are allowed.
   */
  static final class BlocksOnUiThread {
    private Workers workers;

    boolean exists(Path path) {
      return Files.exists(path);
    }

    void close(LogDocument document) {
      document.close();
    }

    long uiCallsWorker(Path path) throws IOException {
      return size(path);
    }

    @WorkerThread
    long size(Path path) throws IOException {
      return Files.size(path);
    }

    @WorkerThread
    long workerCallsWorker(Path path) throws IOException {
      return size(path);
    }

    void submit(final Path path) {
      workers.tryStart(
          Workers.Kind.INDEX,
          new Runnable() {
            @Override
            public void run() {
              Files.isDirectory(path);
            }
          });
    }

    /** Creates two tasks, so neither counts as the submitted one. */
    void submitOneOfTwo(final Path path) {
      Runnable later =
          new Runnable() {
            @Override
            public void run() {
              Files.isSymbolicLink(path);
            }
          };
      workers.tryStart(
          Workers.Kind.INDEX,
          new Runnable() {
            @Override
            public void run() {
              Files.isReadable(path);
            }
          });
      later.run();
    }
  }

  // Operating system checks.

  /** Asks the operating system seam for this process's system instead of taking it in. */
  static final class AsksTheOs {
    boolean mac() {
      return OsTraits.current().macKeymap();
    }
  }
}
