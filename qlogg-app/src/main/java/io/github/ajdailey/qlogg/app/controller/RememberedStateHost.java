package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.config.Preferences;

/** What {@link RememberedState} needs from the window set. Called on the UI thread. */
interface RememberedStateHost {
  /**
   * Returns the current settings.
   *
   * @return the settings
   */
  Preferences settings();

  /**
   * Replaces the settings and schedules their save.
   *
   * @param changed the new settings
   */
  void setPreferences(Preferences changed);

  /**
   * Replaces the settings without scheduling a save; quit and window close save them.
   *
   * @param changed the new settings
   */
  void preferencesChanged(Preferences changed);

  /** Saves {@link ApplicationController#SAVE_DELAY_MILLIS} after the last call. */
  void scheduleSave();
}
