package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.config.Preferences;
import java.util.List;

/** What {@link TimeFeatures} needs from its window. Called on the UI thread. */
interface TimeFeaturesHost {
  /**
   * Returns the current settings.
   *
   * @return the settings
   */
  Preferences preferences();

  /**
   * Replaces the settings and schedules their save.
   *
   * @param changed the new settings
   */
  void setPreferences(Preferences changed);

  /**
   * Returns the shown document.
   *
   * @return its controller, or null
   */
  DocumentController activeDocument();

  /**
   * Returns the open documents of every window of the set.
   *
   * @return window by window, each in tab order
   */
  List<DocumentController> allDocuments();

  /**
   * Reports whether shutdown has begun.
   *
   * @return true once the window stops accepting operations
   */
  boolean isShuttingDown();
}
