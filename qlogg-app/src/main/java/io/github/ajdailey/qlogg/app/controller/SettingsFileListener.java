package io.github.ajdailey.qlogg.app.controller;

import java.util.List;

/**
 * Receives the end of a filter or highlighter import or export, on the UI thread.
 *
 * @param <T> the entry type of the file
 */
public interface SettingsFileListener<T> {
  /**
   * The file was read or written.
   *
   * @param entries the entries read, or the entries written
   */
  void done(List<T> entries);

  /**
   * The path was rejected or the file could not be read, parsed or written.
   *
   * @param message one line for the user, without file content
   */
  void failed(String message);
}
