package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.view.FolderRequest;
import io.github.ajdailey.qlogg.app.view.FolderSearchState;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.folder.FileHits;
import io.github.ajdailey.qlogg.core.folder.FolderProgress;
import io.github.ajdailey.qlogg.core.folder.FolderResults;
import java.util.List;

/**
 * One Search in Folder run that {@link FolderSearchController} keeps: its request, its {@link
 * FolderResults} and its last state. Read on the UI thread; the controller writes it.
 */
public final class FolderResultSet {
  private final FolderRequest request;
  private final FolderResults results;
  private FolderSearchState state =
      new FolderSearchState(FolderSearchState.Phase.RUNNING, FolderProgress.START, null);
  private OperationHandle handle;
  private boolean dropped;

  /** Written under the controller's lock; the search thread reads it there. */
  private boolean pinned;

  FolderResultSet(FolderRequest request, FolderResults results) {
    this.request = request;
    this.results = results;
  }

  /**
   * Returns what the run searched.
   *
   * @return the request
   */
  public FolderRequest request() {
    return request;
  }

  /**
   * Returns the run's last state.
   *
   * @return the state
   */
  public FolderSearchState state() {
    return state;
  }

  /**
   * Reports whether the set is kept past the newest {@link FolderSearchController#KEPT_SETS}.
   *
   * @return true when pinned
   */
  public boolean pinned() {
    return pinned;
  }

  /**
   * Returns the listed files; the search adds to them while it runs.
   *
   * @return a new list, empty once the set is dropped
   */
  public List<FileHits> files() {
    return results.files();
  }

  /**
   * Counts the matching lines.
   *
   * @return lines
   */
  public long matches() {
    return results.matches();
  }

  /**
   * Reports whether the results share stopped the run.
   *
   * @return true once it did
   */
  public boolean limited() {
    return results.limited();
  }

  FolderResults results() {
    return results;
  }

  void setState(FolderSearchState next) {
    state = next;
  }

  OperationHandle handle() {
    return handle;
  }

  void setHandle(OperationHandle running) {
    handle = running;
  }

  boolean dropped() {
    return dropped;
  }

  void setDropped() {
    dropped = true;
  }

  void setPinned(boolean on) {
    pinned = on;
  }
}
