package io.github.ajdailey.qlogg.app.cli;

import java.nio.file.Path;

/**
 * A file to open.
 *
 * @param path the path as given, resolved against the working directory and not yet validated
 * @param line the 1-based line to open at, or 0 when not given
 * @param rotated true to open the rotated set the file belongs to ({@code --rotated})
 */
public record FileRequest(Path path, long line, boolean rotated) {
  /**
   * Creates a request for the file alone.
   *
   * @param path the path as given
   * @param line the 1-based line to open at, or 0
   */
  public FileRequest(Path path, long line) {
    this(path, line, false);
  }
}
