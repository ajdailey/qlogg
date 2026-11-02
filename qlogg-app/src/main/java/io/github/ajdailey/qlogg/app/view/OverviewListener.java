package io.github.ajdailey.qlogg.app.view;

/** Receives new overview bar bins. */
public interface OverviewListener {
  /** The bins changed; the bar repaints from them. */
  void binsChanged();
}
