package io.github.ajdailey.qlogg.app.view;

/**
 * What the status bar shows for a document.
 *
 * @param title the file name
 * @param path the full path, or empty for a document with no file
 * @param encodingName the charset name
 * @param encodingForced true when the user chose the encoding, false when it was detected
 * @param lineCount lines indexed so far
 * @param indexPercent indexed share, 0-100
 * @param indexing true while indexing runs
 * @param sparseIndex true when the index uses sparse storage, so random access is slower
 * @param following true while the view follows the end of the file
 * @param fileBytes the file's size at the last check
 * @param modifiedMillis the file's modification time at the last check
 * @param missing true while no file is at the path
 * @param loadingStopped true after stop loading, until the file is reloaded
 * @param error a one-line failure message, or null
 */
public record DocumentViewState(
    String title,
    String path,
    String encodingName,
    boolean encodingForced,
    long lineCount,
    int indexPercent,
    boolean indexing,
    boolean sparseIndex,
    boolean following,
    long fileBytes,
    long modifiedMillis,
    boolean missing,
    boolean loadingStopped,
    String error) {
  /**
   * Reports whether a file backs the document, as opposed to pasted text.
   *
   * @return true when {@link #path} names a file
   */
  public boolean hasFile() {
    return !path.isEmpty();
  }
}
