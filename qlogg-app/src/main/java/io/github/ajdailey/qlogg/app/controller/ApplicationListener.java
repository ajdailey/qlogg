package io.github.ajdailey.qlogg.app.controller;

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

  // Options dialog and zoom.

  /** The font or the key bindings changed; the views and menus rebuild. Does nothing by default. */
  default void optionsChanged() {}
}
