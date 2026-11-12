package io.github.ajdailey.qlogg.app.view;

import io.github.ajdailey.qlogg.core.folder.NamePattern;

/**
 * What the Search in Folder window asks for.
 *
 * @param folder the folder as typed or chosen
 * @param names {@code ;}-separated file name globs
 * @param recursive true to search subfolders
 * @param text the search text
 * @param options the toggles; mode, case, whole word, inverse and combining apply
 */
public record FolderRequest(
    String folder, String names, boolean recursive, String text, SearchOptions options) {
  /** No folder, the default names, no subfolders, no text, the default toggles. */
  public static final FolderRequest EMPTY =
      new FolderRequest("", NamePattern.DEFAULT, false, "", SearchOptions.DEFAULTS);

  /** Checks the components. */
  public FolderRequest {
    if (folder == null || names == null || text == null || options == null) {
      throw new IllegalArgumentException("every component is required");
    }
  }
}
