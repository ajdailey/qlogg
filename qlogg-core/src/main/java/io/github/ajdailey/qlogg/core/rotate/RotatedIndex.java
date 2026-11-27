package io.github.ajdailey.qlogg.core.rotate;

import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.index.LineStart;
import java.util.List;

/**
 * An immutable line index over a {@link RotatedBytes} stream, built from one snapshot per member.
 * Line numbers run across members; offsets are stream offsets. Each lookup binary-searches the
 * members, then looks up the member's snapshot and adds the member's line and byte base. A sparse
 * member's lookups read its file, as a sparse single-file index does.
 *
 * <p>The snapshots cover a prefix of the set: every member but the last given is completely
 * indexed, so line numbers below the last given member never change while it grows.
 */
public final class RotatedIndex implements LineIndex {
  private final LineIndex[] members;
  private final int[] memberNumbers;
  private final long[] lineBase;
  private final long[] streamStart;
  private final long[] shift;
  private final int given;
  private final long lineCount;
  private final long indexedBytes;
  private final boolean lastLineComplete;
  private final long maxLineBytes;
  private final boolean sparse;
  private final long generation;

  private RotatedIndex(RotatedBytes bytes, List<LineIndex> snapshots) {
    int count = snapshots.size();
    if (count > bytes.memberCount()) {
      throw new IllegalArgumentException("More snapshots than members");
    }
    int withLines = 0;
    for (int i = 0; i < count; i++) {
      if (i < count - 1 && snapshots.get(i).indexedBytes() != bytes.memberEnd(i)) {
        throw new IllegalArgumentException("Member " + i + " is not completely indexed");
      }
      if (snapshots.get(i).lineCount() > 0) {
        withLines++;
      }
    }
    this.given = count;
    this.members = new LineIndex[withLines];
    this.memberNumbers = new int[withLines];
    this.lineBase = new long[withLines];
    this.streamStart = new long[withLines];
    this.shift = new long[withLines];
    long lines = 0;
    long longest = 0;
    long generations = 0;
    boolean anySparse = false;
    int at = 0;
    for (int i = 0; i < count; i++) {
      LineIndex snapshot = snapshots.get(i);
      generations += snapshot.generation();
      anySparse |= snapshot.sparse();
      longest = Math.max(longest, snapshot.maxLineBytes());
      if (snapshot.lineCount() > 0) {
        members[at] = snapshot;
        memberNumbers[at] = i;
        lineBase[at] = lines;
        streamStart[at] = bytes.memberStart(i);
        shift[at] = bytes.memberStart(i) - bytes.bomLength(i);
        lines += snapshot.lineCount();
        at++;
      }
    }
    this.lineCount = lines;
    this.maxLineBytes = longest;
    this.sparse = anySparse;
    this.generation = generations;
    if (count == 0) {
      this.indexedBytes = 0;
      this.lastLineComplete = true;
    } else {
      int end = count - 1;
      LineIndex tail = snapshots.get(end);
      boolean whole = tail.indexedBytes() == bytes.memberEnd(end);
      long translated = bytes.memberStart(end) - bytes.bomLength(end) + tail.indexedBytes();
      this.indexedBytes = whole ? translated + bytes.addedBytes(end) : translated;
      this.lastLineComplete = whole || tail.lastLineComplete();
    }
  }

  /**
   * Builds the index.
   *
   * @param bytes the stream the snapshots' members belong to
   * @param snapshots member snapshots in member order, from member 0; each over {@link
   *     RotatedBytes#member} with the member's byte order mark length; every one but the last
   *     indexed to {@link RotatedBytes#memberEnd}
   * @return the index
   * @throws IllegalArgumentException when a snapshot before the last is incomplete, or there are
   *     more snapshots than members
   */
  public static RotatedIndex of(RotatedBytes bytes, List<LineIndex> snapshots) {
    return new RotatedIndex(bytes, snapshots);
  }

  /**
   * Returns the number of member snapshots the index was built from.
   *
   * @return members, from member 0
   */
  public int indexedMembers() {
    return given;
  }

  /**
   * Returns the first line of each member with lines.
   *
   * @return ascending lines, line 0 first when a member has lines
   */
  public long[] memberStarts() {
    return lineBase.clone();
  }

  /**
   * Returns the member that holds a line.
   *
   * @param line 0 to {@code lineCount() - 1}
   * @return the member number in the stream
   * @throws IndexOutOfBoundsException for any other line
   */
  public int memberOf(long line) {
    return memberNumbers[byLine(line)];
  }

  /**
   * Returns a line's number within its member.
   *
   * @param line 0 to {@code lineCount() - 1}
   * @return the member line
   * @throws IndexOutOfBoundsException for any other line
   */
  public long memberLine(long line) {
    return line - lineBase[byLine(line)];
  }

  @Override
  public long lineCount() {
    return lineCount;
  }

  @Override
  public long indexedBytes() {
    return indexedBytes;
  }

  @Override
  public boolean lastLineComplete() {
    return lastLineComplete;
  }

  @Override
  public long lineStart(long line) {
    int k = byLine(line);
    return members[k].lineStart(line - lineBase[k]) + shift[k];
  }

  @Override
  public LineStart nearestStart(long line) {
    int k = byLine(line);
    LineStart start = members[k].nearestStart(line - lineBase[k]);
    return new LineStart(start.line() + lineBase[k], start.offset() + shift[k]);
  }

  @Override
  public long lineEnd(long line) {
    int k = byLine(line);
    return members[k].lineEnd(line - lineBase[k]) + shift[k];
  }

  @Override
  public long lineAt(long byteOffset) {
    if (members.length == 0 || byteOffset < streamStart[0]) {
      return 0;
    }
    int k = last(streamStart, byteOffset);
    return lineBase[k] + members[k].lineAt(byteOffset - shift[k]);
  }

  @Override
  public long maxLineBytes() {
    return maxLineBytes;
  }

  @Override
  public boolean sparse() {
    return sparse;
  }

  /** Returns the sum of the member snapshots' generations. */
  @Override
  public long generation() {
    return generation;
  }

  private int byLine(long line) {
    if (line < 0 || line >= lineCount) {
      throw new IndexOutOfBoundsException("Line " + line + " of " + lineCount);
    }
    return last(lineBase, line);
  }

  /** Last index whose value is at or before the key; values ascend and the first is at most key. */
  private static int last(long[] values, long key) {
    int low = 0;
    int high = values.length - 1;
    while (low < high) {
      int mid = (low + high + 1) >>> 1;
      if (values[mid] <= key) {
        low = mid;
      } else {
        high = mid - 1;
      }
    }
    return low;
  }
}
