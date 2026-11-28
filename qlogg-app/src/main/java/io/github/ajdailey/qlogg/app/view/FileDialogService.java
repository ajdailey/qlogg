package io.github.ajdailey.qlogg.app.view;

import java.nio.file.Path;
import java.util.List;

/** The platform's file chooser. Called on the UI thread. */
public interface FileDialogService {
  /**
   * Asks for files to open.
   *
   * @param initialDirectory where the chooser starts, or null for its default
   * @return the chosen files, empty when canceled; not yet validated
   */
  List<Path> chooseFilesToOpen(Path initialDirectory);

  /**
   * Asks for a file to write.
   *
   * @param suggestedName the name the chooser starts with
   * @return the chosen path, not yet validated, or null when canceled
   */
  Path chooseSaveFile(String suggestedName);

  /**
   * Asks for a file to write a column view's rows to, and their form: the chooser's file types are
   * Columns (TSV) and Original Lines.
   *
   * @param suggestedName the name the chooser starts with
   * @return the chosen path and form, or null when canceled
   */
  SaveTarget chooseColumnSaveFile(String suggestedName);
}
