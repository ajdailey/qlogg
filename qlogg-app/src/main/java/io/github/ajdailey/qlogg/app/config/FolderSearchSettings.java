package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.core.folder.NamePattern;

/**
 * The Search in Folder window's remembered fields in {@code qlogg.conf}. Immutable.
 *
 * @param folder {@code folderSearch.folder}: the folder as typed or chosen, or empty
 * @param names {@code folderSearch.names}: the file name globs
 * @param recursive {@code folderSearch.recursive}: Include subfolders
 * @param exclude {@code folderSearch.exclude}: the file name globs left out, or empty
 */
public record FolderSearchSettings(String folder, String names, boolean recursive, String exclude) {
  /** Longest folder, names or exclude value kept; a longer one in the file reads as the default. */
  public static final int MAX_CHARS = 4096;

  /** No folder, the default names, no subfolders, no exclude. */
  public static final FolderSearchSettings DEFAULTS =
      new FolderSearchSettings("", NamePattern.DEFAULT, false, "");

  /** Checks the components. */
  public FolderSearchSettings {
    if (folder == null || names == null || exclude == null) {
      throw new IllegalArgumentException("folder, names and exclude are required");
    }
    if (folder.length() > MAX_CHARS || names.length() > MAX_CHARS || exclude.length() > MAX_CHARS) {
      throw new IllegalArgumentException("folder, names or exclude too long");
    }
  }
}
