package io.github.ajdailey.qlogg.core.folder;

import io.github.ajdailey.qlogg.core.search.SearchQuery;

/**
 * What a folder search looks for.
 *
 * @param folder the folder as the user gave it, validated by {@code SafePaths.logDirectory} on the
 *     search thread
 * @param names the file names searched
 * @param recursive true to search subfolders too
 * @param query the search of each file; its {@code scope} must be null
 */
public record FolderQuery(String folder, NamePattern names, boolean recursive, SearchQuery query) {
  /** Checks the components. */
  public FolderQuery {
    if (folder == null || names == null || query == null) {
      throw new IllegalArgumentException("folder, names and query are required");
    }
    if (query.scope() != null) {
      throw new IllegalArgumentException("a folder search covers every line");
    }
  }
}
