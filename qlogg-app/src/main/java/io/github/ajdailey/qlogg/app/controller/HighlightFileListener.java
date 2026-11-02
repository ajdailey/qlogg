package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.core.model.HighlightSet;
import java.util.List;

/** Receives the end of a highlighter import or export, on the UI thread. */
public interface HighlightFileListener {
  /**
   * The file was read or written.
   *
   * @param sets the sets read, or the sets written
   */
  void done(List<HighlightSet> sets);

  /**
   * The file was not read or written.
   *
   * @param message one line, without file content
   */
  void failed(String message);
}
