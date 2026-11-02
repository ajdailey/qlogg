package io.github.ajdailey.qlogg.app.cli;

/** An unknown option, a missing or bad value, or a misplaced {@code +line:N}. */
public final class CommandLineException extends Exception {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message one line naming the argument and the problem
   */
  public CommandLineException(String message) {
    super(message);
  }
}
