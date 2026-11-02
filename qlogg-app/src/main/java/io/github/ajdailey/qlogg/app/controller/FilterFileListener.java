package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.search.PredefinedFilter;
import java.util.List;

/** Receives the end of a filter import or export, on the UI thread. */
public interface FilterFileListener {
  /**
   * Called when the file was read or written.
   *
   * @param filters the filters read, or the filters written
   */
  void done(List<PredefinedFilter> filters);

  /**
   * Called when the path was rejected or the file could not be read, parsed or written.
   *
   * @param message one line for the user
   */
  void failed(String message);
}
