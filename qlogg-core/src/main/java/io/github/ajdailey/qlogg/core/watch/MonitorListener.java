package io.github.ajdailey.qlogg.core.watch;

import java.nio.file.Path;

/** Receives a watched path's checks on its {@code qlogg-follow-} thread. */
public interface MonitorListener {
  /**
   * The poll interval passed, or the directory reported an event for the file name. Calls for one
   * watch never overlap; the listener classifies the change itself.
   *
   * @param path the watched path
   */
  void possiblyChanged(Path path);
}
