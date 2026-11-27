package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.FileIdentity;
import io.github.ajdailey.qlogg.core.io.ReopeningByteSource;
import io.github.ajdailey.qlogg.core.rotate.MemberIdentity;
import io.github.ajdailey.qlogg.core.watch.Baseline;
import io.github.ajdailey.qlogg.core.watch.ChangeDetector;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The head and tail digests of one open file's indexed bytes, and the check that classifies the
 * file at a path against them. {@link #record} runs after each indexing pass; {@link #classify}
 * runs on the watch's thread. The tail digest takes part in a check only when it ends at the
 * covered bytes the check is given: an indexing pass publishes its last snapshot before it records
 * the new tail, and a check in between would otherwise compare the new end of the file with the
 * previous pass's digest and report {@code MODIFIED}. A {@link ReopeningByteSource} receives each
 * record as the bytes its reads verify, and a read of it that found another file turns a quiet
 * check into {@code REPLACED}.
 */
final class FileCheck {
  private final ByteSource source;
  private volatile Tail tail = Tail.NONE;
  private volatile Head head;

  /**
   * Starts from the head digest the source took at open.
   *
   * @param source the open file
   */
  FileCheck(ByteSource source) {
    this.source = source;
    FileIdentity identity = source.identity();
    this.head =
        new Head((int) Math.min(identity.size(), FileIdentity.HEAD_BYTES), identity.headDigest());
  }

  /**
   * The indexed tail a {@code MODIFIED} check compares against.
   *
   * @param end the indexed bytes the digest ends at
   * @param modified the modification time taken before the digest, or -1 for none
   * @param digest CRC32C of the last bytes before {@code end}
   */
  private record Tail(long end, long modified, long digest) {
    static final Tail NONE = new Tail(-1, -1, 0);
  }

  /**
   * The head a check compares against.
   *
   * @param bytes the bytes from offset 0 the digest covers
   * @param digest CRC32C of those bytes
   */
  private record Head(int bytes, long digest) {}

  /**
   * Takes the modification time, then the digest of the indexed tail, and extends the head digest
   * over the indexed bytes until it covers {@link FileIdentity#HEAD_BYTES}. A file that holds fewer
   * than {@code end} bytes before or after the digests keeps the previous record: digests of a file
   * truncated after indexing describe neither the indexed bytes nor a copy of them. A read failure
   * clears the tail record. Blocking.
   *
   * @param path the file's current name, for its modification time
   * @param end the indexed bytes
   */
  void record(Path path, long end) {
    try {
      if (source.size() < end) {
        return;
      }
      long modified = Files.getLastModifiedTime(path).toMillis();
      Head next = head;
      if (next.bytes() < FileIdentity.HEAD_BYTES && end > next.bytes()) {
        int bytes = (int) Math.min(end, FileIdentity.HEAD_BYTES);
        next = new Head(bytes, ChangeDetector.headDigest(source, bytes));
      }
      long digest = ChangeDetector.tailDigest(source, end);
      if (source.size() < end) {
        return;
      }
      head = next;
      tail = new Tail(end, modified, digest);
      if (source instanceof ReopeningByteSource reopening) {
        reopening.expect(
            head.bytes(),
            head.digest(),
            end,
            (int) Math.min(end, ChangeDetector.TAIL_BYTES),
            tail.digest());
      }
    } catch (IOException e) {
      tail = Tail.NONE;
    }
  }

  /**
   * Classifies the file at a path. Blocking.
   *
   * @param path the path to check
   * @param covered the indexed bytes, or the end an indexing pass in progress covers
   * @return the observation
   * @throws IOException when the attributes or a digest cannot be read
   */
  ChangeDetector.Observation classify(Path path, long covered) throws IOException {
    Tail recorded = tail;
    if (recorded.end() != covered) {
      recorded = Tail.NONE;
    }
    Head start = head;
    Baseline baseline =
        new Baseline(
            source.identity(),
            covered,
            start.bytes(),
            start.digest(),
            recorded.modified(),
            recorded.digest());
    return withReadsSeen(ChangeDetector.classify(baseline, path, source));
  }

  /**
   * Returns the identity a rescan finds the file by, from the last record.
   *
   * @return the identity over the recorded end, or null before a record and while the head digest
   *     covers another region than the first {@link FileIdentity#HEAD_BYTES} of the recorded end
   */
  MemberIdentity identity() {
    Tail recorded = tail;
    Head start = head;
    if (recorded.end() < 0 || start.bytes() != Math.min(recorded.end(), FileIdentity.HEAD_BYTES)) {
      return null;
    }
    FileIdentity opened = source.identity();
    return new MemberIdentity(
        opened.fileKey(),
        opened.creationMillis(),
        recorded.end(),
        start.digest(),
        recorded.digest());
  }

  /** A read that found another file outweighs a check that found no difference. */
  private ChangeDetector.Observation withReadsSeen(ChangeDetector.Observation seen) {
    boolean quiet = seen.change() == FileChange.UNCHANGED || seen.change() == FileChange.APPENDED;
    if (quiet && source instanceof ReopeningByteSource reopening && reopening.changed()) {
      return new ChangeDetector.Observation(
          FileChange.REPLACED, seen.size(), seen.modifiedMillis());
    }
    return seen;
  }
}
