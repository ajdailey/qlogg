package io.github.ajdailey.qlogg.app.view;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Records calls made off the UI thread, the thread that created the check. A call from another
 * thread is recorded rather than thrown, so the controller's worker path runs on unchanged; the
 * test thread throws the first record from {@link #assertConfined}.
 */
public final class UiThreadCheck {
  private final Thread uiThread = Thread.currentThread();
  private final AtomicReference<AssertionError> first = new AtomicReference<AssertionError>();

  /**
   * Reports whether the caller is on the UI thread.
   *
   * @return true on the thread that created the check
   */
  public boolean isUiThread() {
    return Thread.currentThread() == uiThread;
  }

  /**
   * Records the call when the caller is not on the UI thread.
   *
   * @param call what was called, for the failure message
   */
  public void check(String call) {
    if (!isUiThread()) {
      first.compareAndSet(
          null,
          new AssertionError(
              call + " ran on " + Thread.currentThread().getName() + ", not the UI thread"));
    }
  }

  /**
   * Throws the first call recorded off the UI thread.
   *
   * @throws AssertionError when one was recorded; its stack trace is the offending call's
   */
  public void assertConfined() {
    AssertionError error = first.get();
    if (error != null) {
      throw error;
    }
  }
}
