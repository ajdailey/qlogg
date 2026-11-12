package io.github.ajdailey.qlogg.core.folder;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * One file of a folder search: its status, match count and the matching lines it lists. Immutable
 * once a search hands it to {@link FolderResults}.
 */
public final class FileHits {
  /** How a file's search ended. */
  public enum Status {
    /** Searched; {@link #matches} counts the matching lines. */
    SEARCHED,
    /** The file or folder could not be opened, read or indexed; {@link #detail} names why. */
    UNREADABLE,
    /** A NUL byte in the first 64 KiB of a file with a one-byte code unit: compressed or binary. */
    NOT_TEXT,
    /** The search of this file failed, for example a regex that overflows on a line. */
    FAILED
  }

  private final Path path;
  private final Status status;
  private final String detail;
  private final long matches;
  private final long[] lines;
  private final String[] texts;
  private final boolean complete;
  private final long retainedBytes;

  private FileHits(
      Path path,
      Status status,
      String detail,
      long matches,
      long[] lines,
      String[] texts,
      boolean complete,
      long retainedBytes) {
    this.path = path;
    this.status = status;
    this.detail = detail;
    this.matches = matches;
    this.lines = lines;
    this.texts = texts;
    this.complete = complete;
    this.retainedBytes = retainedBytes;
  }

  /**
   * Creates a searched file.
   *
   * @param path the file
   * @param matches the matching lines the search found
   * @param lines 0-based line numbers, ascending; the first {@code count} are listed
   * @param texts the listed lines' text, cut at {@link FolderSearch#MAX_TEXT_CHARS}
   * @param count lines listed
   * @param complete false when the results share stopped the search or the listing
   * @param retainedBytes bytes reserved from {@code RESULTS} for this entry
   * @return the entry
   */
  static FileHits searched(
      Path path,
      long matches,
      long[] lines,
      String[] texts,
      int count,
      boolean complete,
      long retainedBytes) {
    return new FileHits(
        path,
        Status.SEARCHED,
        null,
        matches,
        Arrays.copyOf(lines, count),
        Arrays.copyOf(texts, count),
        complete,
        retainedBytes);
  }

  /**
   * Creates a file that was not searched or whose search failed.
   *
   * @param path the file or folder
   * @param status not {@code SEARCHED}
   * @param detail why, one line, or null
   * @param retainedBytes bytes reserved from {@code RESULTS} for this entry
   * @return the entry
   */
  static FileHits skipped(Path path, Status status, String detail, long retainedBytes) {
    return new FileHits(
        path,
        status,
        detail,
        0,
        new long[0],
        new String[0],
        status != Status.FAILED,
        retainedBytes);
  }

  /**
   * Returns the file.
   *
   * @return the path under the searched folder's real path
   */
  public Path path() {
    return path;
  }

  /**
   * Returns the status.
   *
   * @return how the file's search ended
   */
  public Status status() {
    return status;
  }

  /**
   * Returns why a file was not searched or failed.
   *
   * @return one line, or null
   */
  public String detail() {
    return detail;
  }

  /**
   * Counts the matching lines the search found; {@link #size} may list fewer.
   *
   * @return lines
   */
  public long matches() {
    return matches;
  }

  /**
   * Counts the listed lines.
   *
   * @return lines with text
   */
  public int size() {
    return lines.length;
  }

  /**
   * Returns a listed line's number.
   *
   * @param i from 0 to {@link #size} - 1
   * @return the 0-based line number
   */
  public long line(int i) {
    return lines[i];
  }

  /**
   * Returns a listed line's text.
   *
   * @param i from 0 to {@link #size} - 1
   * @return the line, tabs kept, cut at {@link FolderSearch#MAX_TEXT_CHARS}
   */
  public String text(int i) {
    return texts[i];
  }

  /**
   * Reports whether the file was searched to its end and every match listed.
   *
   * @return false when the results share stopped the search or the listing
   */
  public boolean complete() {
    return complete;
  }

  /**
   * Returns the bytes reserved from {@code RESULTS} for this entry.
   *
   * @return bytes
   */
  public long retainedBytes() {
    return retainedBytes;
  }
}
