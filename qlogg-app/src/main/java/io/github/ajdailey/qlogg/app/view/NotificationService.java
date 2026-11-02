package io.github.ajdailey.qlogg.app.view;

/** User-visible notices. Called on the UI thread. */
public interface NotificationService {
  /**
   * Shows an error without waiting for the user to dismiss it.
   *
   * @param title the window title
   * @param message one line; never a stack trace
   */
  void showError(String title, String message);

  /**
   * Shows a short notice in the status bar until the next notice or a few seconds pass.
   *
   * @param message one line, such as {@code Reached end of file, no occurrence found}
   */
  void showNotice(String message);
}
