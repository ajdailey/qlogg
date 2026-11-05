package io.github.ajdailey.qlogg.app.controller;

/** Receives keyword trigger scan results on the UI thread. */
public interface TriggerListener {
  /**
   * Appended lines were scanned and their marks applied.
   *
   * @param from the first 0-based line scanned
   * @param to the line after the last
   * @param loudLines lines no quiet rule matched
   */
  void linesScanned(long from, long to, long loudLines);
}
