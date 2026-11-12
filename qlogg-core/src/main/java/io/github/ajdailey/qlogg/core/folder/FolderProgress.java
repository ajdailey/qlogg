package io.github.ajdailey.qlogg.core.folder;

import java.nio.file.Path;

/**
 * A folder search's progress.
 *
 * @param filesDone files searched or listed as not searched
 * @param filesTotal files the walk found, or -1 while it runs
 * @param file the file being searched, or null between files
 * @param filePercent that file's progress, 0-100: indexing is the first half, the search the second
 * @param bytesDone bytes of the files done plus the current file's share of its percent
 * @param bytesTotal bytes of the files the walk found, or 0 while it runs
 * @param matches matching lines found so far
 */
public record FolderProgress(
    int filesDone,
    int filesTotal,
    Path file,
    int filePercent,
    long bytesDone,
    long bytesTotal,
    long matches) {
  /** No progress yet. */
  public static final FolderProgress START = new FolderProgress(0, -1, null, 0, 0, 0, 0);

  /**
   * Returns the overall percent.
   *
   * @return 0-100 by bytes; 0 while the walk runs
   */
  public int percent() {
    if (bytesTotal <= 0) {
      return filesTotal >= 0 && filesDone >= filesTotal ? 100 : 0;
    }
    return (int) Math.min(100, bytesDone * 100 / bytesTotal);
  }
}
