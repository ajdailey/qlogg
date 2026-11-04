package io.github.ajdailey.qlogg.app.controller.archfixture;

import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import java.io.IOException;
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
}
