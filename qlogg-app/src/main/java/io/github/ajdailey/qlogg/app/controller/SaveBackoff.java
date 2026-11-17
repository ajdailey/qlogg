package io.github.ajdailey.qlogg.app.controller;

/**
 * The delay before {@link WindowSet} retries a save with a failed write: {@link
 * ApplicationController#SAVE_DELAY_MILLIS} after the first, doubling with each further one up to
 * {@link #MAX_MILLIS}. A save without a failed write ends the run. Not synchronized; the caller
 * serializes access.
 */
final class SaveBackoff {
  /** The longest delay. */
  static final int MAX_MILLIS = 60_000;

  private int failures;

  /**
   * Records whether a save had a failed write.
   *
   * @param failed true when a write failed
   * @return the milliseconds before the retry, or 0 when nothing failed
   */
  int record(boolean failed) {
    failures = failed ? failures + 1 : 0;
    if (failures == 0) {
      return 0;
    }
    long delay = (long) ApplicationController.SAVE_DELAY_MILLIS << Math.min(failures - 1, 20);
    return (int) Math.min(delay, MAX_MILLIS);
  }
}
