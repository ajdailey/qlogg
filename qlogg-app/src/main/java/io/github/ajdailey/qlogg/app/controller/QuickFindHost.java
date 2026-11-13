package io.github.ajdailey.qlogg.app.controller;

/** What {@link QuickFindController} needs from its window. Called on the UI thread. */
interface QuickFindHost {
  /**
   * Returns the view commands go to.
   *
   * @return the focused view of the shown document, or null with no document
   */
  LineViewController focusedView();

  /** Quick find opened itself for a selection; the window focuses the bar. */
  void quickFindOpened();
}
