package io.github.ajdailey.qlogg.ui.swt.platform;

import java.util.List;

/**
 * How a launch receives its files, one implementation per platform from {@link
 * PlatformTraits#launchDocuments}. On cocoa, Finder Open With can start a second copy with no
 * arguments and send the files as {@code SWT.OpenDocument} events once the Display exists, so the
 * launch waits for them before it hands off; elsewhere every file is an argument.
 */
public interface LaunchDocuments {
  /**
   * Collects the files the system sends as events.
   *
   * @param wait runs the event loop and returns the files received
   * @return the files in arrival order: {@code wait}'s on cocoa, empty elsewhere, where {@code
   *     wait} is not called
   */
  List<String> await(Wait wait);

  /** Runs an event loop for {@code SWT.OpenDocument} events. */
  interface Wait {
    /**
     * Creates a Display, collects the files and disposes the Display.
     *
     * @return the files in arrival order
     */
    List<String> run();
  }
}
