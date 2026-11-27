package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.FileByteSource;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.rotate.LineMap;
import io.github.ajdailey.qlogg.core.rotate.Member;
import io.github.ajdailey.qlogg.core.rotate.MemberIdentity;
import io.github.ajdailey.qlogg.core.rotate.RotatedSet;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32C;

/**
 * Compares a rescan of a rotated set with its loaded members and decides how the set follows.
 * Members are matched by {@link MemberIdentity}, and by name where a member has no recorded digests
 * or its digests match several files. The roll-over is in place when every loaded member keeps its
 * rank in the new order, every member before the last keeps its size, and the last holds at least
 * its indexed bytes: renamed members are re-pointed and newer files join after the last member once
 * one of them has bytes. A last member found holding fewer than its indexed bytes beside another
 * file holding them is a copy-truncate or a rename: that file takes the member's place and the
 * short file joins. Without file keys, this settles a rename where each new file starts with the
 * same line and the member's digests still cover that line alone. A newer file whose bytes start as
 * the last member's do, an empty file included, while that member has not truncated is a copy in
 * progress, ignored until its size holds for two rescans. Anything else changes line numbers: the
 * plan carries a {@link LineMap}, with the line counts of the new order counted from the files
 * where the loaded indexes do not cover them. Not thread-safe; the set's watch calls it.
 */
final class RollOver {
  private static final int READ_BYTES = 64 * 1024;
  private static final int STABLE_RESCANS = 2;

  /** How the set follows a rescan. */
  enum Kind {
    /** Members stay where they are; growth of the last member is an append. */
    UNCHANGED,
    /** Members are re-pointed or join; line numbers stay. */
    IN_PLACE,
    /** The last member is gone and no file took its place. */
    MISSING,
    /** Line numbers change: the set is loaded again through the map. */
    ROLLED
  }

  /**
   * The decision.
   *
   * @param kind how the set follows
   * @param paths for {@code UNCHANGED} and {@code IN_PLACE}, each loaded member's file now
   * @param joins for {@code IN_PLACE}, the files that join after the last member, oldest first
   * @param waiting true when newer files without bytes wait to join
   * @param map for {@code ROLLED}, the old line numbering's map
   */
  record Plan(Kind kind, List<Path> paths, List<Member> joins, boolean waiting, LineMap map) {}

  private final TextEncoding encoding;
  private final SafePaths.HeldLock held;
  private final BooleanSupplier closing;
  private final Map<Path, long[]> copies = new HashMap<Path, long[]>();
  private final boolean fileKeys;

  /**
   * Creates the planner for one set.
   *
   * @param encoding the set's encoding
   * @param held the instance lock, or null
   * @param closing true once the set closes, which ends a line count
   */
  RollOver(TextEncoding encoding, SafePaths.HeldLock held, BooleanSupplier closing) {
    this(encoding, held, closing, true);
  }

  /**
   * Creates the planner for one set.
   *
   * @param encoding the set's encoding
   * @param held the instance lock, or null
   * @param closing true once the set closes, which ends a line count
   * @param fileKeys false to match members by content alone, with creation times that decide
   *     nothing, as on Windows after NTFS tunneling
   */
  RollOver(
      TextEncoding encoding, SafePaths.HeldLock held, BooleanSupplier closing, boolean fileKeys) {
    this.encoding = encoding;
    this.held = held;
    this.closing = closing;
    this.fileKeys = fileKeys;
  }

  /**
   * Plans the set's response to a rescan. Blocking: reads digests and, for {@code ROLLED}, counts
   * lines in files the indexes do not cover.
   *
   * @param members the loaded members, oldest first
   * @param indexed members 0 to {@code indexed - 1} are in the published index
   * @param now the rescan
   * @return the plan
   * @throws IOException when a file the plan needs cannot be read
   */
  Plan plan(List<SetMember> members, int indexed, RotatedSet now) throws IOException {
    List<Member> found = now.members();
    List<Path> paths = new ArrayList<Path>();
    for (Member member : found) {
      paths.add(member.path());
    }
    int last = members.size() - 1;
    int[] at = new int[members.size()];
    boolean[] claimed = new boolean[found.size()];
    for (int k = 0; k <= last; k++) {
      at[k] = locate(members.get(k), paths, claimed);
      if (at[k] >= 0) {
        claimed[at[k]] = true;
      }
    }
    SetMember tail = members.get(last);
    long tailIndexed = tail.index().snapshot().indexedBytes();
    if (at[last] >= 0 && found.get(at[last]).size() < tailIndexed) {
      int shorter = at[last];
      at[last] = copyOf(tail, paths, claimed);
      claimed[shorter] = false;
      if (at[last] >= 0) {
        claimed[at[last]] = true;
      }
    } else if (at[last] >= 0 && rewritten(tail, paths.get(at[last]))) {
      claimed[at[last]] = false;
      at[last] = -1;
    }
    boolean[] ignored = copiesInProgress(tail, found, claimed, at[last]);
    int[] rank = new int[found.size()];
    int next = 0;
    for (int j = 0; j < found.size(); j++) {
      rank[j] = ignored[j] ? -1 : next++;
    }
    boolean kept = true;
    for (int k = 0; k <= last && kept; k++) {
      kept = at[k] >= 0 && rank[at[k]] == k;
      if (kept && k < last) {
        kept = found.get(at[k]).size() == members.get(k).limit();
      }
    }
    if (!kept && at[last] < 0 && next == last && keptBefore(at, rank, last)) {
      return new Plan(Kind.MISSING, List.<Path>of(), List.<Member>of(), false, null);
    }
    if (!kept) {
      return new Plan(
          Kind.ROLLED,
          List.<Path>of(),
          List.<Member>of(),
          false,
          map(members, indexed, found, at, last));
    }
    List<Path> current = new ArrayList<Path>();
    boolean moved = false;
    for (int k = 0; k <= last; k++) {
      current.add(paths.get(at[k]));
      moved |= !paths.get(at[k]).equals(members.get(k).path());
    }
    List<Member> newer = new ArrayList<Member>();
    int withBytes = -1;
    for (int j = at[last] + 1; j < found.size(); j++) {
      if (!ignored[j]) {
        newer.add(found.get(j));
        if (found.get(j).size() > 0) {
          withBytes = newer.size() - 1;
        }
      }
    }
    List<Member> joins = newer.subList(0, withBytes + 1);
    boolean waiting = withBytes < newer.size() - 1;
    Kind kind = moved || !joins.isEmpty() ? Kind.IN_PLACE : Kind.UNCHANGED;
    return new Plan(kind, current, List.copyOf(joins), waiting, null);
  }

  /** True when every member before the last keeps its rank. */
  private static boolean keptBefore(int[] at, int[] rank, int last) {
    for (int k = 0; k < last; k++) {
      if (at[k] < 0 || rank[at[k]] != k) {
        return false;
      }
    }
    return true;
  }

  /** The member's position by identity, else by name when its digests decide nothing. */
  private int locate(SetMember member, List<Path> paths, boolean[] claimed) {
    MemberIdentity identity = identity(member);
    if (identity != null) {
      MemberIdentity.Match match = identity.find(unclaimed(paths, claimed));
      if (match.outcome() == MemberIdentity.Outcome.FOUND) {
        return paths.indexOf(match.path());
      }
      if (match.outcome() == MemberIdentity.Outcome.NONE) {
        return -1;
      }
    }
    int byName = paths.indexOf(member.path());
    return byName >= 0 && !claimed[byName] ? byName : -1;
  }

  /**
   * An unclaimed file holding the member's indexed bytes, compared by content alone. The caller
   * keeps the short file claimed: it can match digests recorded before the last indexing pass.
   */
  private int copyOf(SetMember member, List<Path> paths, boolean[] claimed) {
    MemberIdentity identity = identity(member);
    if (identity == null) {
      return -1;
    }
    MemberIdentity.Match match = identity.byContent().find(unclaimed(paths, claimed));
    return match.outcome() == MemberIdentity.Outcome.FOUND ? paths.indexOf(match.path()) : -1;
  }

  /** True when a member matched by file key does not hold its indexed bytes. */
  private boolean rewritten(SetMember member, Path file) {
    MemberIdentity identity = identity(member);
    return identity != null
        && identity.fileKey() != null
        && identity.byContent().find(List.of(file)).outcome() != MemberIdentity.Outcome.FOUND;
  }

  /** The member's recorded identity; without file keys, its content alone. */
  private MemberIdentity identity(SetMember member) {
    MemberIdentity recorded = member.memberIdentity();
    if (fileKeys || recorded == null) {
      return recorded;
    }
    return new MemberIdentity(
        null, -1, recorded.indexedBytes(), recorded.headDigest(), recorded.tailDigest());
  }

  private static List<Path> unclaimed(List<Path> paths, boolean[] claimed) {
    List<Path> free = new ArrayList<Path>();
    for (int j = 0; j < paths.size(); j++) {
      if (!claimed[j]) {
        free.add(paths.get(j));
      }
    }
    return free;
  }

  /**
   * Marks the unclaimed files before the last member whose bytes start as the last member's do,
   * until a file's size holds for {@link #STABLE_RESCANS} rescans.
   */
  private boolean[] copiesInProgress(
      SetMember tail, List<Member> found, boolean[] claimed, int tailAt) throws IOException {
    boolean[] ignored = new boolean[found.size()];
    Map<Path, long[]> seen = new HashMap<Path, long[]>();
    for (int j = 0; j < tailAt; j++) {
      Member candidate = found.get(j);
      if (claimed[j] || !samePrefix(candidate, found.get(tailAt))) {
        continue;
      }
      long[] before = copies.get(candidate.path());
      long steady = 0;
      if (before != null && before[0] == candidate.size()) {
        steady = before[1] + 1;
      }
      seen.put(candidate.path(), new long[] {candidate.size(), steady});
      ignored[j] = steady < STABLE_RESCANS;
    }
    copies.clear();
    copies.putAll(seen);
    return ignored;
  }

  /** True when the candidate's bytes equal the start of the other file. */
  private boolean samePrefix(Member candidate, Member other) throws IOException {
    int length = (int) Math.min(candidate.size(), READ_BYTES);
    if (other.size() < length) {
      return false;
    }
    return digest(candidate.path(), length) == digest(other.path(), length);
  }

  private long digest(Path path, int length) throws IOException {
    try (FileByteSource file = FileByteSource.open(path, held)) {
      ByteBuffer bytes = ByteBuffer.allocate(length);
      while (bytes.hasRemaining() && file.read(bytes.position(), bytes) >= 0) {
        // Positional reads until full or at the end.
      }
      bytes.flip();
      CRC32C crc = new CRC32C();
      crc.update(bytes);
      return crc.getValue();
    }
  }

  /** The map from the old numbering to the order of the rescan. */
  private LineMap map(List<SetMember> members, int indexed, List<Member> found, int[] at, int last)
      throws IOException {
    int needed = 0;
    for (int k = 0; k <= last; k++) {
      needed = Math.max(needed, at[k]);
    }
    long[] counts = new long[needed];
    for (int j = 0; j < needed; j++) {
      int owner = -1;
      for (int k = 0; k <= last; k++) {
        if (at[k] == j) {
          owner = k;
        }
      }
      counts[j] = owner >= 0 ? lines(members.get(owner), found.get(j)) : lines(null, found.get(j));
    }
    List<LineMap.Entry> entries = new ArrayList<LineMap.Entry>();
    long oldBase = 0;
    for (int k = 0; k <= last; k++) {
      SetMember member = members.get(k);
      long lines = k < indexed ? member.index().snapshot().lineCount() : 0;
      boolean shrank =
          at[k] >= 0 && found.get(at[k]).size() < member.index().snapshot().indexedBytes();
      long newBase = LineMap.DROPPED;
      if (at[k] >= 0 && !shrank) {
        newBase = 0;
        for (int j = 0; j < at[k]; j++) {
          newBase += counts[j];
        }
      }
      entries.add(new LineMap.Entry(member.path(), oldBase, lines, newBase));
      oldBase += lines;
    }
    return new LineMap(entries);
  }

  /**
   * Counts a file's lines as the set's index will, a last line without LF included: from the
   * member's index where it covers the file, by reading the rest.
   */
  private long lines(SetMember member, Member file) throws IOException {
    long from = file.encoding().bomLength();
    long complete = 0;
    if (member != null) {
      LineIndex snapshot = member.index().snapshot();
      if (snapshot.indexedBytes() <= file.size()) {
        from = snapshot.indexedBytes();
        complete = snapshot.lineCount() - (snapshot.lastLineComplete() ? 0 : 1);
      }
    }
    int unit = encoding.unitWidth();
    long end = file.size() / unit * unit;
    boolean open;
    try (FileByteSource source = FileByteSource.open(file.path(), held)) {
      LineCounter.Counted counted =
          LineCounter.count(source, file.path(), from, end, encoding, closing);
      complete += counted.complete();
      open = counted.open();
    }
    if (from == end && member != null) {
      LineIndex snapshot = member.index().snapshot();
      open = !snapshot.lastLineComplete();
    }
    return complete + (open ? 1 : 0);
  }
}
