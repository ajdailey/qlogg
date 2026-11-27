package io.github.ajdailey.qlogg.core.rotate;

import java.nio.file.Path;
import java.util.List;

/**
 * The files of an open rotated set.
 *
 * @param members the loaded members, oldest first, numbered as {@link RotatedIndex#memberOf}
 *     numbers them
 * @param leftOut the files of the series the set does not load, as the last listing found them
 */
public record SetFiles(List<Loaded> members, List<LeftOut> leftOut) {
  /**
   * One loaded member.
   *
   * @param path its current name
   * @param size its bytes at the last listing, or at open when the listing missed it
   * @param modifiedMillis its last-modified time, read as {@code size} is
   * @param headDigest CRC32C of its first 4 KiB at open ({@code FileIdentity.headDigest})
   */
  public record Loaded(Path path, long size, long modifiedMillis, long headDigest) {}

  /** Copies the lists. */
  public SetFiles {
    members = List.copyOf(members);
    leftOut = List.copyOf(leftOut);
  }

  /**
   * Counts the left-out files with one reason.
   *
   * @param reason the reason
   * @return the files
   */
  public int leftOut(LeftOut.Reason reason) {
    int count = 0;
    for (LeftOut file : leftOut) {
      if (file.reason() == reason) {
        count++;
      }
    }
    return count;
  }
}
