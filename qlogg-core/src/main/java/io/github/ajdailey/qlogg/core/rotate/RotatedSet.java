package io.github.ajdailey.qlogg.core.rotate;

import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.text.EncodingDetection;
import io.github.ajdailey.qlogg.core.text.EncodingDetector;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The files of one rotation series in one directory, oldest first, as {@link #discover} found them.
 *
 * @param base the active file's path; it need not exist
 * @param members the files the set loads, oldest first; the base file, when it exists, is last
 * @param leftOut files of the series the set does not load, in the same order
 * @param detection the set's encoding, with byte order mark length 0, and the rule that chose it
 */
public record RotatedSet(
    Path base, List<Member> members, List<LeftOut> leftOut, EncodingDetection detection) {
  /** Most files a set loads; older files are left out. */
  public static final int MAX_MEMBERS = 100;

  private record Candidate(
      Path path, String token, boolean compressed, long modifiedMillis, long size) {
    boolean active() {
      return token == null && !compressed;
    }
  }

  /**
   * Base file last; the others oldest first, equal times by token read as numbers, higher first.
   */
  private static final Comparator<Candidate> ORDER =
      new Comparator<Candidate>() {
        @Override
        public int compare(Candidate a, Candidate b) {
          if (a.active() != b.active()) {
            return a.active() ? 1 : -1;
          }
          int byTime = Long.compare(a.modifiedMillis(), b.modifiedMillis());
          if (byTime != 0) {
            return byTime;
          }
          return compareTokens(b.token(), a.token());
        }
      };

  /** Copies the lists. */
  public RotatedSet {
    members = List.copyOf(members);
    leftOut = List.copyOf(leftOut);
  }

  /**
   * Returns the set's encoding.
   *
   * @return the charset, unit width and byte order shared by every member; byte order mark length 0
   */
  public TextEncoding encoding() {
    return detection.encoding();
  }

  /**
   * Lists the series a file belongs to. Lists the file's directory once, not recursively; stats
   * each file and reads the first {@link EncodingDetector#SAMPLE_BYTES} of each member. Members are
   * regular files that pass {@link SafePaths#logPath}; a file that vanishes during the listing is
   * skipped. The set's encoding is the forced charset, or detected from the newest member with
   * bytes. An empty member is kept; a member whose line feed differs in width or byte order is left
   * out. Blocks; call it on a worker.
   *
   * @param given any member's path, or the base path
   * @param held the lock file the process holds, or null
   * @param fallback the charset detection falls back to
   * @param forced the charset the user chose, or null to detect
   * @param ignoreCase true where the file system compares names without regard to case
   * @return the set
   * @throws IOException when the path is rejected or the directory cannot be listed
   */
  public static RotatedSet discover(
      Path given, SafePaths.HeldLock held, Charset fallback, Charset forced, boolean ignoreCase)
      throws IOException {
    return assemble(given, held, fallback, forced, ignoreCase, null);
  }

  /**
   * Lists the series again as {@link #discover} does, keeping an existing set's encoding: a member
   * whose line feed differs from it is left out, whichever member is newest.
   *
   * @param given any member's path, or the base path
   * @param held the lock file the process holds, or null
   * @param fallback the charset detection falls back to
   * @param forced the charset the user chose, or null
   * @param ignoreCase true where the file system compares names without regard to case
   * @param keep the set's detection, byte order mark length 0
   * @return the set, with {@code keep} as its detection
   * @throws IOException when the path is rejected or the directory cannot be listed
   */
  public static RotatedSet rescan(
      Path given,
      SafePaths.HeldLock held,
      Charset fallback,
      Charset forced,
      boolean ignoreCase,
      EncodingDetection keep)
      throws IOException {
    return assemble(given, held, fallback, forced, ignoreCase, keep);
  }

  private static RotatedSet assemble(
      Path given,
      SafePaths.HeldLock held,
      Charset fallback,
      Charset forced,
      boolean ignoreCase,
      EncodingDetection keep)
      throws IOException {
    Path absolute = SafePaths.logPath(given, held).given();
    Path directory = absolute.getParent();
    Path name = absolute.getFileName();
    if (directory == null || name == null) {
      throw new IOException(absolute + " names no file in a directory");
    }
    MemberNames names = MemberNames.of(name.toString(), ignoreCase);
    List<Candidate> found = list(directory, names, held);
    found.sort(ORDER);
    List<LeftOut> leftOut = new ArrayList<LeftOut>();
    List<Candidate> loadable = new ArrayList<Candidate>();
    for (Candidate candidate : found) {
      if (candidate.compressed()) {
        leftOut.add(new LeftOut(candidate.path(), LeftOut.Reason.COMPRESSED));
      } else {
        loadable.add(candidate);
      }
    }
    int over = Math.max(0, loadable.size() - MAX_MEMBERS);
    for (int i = 0; i < over; i++) {
      leftOut.add(new LeftOut(loadable.get(i).path(), LeftOut.Reason.OVER_CAP));
    }
    loadable = loadable.subList(over, loadable.size());
    ByteBuffer head = ByteBuffer.allocate(EncodingDetector.SAMPLE_BYTES);
    EncodingDetection detected = keep;
    for (int i = loadable.size() - 1; i >= 0 && detected == null; i--) {
      if (loadable.get(i).size() > 0 && readHead(loadable.get(i).path(), head)) {
        detected = detect(head, fallback, forced);
      }
    }
    if (detected == null) {
      head.clear().flip();
      detected = detect(head, fallback, forced);
    }
    TextEncoding set = detected.encoding();
    TextEncoding plain = new TextEncoding(set.charset(), set.unitWidth(), set.order(), 0);
    List<Member> members = new ArrayList<Member>();
    for (Candidate candidate : loadable) {
      int bom = 0;
      if (candidate.size() > 0) {
        if (!readHead(candidate.path(), head)) {
          continue;
        }
        TextEncoding own = detect(head, fallback, forced).encoding();
        if (own.unitWidth() != set.unitWidth()
            || (own.unitWidth() > 1 && !own.order().equals(set.order()))) {
          leftOut.add(new LeftOut(candidate.path(), LeftOut.Reason.ENCODING));
          continue;
        }
        bom = own.bomLength();
      }
      members.add(
          new Member(
              candidate.path(),
              candidate.token(),
              candidate.modifiedMillis(),
              candidate.size(),
              new TextEncoding(set.charset(), set.unitWidth(), set.order(), bom)));
    }
    return new RotatedSet(
        directory.resolve(names.base()),
        members,
        leftOut,
        new EncodingDetection(plain, detected.reason()));
  }

  /**
   * Compares two rotation tokens as sequences of numbers: {@code 2} after {@code 10} is false,
   * {@code 2026-11-25.1} after {@code 2026-11-25} is true. Null sorts first.
   *
   * @param a a token or null
   * @param b a token or null
   * @return negative, zero or positive as a is lower, equal or higher
   */
  static int compareTokens(String a, String b) {
    if (a == null || b == null) {
      return a == null ? (b == null ? 0 : -1) : 1;
    }
    String[] left = a.split("[._-]");
    String[] right = b.split("[._-]");
    for (int i = 0; i < Math.min(left.length, right.length); i++) {
      int byNumber = compareNumbers(left[i], right[i]);
      if (byNumber != 0) {
        return byNumber;
      }
    }
    return Integer.compare(left.length, right.length);
  }

  private static int compareNumbers(String a, String b) {
    String left = stripZeros(a);
    String right = stripZeros(b);
    if (left.length() != right.length()) {
      return Integer.compare(left.length(), right.length());
    }
    return left.compareTo(right);
  }

  private static String stripZeros(String digits) {
    int at = 0;
    while (at < digits.length() - 1 && digits.charAt(at) == '0') {
      at++;
    }
    return digits.substring(at);
  }

  private static List<Candidate> list(Path directory, MemberNames names, SafePaths.HeldLock held)
      throws IOException {
    List<Candidate> found = new ArrayList<Candidate>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
      for (Path entry : entries) {
        Path name = entry.getFileName();
        MemberNames.Match match = name == null ? null : names.match(name.toString());
        if (match == null) {
          continue;
        }
        try {
          Path real = SafePaths.logPath(entry, held).real();
          BasicFileAttributes attributes = Files.readAttributes(real, BasicFileAttributes.class);
          if (attributes.isRegularFile()) {
            found.add(
                new Candidate(
                    entry,
                    match.token(),
                    match.compressed(),
                    attributes.lastModifiedTime().toMillis(),
                    attributes.size()));
          }
        } catch (IOException e) {
          // Rejected, unreadable or gone since the listing: not a member.
        }
      }
    }
    return found;
  }

  /** Reads a file's head into the buffer, flipped; false when the file cannot be read. */
  private static boolean readHead(Path file, ByteBuffer head) {
    head.clear();
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      while (head.hasRemaining()) {
        if (channel.read(head, head.position()) < 0) {
          break;
        }
      }
    } catch (IOException e) {
      return false;
    }
    head.flip();
    return true;
  }

  private static EncodingDetection detect(ByteBuffer head, Charset fallback, Charset forced) {
    return forced == null
        ? EncodingDetector.detect(head, fallback)
        : EncodingDetector.forced(forced, head);
  }
}
