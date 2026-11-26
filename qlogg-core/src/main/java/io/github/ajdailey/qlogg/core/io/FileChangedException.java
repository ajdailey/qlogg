package io.github.ajdailey.qlogg.core.io;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The file at a {@link ReopeningByteSource}'s real path is not the file it opened, or a rotated
 * member holds fewer bytes than the set was assembled with.
 */
public final class FileChangedException extends IOException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param path the file that changed
   */
  public FileChangedException(Path path) {
    super(path + " changed on disk");
  }
}
