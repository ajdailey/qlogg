package io.github.ajdailey.qlogg.core.index;

/**
 * Immutable snapshot of the indexed prefix of a file. Line numbers start at 0. Lookups in a sparse
 * snapshot read the file and throw {@link java.io.UncheckedIOException} when that read fails,
 * including after the source is closed.
 */
public interface LineIndex {
  /**
   * Counts complete lines, plus one when the indexed region ends in a partial line.
   *
   * @return lines; 0 for an empty file
   */
  long lineCount();

  /**
   * Returns the end of the indexed region, a byte offset that includes any byte order mark.
   *
   * @return bytes
   */
  long indexedBytes();

  /**
   * Reports whether the indexed region ends with LF (true for an empty file).
   *
   * @return false when the last line is partial
   */
  boolean lastLineComplete();

  /**
   * Returns a line's first byte.
   *
   * @param line 0 to {@code lineCount() - 1}
   * @return byte offset
   * @throws IndexOutOfBoundsException for any other line
   */
  long lineStart(long line);

  /**
   * Returns the nearest line start at or before a line that needs no file read: the line itself in
   * a full index, the last checkpoint at or before it in a sparse one (at most 1,024 lines or 1 MiB
   * earlier).
   *
   * @param line 0 to {@code lineCount() - 1}
   * @return the line and its first byte
   * @throws IndexOutOfBoundsException for any other line
   */
  LineStart nearestStart(long line);

  /**
   * Returns a line's exclusive end before its LF. A CR before the LF is part of the line; {@link
   * io.github.ajdailey.qlogg.core.text.LineDecoder} drops it.
   *
   * @param line 0 to {@code lineCount() - 1}
   * @return byte offset
   * @throws IndexOutOfBoundsException for any other line
   */
  long lineEnd(long line);

  /**
   * Returns the line containing a byte offset; an LF belongs to the line it ends. Offsets before
   * the first line give 0 and offsets past the region give the last line.
   *
   * @param byteOffset the offset
   * @return the line, or 0 when the index is empty
   */
  long lineAt(long byteOffset);

  /**
   * Returns the longest line's length, LF excluded.
   *
   * @return bytes
   */
  long maxLineBytes();

  /**
   * Reports the storage mode.
   *
   * @return true for checkpoint storage, where a lookup reads the file
   */
  boolean sparse();

  /**
   * Returns the publication number; each published snapshot of one index has a higher number than
   * the one before.
   *
   * @return the generation, 0 for the empty index
   */
  long generation();
}
