package io.github.ajdailey.qlogg.core.watch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.core.io.FileByteSource;
import io.github.ajdailey.qlogg.core.io.FileIdentity;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangeDetectorTest {
  @TempDir Path dir;

  /** Opens the file and takes a baseline covering its whole content. */
  private static Baseline baseline(Path file) throws IOException {
    try (FileByteSource source = FileByteSource.open(file)) {
      long size = source.size();
      int head = (int) Math.min(size, FileIdentity.HEAD_BYTES);
      return new Baseline(
          source.identity(),
          size,
          head,
          ChangeDetector.headDigest(source, head),
          Files.getLastModifiedTime(file).toMillis(),
          ChangeDetector.tailDigest(source, size));
    }
  }

  private Path write(String name, String text) throws IOException {
    return Files.writeString(dir.resolve(name), text, StandardCharsets.US_ASCII);
  }

  private static FileChange classify(Baseline baseline, Path file) throws IOException {
    return ChangeDetector.classify(baseline, file).change();
  }

  private Path linkTo(Path target, String name) throws IOException {
    try {
      return Files.createSymbolicLink(dir.resolve(name), target);
    } catch (FileSystemException e) {
      assumeTrue(
          File.separatorChar == '/',
          "creating a symbolic link on Windows needs SeCreateSymbolicLinkPrivilege");
      throw e;
    }
  }

  /** Gives the second file the first one's creation time where the platform can set it. */
  private static void copyCreationTime(Path from, Path to) throws IOException {
    FileTime created = Files.readAttributes(from, BasicFileAttributes.class).creationTime();
    Files.getFileAttributeView(to, BasicFileAttributeView.class).setTimes(null, null, created);
  }

  /**
   * Takes the file key away, the state of every baseline on Windows, and takes the creation time of
   * the file now at the path, so that only the real path and the digests can differ.
   */
  private static Baseline withoutFileKey(Baseline baseline, Path file) throws IOException {
    FileIdentity opened = baseline.identity();
    FileIdentity identity =
        new FileIdentity(
            null,
            Files.readAttributes(file, BasicFileAttributes.class).creationTime().toMillis(),
            opened.size(),
            opened.modifiedMillis(),
            opened.headDigest(),
            opened.realPath());
    return new Baseline(
        identity,
        baseline.coveredBytes(),
        baseline.headBytes(),
        baseline.headDigest(),
        baseline.tailModifiedMillis(),
        baseline.tailDigest());
  }

  @Test
  void growthIsAppendedAndNothingIsUnchanged() throws Exception {
    Path file = write("grow.log", "one\ntwo\n");
    Baseline baseline = baseline(file);
    assertEquals(FileChange.UNCHANGED, classify(baseline, file));
    Files.writeString(file, "three\n", StandardOpenOption.APPEND);
    ChangeDetector.Observation seen = ChangeDetector.classify(baseline, file);
    assertEquals(FileChange.APPENDED, seen.change());
    assertEquals(14, seen.size());
  }

  @Test
  void shorterIsTruncated() throws Exception {
    Path file = write("cut.log", "one\ntwo\nthree\n");
    Baseline baseline = baseline(file);
    Files.writeString(file, "one\n");
    assertEquals(FileChange.TRUNCATED, classify(baseline, file));
  }

  @Test
  void anotherFileUnderTheNameIsReplaced() throws Exception {
    Path file = write("rotate.log", "old one\nold two\n");
    Baseline baseline = baseline(file);
    Files.move(file, dir.resolve("rotate.log.1"));
    write("rotate.log", "new one\nnew two\nnew three\n");
    assertEquals(FileChange.REPLACED, classify(baseline, file));
  }

  @Test
  void aRewrittenHeadIsReplacedEvenWhenLonger() throws Exception {
    Path file = write("rewrite.log", "aaaa\n");
    Baseline baseline = baseline(file);
    Files.writeString(file, "bbbb\nmore\n", StandardOpenOption.TRUNCATE_EXISTING);
    assertEquals(FileChange.REPLACED, classify(baseline, file));
  }

  /**
   * Regression: a file opened empty, then written, truncated and regrown longer between two checks
   * kept its file key and had no head bytes recorded at open, and was reported APPENDED.
   */
  @Test
  void truncateThenRegrowOfAFileOpenedEmptyIsReplaced() throws Exception {
    Path file = write("regrow.log", "");
    FileIdentity opened;
    try (FileByteSource source = FileByteSource.open(file)) {
      opened = source.identity();
    }
    Files.writeString(file, "old line one\nold line two\n".repeat(4), StandardCharsets.US_ASCII);
    Baseline indexed = baseline(file);
    Baseline baseline =
        new Baseline(
            opened,
            indexed.coveredBytes(),
            0,
            opened.headDigest(),
            indexed.tailModifiedMillis(),
            indexed.tailDigest());
    Files.writeString(file, "", StandardOpenOption.TRUNCATE_EXISTING);
    Files.writeString(file, "NEW CONTENT AFTER ROTATION\n".repeat(8), StandardOpenOption.APPEND);
    assertEquals(FileChange.REPLACED, classify(baseline, file));
  }

  /** Regression: a rewrite past an unchanged 4 KiB head that left the file longer was APPENDED. */
  @Test
  void aLongerRewriteKeepingTheHeadIsReplaced() throws Exception {
    String header = "H".repeat(5000) + "\n";
    Path file = write("body.log", header + "first body line\n");
    Baseline baseline = baseline(file);
    Files.writeString(
        file,
        header + "REWRITTEN BODY LINE, longer than before\n",
        StandardOpenOption.TRUNCATE_EXISTING);
    assertEquals(FileChange.REPLACED, classify(baseline, file));
  }

  /** The head digest covers a file under 4 KiB whole, so the edit lies past it. */
  @Test
  void sameSizeWithAnotherTailIsModified() throws Exception {
    String head = "h".repeat(FileIdentity.HEAD_BYTES) + "\n";
    Path file = write("edit.log", head + "line two\n");
    Baseline baseline = baseline(file);
    Files.writeString(file, head + "line 2!!\n", StandardOpenOption.TRUNCATE_EXISTING);
    Files.setLastModifiedTime(file, FileTime.fromMillis(baseline.tailModifiedMillis() + 5000));
    assertEquals(FileChange.MODIFIED, classify(baseline, file));
  }

  @Test
  void aTouchWithTheSameBytesIsUnchanged() throws Exception {
    Path file = write("touch.log", "same\n");
    Baseline baseline = baseline(file);
    Files.setLastModifiedTime(file, FileTime.fromMillis(baseline.tailModifiedMillis() + 5000));
    assertEquals(FileChange.UNCHANGED, classify(baseline, file));
  }

  /**
   * Regression: without a file key, a link retargeted to a file with the same creation millisecond
   * that starts with the old bytes was APPENDED, and the tab kept the old file's lines.
   */
  @Test
  void aRetargetWithoutAFileKeyIsReplaced() throws Exception {
    String lines = "mock line\n".repeat(10);
    Path first = write("one.log", lines);
    Path second = write("two.log", lines + "mock next line\n".repeat(10));
    Path link = linkTo(first, "current.log");
    Baseline baseline = baseline(link);
    Files.delete(link);
    linkTo(second, "current.log");
    copyCreationTime(first, second);
    assertEquals(FileChange.REPLACED, classify(withoutFileKey(baseline, link), link));
  }

  @Test
  void growthThroughALinkWithoutAFileKeyIsAppended() throws Exception {
    Path target = write("target.log", "one\n");
    Path link = linkTo(target, "current.log");
    Baseline baseline = baseline(link);
    Files.writeString(target, "two\n", StandardOpenOption.APPEND);
    assertEquals(FileChange.APPENDED, classify(withoutFileKey(baseline, link), link));
  }

  /** The known gap: nothing tells these two files apart without a file key. */
  @Test
  void aPlainFileReplacedWithTheSameStartAndCreationTimeWithoutAFileKeyIsAppended()
      throws Exception {
    Path file = write("plain.log", "one\n");
    Baseline baseline = baseline(file);
    Files.delete(file);
    write("plain.log", "one\ntwo\n");
    assertEquals(FileChange.APPENDED, classify(withoutFileKey(baseline, file), file));
  }

  @Test
  void aRealPathThatDoesNotResolveIsMissing() throws Exception {
    Path file = write("denied.log", "one\n");
    Baseline baseline = withoutFileKey(baseline(file), file);
    ChangeDetector.Observation seen =
        ChangeDetector.classify(
            baseline,
            file,
            new ChangeDetector.Resolver() {
              @Override
              public Path realPath(Path path) throws IOException {
                throw new AccessDeniedException(path.toString());
              }
            });
    assertEquals(new ChangeDetector.Observation(FileChange.MISSING, -1, -1), seen);
  }

  @Test
  void aFileKeyLeavesTheRealPathUnresolved() throws Exception {
    Path file = write("keyed.log", "one\n");
    Baseline baseline = baseline(file);
    assumeTrue(baseline.identity().fileKey() != null, "the platform has no file key");
    ChangeDetector.Observation seen =
        ChangeDetector.classify(
            baseline,
            file,
            new ChangeDetector.Resolver() {
              @Override
              public Path realPath(Path path) {
                throw new AssertionError("resolved " + path);
              }
            });
    assertEquals(FileChange.UNCHANGED, seen.change());
  }

  @Test
  void noFileOrADirectoryIsMissing() throws Exception {
    Path file = write("gone.log", "x\n");
    Baseline baseline = baseline(file);
    Files.delete(file);
    ChangeDetector.Observation seen = ChangeDetector.classify(baseline, file);
    assertEquals(FileChange.MISSING, seen.change());
    assertEquals(-1, seen.size());
    Files.createDirectory(file);
    assertEquals(FileChange.MISSING, classify(baseline, file));
  }
}
