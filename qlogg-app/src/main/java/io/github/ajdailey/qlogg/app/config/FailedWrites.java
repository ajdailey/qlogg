package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.core.text.LogText;
import java.io.IOException;
import java.nio.file.FileSystemException;
import org.slf4j.Logger;

/**
 * Logs the failed writes of one file: a WARN for the first failure of a run, DEBUG for the rest; a
 * write that lands ends the run. A line names the file and the exception class and reason, never a
 * path. Not synchronized; the owner's lock guards it.
 */
final class FailedWrites {
  private final Logger log;
  private final String name;
  private boolean failing;

  FailedWrites(Logger log, String name) {
    this.log = log;
    this.name = name;
  }

  /** Logs a failed write. */
  void failed(IOException e) {
    if (!failing) {
      failing = true;
      log.warn("Cannot save {}: {}", LogText.oneLine(name), reason(e));
    } else if (log.isDebugEnabled()) {
      log.debug("Cannot save {} again: {}", LogText.oneLine(name), reason(e));
    }
  }

  /** Ends the run of failures. */
  void written() {
    failing = false;
  }

  /** Returns the exception class and, for a file system error, its reason, which has no path. */
  private static String reason(IOException e) {
    String text = e.getClass().getSimpleName();
    if (e instanceof FileSystemException fs && fs.getReason() != null) {
      text += " (" + fs.getReason() + ")";
    }
    return LogText.oneLine(text);
  }
}
