package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.concurrent.OperationGate;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link DocumentContent}'s {@link OperationGate}, the handles of its indexing pass and its
 * watch, and the indexing state both contents share: one pass at a time, growth seen while a pass
 * runs indexed when it ends, nothing indexed after {@link #stopIndexing}. A pass holds one gate
 * entry and the running flag from its start until its end calls {@link #passEnded}. Thread-safe.
 */
final class ContentOperations {
  private static final long CANCEL_SLICE_MILLIS = 50;

  /** Starts an indexing pass whose end calls {@link #passEnded}. */
  interface Pass {
    /**
     * Starts the pass.
     *
     * @throws RejectedExecutionException after the workers close
     * @throws IllegalStateException when the index storage is refused
     */
    void start();
  }

  private final OperationGate gate = new OperationGate();
  private final AtomicBoolean indexStarted = new AtomicBoolean();
  private final AtomicBoolean indexRunning = new AtomicBoolean();
  private final AtomicBoolean extendWanted = new AtomicBoolean();
  private final AtomicBoolean reopenWaiting = new AtomicBoolean();
  private volatile boolean loadingStopped;
  private volatile OperationHandle indexing;
  private volatile OperationHandle watching;

  boolean enter() {
    return gate.enter();
  }

  void exit() {
    gate.exit();
  }

  boolean isClosed() {
    return gate.isClosed();
  }

  /** Records the running indexing pass, which {@link #stopIndexing} and the close cancel. */
  void indexing(OperationHandle handle) {
    indexing = handle;
  }

  /** Records the watch; it is canceled at once when the gate closed meanwhile. */
  void watching(OperationHandle handle) {
    watching = handle;
    if (gate.isClosed()) {
      cancel(handle);
    }
  }

  /**
   * Starts the first pass.
   *
   * @param pass starts it
   * @throws IllegalStateException when indexing was started before or the gate is closed
   * @throws RejectedExecutionException after the workers close
   */
  void startIndexing(Pass pass) {
    if (!indexStarted.compareAndSet(false, true)) {
      throw new IllegalStateException("Indexing already started or document closed");
    }
    if (!gate.enter()) {
      throw new IllegalStateException("Document closed");
    }
    indexRunning.set(true);
    boolean started = false;
    try {
      pass.start();
      started = true;
    } finally {
      if (!started) {
        passEnded();
      }
    }
  }

  /** Cancels the running pass and leaves later growth unindexed; true when a pass was running. */
  boolean stopIndexing() {
    loadingStopped = true;
    boolean running = indexRunning.get();
    cancel(indexing);
    return running;
  }

  boolean loadingStopped() {
    return loadingStopped;
  }

  boolean indexRunning() {
    return indexRunning.get();
  }

  /** Clears the running flag and exits the pass's gate entry. */
  void passEnded() {
    indexRunning.set(false);
    gate.exit();
  }

  /**
   * Takes the running flag for work that must not overlap a pass, without a gate entry.
   *
   * @return false while a pass runs
   */
  boolean holdIndexing() {
    return indexRunning.compareAndSet(false, true);
  }

  /** Releases {@link #holdIndexing}. */
  void releaseIndexing() {
    indexRunning.set(false);
  }

  /**
   * Indexes growth now, or when the running pass ends and calls {@link #tryExtend}.
   *
   * @param pass starts a pass
   * @param stale true while the owner reopens the content
   */
  void extend(Pass pass, boolean stale) {
    extendWanted.set(true);
    tryExtend(pass, stale);
  }

  /**
   * Starts a pass when growth waits and none runs. A pass that cannot start stops loading.
   *
   * @param pass starts a pass
   * @param stale true while the owner reopens the content
   */
  void tryExtend(Pass pass, boolean stale) {
    if (loadingStopped
        || stale
        || !extendWanted.get()
        || !indexRunning.compareAndSet(false, true)) {
      return;
    }
    if (!gate.enter()) {
      indexRunning.set(false);
      return;
    }
    extendWanted.set(false);
    boolean started = false;
    try {
      pass.start();
      started = true;
    } catch (RejectedExecutionException | IllegalStateException e) {
      // The workers are closing, or the index storage was refused: growth stays unindexed.
      loadingStopped = true;
    } finally {
      if (!started) {
        passEnded();
      }
    }
  }

  /** Records that a pass could not continue: later growth stays unindexed. */
  void stopLoading() {
    loadingStopped = true;
  }

  /** Records a failed reopen; true when it starts a run of failures. */
  boolean reopenFailed() {
    return !reopenWaiting.getAndSet(true);
  }

  boolean reopenWaiting() {
    return reopenWaiting.get();
  }

  /** Ends a run of failed reopens; true when one was waiting. */
  boolean clearReopenWaiting() {
    return reopenWaiting.getAndSet(false);
  }

  /**
   * Closes the gate and waits up to the bound for every entered operation to exit and the watch to
   * end, canceling indexing and the watch each slice, so an operation started meanwhile stops too.
   *
   * @param bound the longest wait
   * @return false when an operation outlived the bound or the wait was interrupted
   */
  boolean close(Duration bound) {
    gate.close();
    long deadline = System.nanoTime() + bound.toNanos();
    try {
      while (true) {
        cancel(indexing);
        OperationHandle watch = watching;
        cancel(watch);
        long left = (deadline - System.nanoTime()) / 1_000_000L;
        long slice = Math.max(0, Math.min(left, CANCEL_SLICE_MILLIS));
        if (gate.awaitIdle(slice) && (watch == null || watch.await(slice))) {
          return true;
        }
        if (left <= 0) {
          return false;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static void cancel(OperationHandle handle) {
    if (handle != null) {
      handle.cancel();
    }
  }
}
