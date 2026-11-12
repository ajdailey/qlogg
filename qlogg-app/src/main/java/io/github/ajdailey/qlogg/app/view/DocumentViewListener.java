package io.github.ajdailey.qlogg.app.view;

/** Receives document updates on the UI thread. */
public interface DocumentViewListener {
  /**
   * The status changed.
   *
   * @param state the new state
   */
  void stateChanged(DocumentViewState state);

  /** Lines, viewport or selection changed; the view repaints from the controller's snapshot. */
  void contentChanged();

  /** The caret time, the selection's time difference, the time offset or the format changed. */
  default void timeChanged() {}

  /** Go Back or Go Forward changed whether it has an entry. */
  default void navigationChanged() {}
}
