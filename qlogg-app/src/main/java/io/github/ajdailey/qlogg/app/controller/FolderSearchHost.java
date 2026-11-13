package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import java.nio.file.Path;
import java.util.List;

/** What {@link FolderSearchController} needs from its window. Called on the UI thread. */
interface FolderSearchHost {
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
   * Returns this window's open documents.
   *
   * @return in tab order
   */
  List<DocumentController> documents();

  /**
   * Shows a tab.
   *
   * @param document an open document's controller
   */
  void activate(DocumentController document);

  /**
   * Opens a file on a worker in a new tab, or shows its tab when it is open already.
   *
   * @param path the path as given
   * @param line the 1-based line to go to once indexed, or 0
   */
  void openFile(Path path, long line);

  /**
   * Adds a listener for this window's tabs.
   *
   * @param listener called on the UI thread
   */
  void addListener(ApplicationListener listener);

  /**
   * Returns the document services the windows share, read at each run: a change of the default
   * encoding replaces them.
   *
   * @return the current services
   */
  DocumentServices services();
}
