package io.github.ajdailey.qlogg.app.cli;

/** Session restore requested on the command line. */
public enum SessionMode {
  /**
   * Neither {@code -s} nor {@code -n}: restore when no file is given and the preference says so.
   */
  DEFAULT,
  /** {@code -s}: restore the previous session. */
  LOAD,
  /** {@code -n}: do not restore. */
  NEW
}
