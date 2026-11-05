package io.github.ajdailey.qlogg.core.time;

/** Receives the position of a sequential scan; runs on the scanning thread. */
public interface ScanProgress {
  /**
   * Reports the scan position.
   *
   * @param position the byte offset reached
   * @param end the scan's end
   */
  void scanned(long position, long end);
}
