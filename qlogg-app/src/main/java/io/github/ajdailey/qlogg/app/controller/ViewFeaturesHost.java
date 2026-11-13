package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.theme.ThemeController;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import java.util.List;

/** What {@link ViewFeatures} needs from its window. Called on the UI thread. */
interface ViewFeaturesHost {
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
   * Returns the open documents of every window of the set.
   *
   * @return window by window, each in tab order
   */
  List<DocumentController> allDocuments();

  /**
   * Returns the view commands go to.
   *
   * @return the focused view of the shown document, or null with no document
   */
  LineViewController focusedView();

  /**
   * Returns the theme, which pushes palette and color changes to the views.
   *
   * @return the controller
   */
  ThemeController theme();

  /**
   * Returns the services documents open with.
   *
   * @return the current services
   */
  DocumentServices services();
}
