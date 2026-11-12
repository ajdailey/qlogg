package io.github.ajdailey.qlogg.app.controller;

import java.nio.file.Path;

/** Receives application events on the UI thread. */
public interface ApplicationListener {
  /**
   * A document opened in a new tab after the existing tabs.
   *
   * @param document its controller
   */
  void documentOpened(DocumentController document);

  /**
   * A document closed; its tab is removed.
   *
   * @param document its controller, already detached
   */
  void documentClosed(DocumentController document);

  /**
   * A file did not open; its error is shown. Does nothing by default.
   *
   * @param path the path as requested
   */
  default void documentOpenFailed(Path path) {}

  /**
   * Another document is shown, or none. Does nothing by default.
   *
   * @param document the shown document's controller, or null when no tab is left
   */
  default void activeDocumentChanged(DocumentController document) {}

  /**
   * A reopened document took the tab of the one it replaces. Does nothing by default.
   *
   * @param closed the replaced controller, already detached
   * @param replacement the new controller
   */
  default void documentReplaced(DocumentController closed, DocumentController replacement) {}

  /**
   * A tab's name changed through Rename Tab. Does nothing by default.
   *
   * @param document the tab's controller
   */
  default void tabRenamed(DocumentController document) {}

  // Preferences dialog and zoom.

  /** The key bindings changed; the menus and views take the new keymap. Does nothing by default. */
  default void keymapChanged() {}

  /** The log font changed, from Preferences or a zoom. Does nothing by default. */
  default void fontChanged() {}

  /**
   * An option other than the key bindings and the font changed in Preferences. Does nothing by
   * default.
   */
  default void optionsChanged() {}

  // Tab move between windows.

  /**
   * A tab moved to another window; its tab is removed and its controllers stay attached. Does
   * nothing by default.
   *
   * @param document the tab's controller
   */
  default void documentMovedOut(DocumentController document) {}

  /**
   * A tab moved here from another window. Does nothing by default.
   *
   * @param document the tab's controller
   * @param index its tab position, 0 first
   */
  default void documentMovedIn(DocumentController document, int index) {}

  /** A tab of any window joined or left a time sync group. Does nothing by default. */
  default void timeSyncChanged() {}
}
