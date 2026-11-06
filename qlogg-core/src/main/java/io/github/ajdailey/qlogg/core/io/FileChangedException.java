package io.github.ajdailey.qlogg.core.io;

import java.io.IOException;
import java.nio.file.Path;

/** The file at a {@link ReopeningByteSource}'s real path is not the file it opened. */
public final class FileChangedException extends IOException {
  private static final long serialVersionUID = 1L;

  FileChangedException(Path path) {
    super(path + " changed on disk");
  }
}
