package io.github.ajdailey.qlogg.app.view;

import io.github.ajdailey.qlogg.core.folder.FolderProgress;

/**
 * The Search in Folder window's state, pushed on the UI thread.
 *
 * @param phase where the search is
 * @param progress the last progress of the search
 * @param message the reason for {@code FAILED}, otherwise null
 */
public record FolderSearchState(Phase phase, FolderProgress progress, String message) {
  /** Where a folder search is. */
  public enum Phase {
    /** No search yet. */
    IDLE,
    /** Walking the folder or searching its files. */
    RUNNING,
    /** Searched every file, or stopped at the results share. */
    DONE,
    /** Stopped by the user. */
    CANCELED,
    /** Did not start or failed; {@code message} says why. */
    FAILED
  }

  /** No search. */
  public static final FolderSearchState IDLE =
      new FolderSearchState(Phase.IDLE, FolderProgress.START, null);
}
