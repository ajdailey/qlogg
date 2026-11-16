package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.config.WindowState;

/**
 * Which windows write their placement and split to {@code qlogg.conf} on close and quit. Every
 * window does unless {@link #rememberNone} ran ({@code -m}); a window placed from the command line
 * ({@link #keep}) never does, and the session keeps the bounds it started from. Confined to the UI
 * thread.
 */
final class PlacementMemory {
  private boolean remembers = true;
  private ApplicationController placed;
  private WindowState placedBounds;

  /** Stops every window from writing its placement. */
  void rememberNone() {
    remembers = false;
  }

  /**
   * Stops a window placed from the command line from writing its placement.
   *
   * @param window the window
   * @param saved the bounds the session saves for it
   */
  void keep(ApplicationController window, WindowState saved) {
    placed = window;
    placedBounds = saved;
  }

  /** Reports whether a window writes its placement on close and quit. */
  boolean remembers(ApplicationController window) {
    return remembers && window != placed;
  }

  /** Returns a window's session draft, with the saved bounds for the placed window. */
  WindowSet.WindowDraft draft(ApplicationController window) {
    WindowSet.WindowDraft draft = window.draft();
    if (window != placed) {
      return draft;
    }
    return new WindowSet.WindowDraft(draft.tabs(), draft.active(), draft.onTop(), placedBounds);
  }
}
