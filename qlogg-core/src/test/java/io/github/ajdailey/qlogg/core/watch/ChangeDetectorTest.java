package io.github.ajdailey.qlogg.core.watch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.core.io.FileByteSource;
import io.github.ajdailey.qlogg.core.io.FileIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangeDetectorTest {
  @TempDir Path dir;

  /** Opens the file and takes a baseline covering its whole content. */
  private static Baseline baseline(Path file) throws IOException {
    try (FileByteSource source = FileByteSource.open(file)) {
      long size = source.size();
      return new Baseline(
          source.identity(),
          size,
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
