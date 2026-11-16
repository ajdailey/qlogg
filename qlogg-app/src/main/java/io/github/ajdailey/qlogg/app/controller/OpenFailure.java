package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * The error text shown when a file does not open. {@code AccessDeniedException} and {@code
 * NoSuchFileException} carry only the path as their message, so a denied, missing or folder path
 * maps to a fixed reason; any other failure shows its message.
 */
final class OpenFailure {
  private OpenFailure() {}

  /**
   * Returns {@code Cannot open <name>: <reason>}. Checks the path on disk; call on a worker.
   *
   * @param path the path that did not open
   * @param error the failure
   * @return the one-line text
   */
  @WorkerThread
  static String text(Path path, IOException error) {
    Path name = path.getFileName();
    return UiText.OPEN_FAILED.format(
        LogText.oneLine(name == null ? path : name), reason(path, error));
  }

  @WorkerThread
  private static String reason(Path path, IOException error) {
    if (error instanceof AccessDeniedException) {
      return UiText.OPEN_REASON_DENIED.text();
    }
    if (Files.isDirectory(path)) {
      return UiText.OPEN_REASON_FOLDER.text();
    }
    if (error instanceof NoSuchFileException || Files.notExists(path)) {
      Path parent = path.toAbsolutePath().getParent();
      return parent != null && Files.notExists(parent)
          ? UiText.OPEN_REASON_NO_FOLDER.text()
          : UiText.OPEN_REASON_MISSING.text();
    }
    return LogText.oneLine(error.getMessage());
  }
}
