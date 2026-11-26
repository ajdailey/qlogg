package io.github.ajdailey.qlogg.core.rotate;

import java.nio.file.Path;

/**
 * A file of a rotated set that the set does not load.
 *
 * @param path the file
 * @param reason why it is left out
 */
public record LeftOut(Path path, Reason reason) {
  /** Why a file is left out. */
  public enum Reason {
    /** A compression extension: the set reads no compressed file. */
    COMPRESSED,
    /** Its line feed has another width or byte order than the set's. */
    ENCODING,
    /** Older than the newest {@link RotatedSet#MAX_MEMBERS} files. */
    OVER_CAP
  }
}
