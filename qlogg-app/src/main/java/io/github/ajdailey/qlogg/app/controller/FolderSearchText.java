package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.FolderSearchState;
import io.github.ajdailey.qlogg.core.folder.FileHits;
import io.github.ajdailey.qlogg.core.folder.FolderProgress;
import java.nio.file.Path;
import java.util.List;

/** Builds the Search in Folder window's text: the summary line, set list lines and file status. */
public final class FolderSearchText {
  private FolderSearchText() {}

  /**
   * Builds a kept set's line in the set list: the search text, the folder's last name and the match
   * count, marked while running and when pinned.
   *
   * @param set the set
   * @return the line
   */
  public static String set(FolderResultSet set) {
    return set(
        set.request().text(),
        set.request().folder(),
        set.matches(),
        set.state().phase() == FolderSearchState.Phase.RUNNING,
        set.pinned());
  }

  static String set(String text, String folder, long matches, boolean running, boolean pinned) {
    String line = UiText.FOLDER_SET.format(text, lastName(folder), matches);
    if (running) {
      line = UiText.FOLDER_SET_RUNNING.format(line);
    }
    return pinned ? UiText.FOLDER_SET_PINNED.format(line) : line;
  }

  /**
   * Builds the summary line for a state.
   *
   * @param state the shown set's state
   * @param searched the shown set's search text; empty when none is shown
   * @param files the listed files
   * @param limited true when the results share stopped the run
   * @return the line
   */
  public static String summary(
      FolderSearchState state, String searched, List<FileHits> files, boolean limited) {
    long matches = 0;
    long withMatches = 0;
    for (FileHits file : files) {
      matches += file.matches();
      if (file.matches() > 0) {
        withMatches++;
      }
    }
    return summary(state, searched, matches, withMatches, files.size(), limited);
  }

  static String summary(
      FolderSearchState state,
      String searched,
      long matches,
      long withMatches,
      int fileCount,
      boolean limited) {
    FolderProgress progress = state.progress();
    return switch (state.phase()) {
      case IDLE -> UiText.FOLDER_IDLE.text();
      case RUNNING -> {
        if (progress.filesTotal() < 0) {
          yield UiText.FOLDER_WALKING.text();
        }
        Path file = progress.file() == null ? null : progress.file().getFileName();
        yield UiText.FOLDER_RUNNING.format(
            file == null ? "" : file.toString(),
            Math.min(progress.filesDone() + 1, progress.filesTotal()),
            progress.filesTotal(),
            progress.percent(),
            progress.matches());
      }
      case DONE ->
          limited
              ? UiText.FOLDER_LIMITED.format(searched, matches)
              : UiText.FOLDER_DONE.format(searched, matches, withMatches, fileCount);
      case CANCELED -> UiText.FOLDER_STOPPED.format(searched, matches, withMatches);
      case FAILED -> state.message();
    };
  }

  /**
   * Builds a file row's status column.
   *
   * @param file the file
   * @return the status
   */
  public static String status(FileHits file) {
    return status(file.status(), file.complete(), file.size(), file.detail());
  }

  static String status(FileHits.Status status, boolean complete, int listed, String detail) {
    return switch (status) {
      case SEARCHED ->
          complete ? UiText.FOLDER_STATUS_DONE.text() : UiText.FOLDER_STATUS_PARTIAL.format(listed);
      case UNREADABLE -> UiText.FOLDER_STATUS_UNREADABLE.format(String.valueOf(detail));
      case NOT_TEXT -> UiText.FOLDER_STATUS_NOT_TEXT.text();
      case FAILED -> UiText.FOLDER_STATUS_FAILED.format(String.valueOf(detail));
      case ACCESS_DENIED -> UiText.FOLDER_STATUS_ACCESS_DENIED.text();
    };
  }

  /**
   * Returns the last name of a folder as typed, without parsing it as a path: trailing separators
   * are dropped, and a folder that is only separators comes back as typed, stripped.
   *
   * @param folder the folder text
   * @return the last name
   */
  static String lastName(String folder) {
    String trimmed = folder.strip();
    int end = trimmed.length();
    while (end > 1 && (trimmed.charAt(end - 1) == '/' || trimmed.charAt(end - 1) == '\\')) {
      end--;
    }
    int start = Math.max(trimmed.lastIndexOf('/', end - 1), trimmed.lastIndexOf('\\', end - 1));
    String name = trimmed.substring(start + 1, end);
    return name.isEmpty() ? trimmed : name;
  }
}
