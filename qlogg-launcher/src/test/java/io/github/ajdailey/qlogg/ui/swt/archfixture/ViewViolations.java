package io.github.ajdailey.qlogg.ui.swt.archfixture;

import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.Highlighter;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.Viewport;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.watch.MonitorSettings;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/** View classes that each break one architecture rule; never instantiated. */
final class ViewViolations {
  private ViewViolations() {}

  /** Uses a {@code core.search} enum, which is allowed, and a {@code core.watch} record. */
  static final class UsesWatchRecord {
    MatchMode mode() {
      return MatchMode.REGEX;
    }

    int poll() {
      return MonitorSettings.DEFAULTS.pollMillis();
    }
  }

  /** Reaches the document model. */
  static final class ReachesDocument {
    boolean inMemory(LogDocument document) {
      return document.inMemory();
    }
  }

  /** Reads the viewport, which is allowed, and moves it. */
  static final class MovesViewport {
    long scroll(Viewport viewport) {
      viewport.scrollBy(1);
      return viewport.topLine();
    }
  }

  /** Blocks in every banned way. */
  static final class Blocks {
    void block(
        Object monitor,
        Lock lock,
        Condition condition,
        Semaphore semaphore,
        BlockingQueue<String> queue,
        CountDownLatch latch,
        OperationHandle handle)
        throws InterruptedException {
      Thread.sleep(1);
      monitor.wait();
      lock.lock();
      lock.lockInterruptibly();
      condition.await();
      semaphore.acquire();
      queue.put("x");
      queue.take();
      latch.await();
      handle.await(1);
    }
  }

  // Highlight rule composition and validation.

  /** Composes a highlighter and compiles a rule. */
  static final class CompilesHighlightRule {
    boolean compiles(HighlightRule rule) {
      return Highlighter.of(List.of(rule)) != null && Highlighter.compile(rule) != null;
    }
  }
}
