package io.github.ajdailey.qlogg.app.view;

/** Receives new overview bar bins and time spread. */
public interface OverviewListener {
  /** The bins or the time spread changed; the bar repaints from them. */
  void binsChanged();
}
