package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.view.MainView;

/**
 * A window's View > Always on Top state, saved per window in the session. Where the view reports
 * the platform unsupported, the item is disabled and unchecked, and a restored flag is kept for the
 * next save without reaching the view. Confined to the UI thread.
 */
public final class AlwaysOnTop {
  private final MainView view;
  private final Runnable changed;
  private boolean onTop;

  /**
   * Creates the state, off.
   *
   * @param view the window
   * @param changed schedules the session save after a toggle
   */
  AlwaysOnTop(MainView view, Runnable changed) {
    this.view = view;
    this.changed = changed;
  }

  /**
   * Reports whether the platform can keep the window on top.
   *
   * @return the view's answer
   */
  public boolean supported() {
    return view.onTopSupported();
  }

  /**
   * Reports whether the item shows checked.
   *
   * @return true while on top and supported
   */
  public boolean shown() {
    return onTop && supported();
  }

  /**
   * Returns the flag the session saves.
   *
   * @return true when the window is to stay on top
   */
  public boolean saved() {
    return onTop;
  }

  /** Flips the state and schedules a save; does nothing where unsupported. */
  void toggle() {
    if (!supported()) {
      return;
    }
    onTop = !onTop;
    view.setOnTop(onTop);
    changed.run();
  }

  /**
   * Applies a session's flag.
   *
   * @param value the saved flag
   */
  void restore(boolean value) {
    onTop = value;
    if (value && supported()) {
      view.setOnTop(true);
    }
  }
}
