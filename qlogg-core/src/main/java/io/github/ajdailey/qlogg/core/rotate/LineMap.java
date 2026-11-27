package io.github.ajdailey.qlogg.core.rotate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Where each member of a rotated set's old line numbering lands after a roll-over that changes line
 * numbers: a member keeps its lines in order under a new line base, or is dropped. Immutable.
 *
 * @param members the old members, oldest first
 */
public record LineMap(List<Entry> members) {
  /** The line base of a dropped member. */
  public static final long DROPPED = -1;

  /**
   * One old member.
   *
   * @param path the member's file before the roll-over
   * @param oldBase its first line in the old numbering
   * @param lines its line count in the old numbering
   * @param newBase its first line in the new numbering, or {@link #DROPPED}
   */
  public record Entry(Path path, long oldBase, long lines, long newBase) {}

  /** Copies the list. */
  public LineMap {
    members = List.copyOf(members);
  }

  /**
   * Maps an old line number.
   *
   * @param oldLine a line in the old numbering
   * @return the line in the new numbering, or {@link #DROPPED} when its member was dropped or the
   *     line is past the old members
   */
  public long map(long oldLine) {
    int low = 0;
    int high = members.size() - 1;
    while (low <= high) {
      int mid = (low + high) >>> 1;
      Entry entry = members.get(mid);
      if (oldLine < entry.oldBase()) {
        high = mid - 1;
      } else if (oldLine >= entry.oldBase() + entry.lines()) {
        low = mid + 1;
      } else {
        return entry.newBase() == DROPPED ? DROPPED : entry.newBase() + oldLine - entry.oldBase();
      }
    }
    return DROPPED;
  }

  /**
   * Lists the dropped members.
   *
   * @return their files before the roll-over, oldest first
   */
  public List<Path> dropped() {
    List<Path> paths = new ArrayList<Path>();
    for (Entry entry : members) {
      if (entry.newBase() == DROPPED) {
        paths.add(entry.path());
      }
    }
    return paths;
  }
}
