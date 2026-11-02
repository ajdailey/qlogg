package io.github.ajdailey.qlogg.app.view;

/** Receives quick find updates on the UI thread. */
public interface QuickFindListener {
  /**
   * Called when the bar's state changes.
   *
   * @param state the latest state
   */
  void stateChanged(QuickFindState state);
}
