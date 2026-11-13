package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.config.Preferences;
import java.util.List;

/** What {@link RecentFiles} needs from its window. Called on the UI thread. */
interface RecentFilesHost {
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
   * Reports whether shutdown has begun.
   *
   * @return true once the window stops accepting operations
   */
  boolean isShuttingDown();

  /**
   * Opens files on a worker, a tab each in the order given.
   *
   * @param files the files
   */
  void open(List<FileRequest> files);

  /**
   * Opens a listed path on a worker; a path that does not parse shows an error.
   *
   * @param text the path
   */
  void openListed(String text);
}
