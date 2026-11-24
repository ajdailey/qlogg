package io.github.ajdailey.qlogg.ui.swt.platform;

/**
 * A main window's View > Full Screen state, not saved, one implementation per platform from {@link
 * PlatformTraits#fullScreen}. While {@link #on}, the window's tracked bounds and maximized state
 * stay those from before. Confined to the UI thread.
 */
public interface FullScreen {
  /**
   * Reports whether the window fills the screen now.
   *
   * @return true while full screen
   */
  boolean on();

  /**
   * Reports the state View > Full Screen shows: a request still waiting for the shell, else {@link
   * #on}.
   *
   * @return true when full screen is on or requested
   */
  boolean shown();

  /**
   * Fills the screen with the window, or returns it to its bounds.
   *
   * @param value true to fill the screen
   */
  void set(boolean value);

  /** Records the maximized state of a window that is not full screen. */
  void track();

  /**
   * Reports whether the window is maximized, or was before full screen.
   *
   * @return the state
   */
  boolean maximized();
}
