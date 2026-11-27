package io.github.ajdailey.qlogg.core.rotate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Identity across a rename chain. The digest cases drop the file key, as on Windows, so they run on
 * every lane.
 */
class MemberIdentityTest {
  @TempDir Path dir;

  @Test
  void theFileKeyFollowsARename() throws IOException {
    Path active = write("app.log", text('a', 100));
    MemberIdentity identity = MemberIdentity.read(active, 100);
    assumeTrue(identity.fileKey() != null, "no file key on this platform");
    rotate();
    MemberIdentity.Match match = identity.find(members());
    assertEquals(MemberIdentity.Outcome.FOUND, match.outcome());
    assertEquals(dir.resolve("app.log.1"), match.path());
  }

  @Test
  void withoutAFileKeyTheDigestsFollowARenameAndLaterGrowth() throws IOException {
    Path active = write("app.log", text('a', 10_000));
    write("app.log.1", text('b', 10_000));
    MemberIdentity active1 = withoutKey(MemberIdentity.read(active, 10_000));
    MemberIdentity older = withoutKey(MemberIdentity.read(dir.resolve("app.log.1"), 10_000));
    rotate();
    Files.write(dir.resolve("app.log.1"), text('c', 50), StandardOpenOption.APPEND);
    assertFound(active1, "app.log.1");
    assertFound(older, "app.log.2");
  }

  @Test
  void aChangedTailOrAShortFileDoesNotMatch() throws IOException {
    Path active = write("app.log", text('a', 10_000));
    MemberIdentity identity = withoutKey(MemberIdentity.read(active, 10_000));
    byte[] rewritten = text('a', 10_000);
    rewritten[9_000] = 'z';
    write("app.log", rewritten);
    assertEquals(MemberIdentity.Outcome.NONE, identity.find(members()).outcome());
    write("app.log", text('a', 9_999));
    assertEquals(MemberIdentity.Outcome.NONE, identity.find(members()).outcome());
  }

  @Test
  void equalCopiesAreAmbiguousUnlessCreationTimePicksOne() throws Exception {
    write("app.log.1", text('a', 500));
    // Separates the two creation times on file systems that keep milliseconds.
    Thread.sleep(20);
    write("app.log.2", text('a', 500));
    MemberIdentity read = MemberIdentity.read(dir.resolve("app.log.1"), 500);
    MemberIdentity unknownCreation =
        new MemberIdentity(null, -1, 500, read.headDigest(), read.tailDigest());
    MemberIdentity.Match match = unknownCreation.find(members());
    assertEquals(MemberIdentity.Outcome.AMBIGUOUS, match.outcome());
    assertNull(match.path());
    long created2 =
        Files.readAttributes(dir.resolve("app.log.2"), BasicFileAttributes.class)
            .creationTime()
            .toMillis();
    long created1 = read.creationMillis();
    assumeTrue(created1 != created2, "both copies have one creation time");
    MemberIdentity second =
        new MemberIdentity(null, created2, 500, read.headDigest(), read.tailDigest());
    assertFound(second, "app.log.2");
  }

  @Test
  void anEmptyIndexedRegionMatchesEveryFileWithoutAKey() throws IOException {
    write("app.log", new byte[0]);
    write("app.log.1", text('a', 10));
    MemberIdentity empty = withoutKey(MemberIdentity.read(dir.resolve("app.log"), 0));
    MemberIdentity creation =
        new MemberIdentity(null, -1, 0, empty.headDigest(), empty.tailDigest());
    assertEquals(MemberIdentity.Outcome.AMBIGUOUS, creation.find(members()).outcome());
  }

  /**
   * Renames {@code app.log.1} to {@code .2} and {@code app.log} to {@code .1}; a new empty base.
   */
  private void rotate() throws IOException {
    if (Files.exists(dir.resolve("app.log.1"))) {
      Files.move(dir.resolve("app.log.1"), dir.resolve("app.log.2"));
    }
    Files.move(dir.resolve("app.log"), dir.resolve("app.log.1"));
    write("app.log", new byte[0]);
  }

  private List<Path> members() throws IOException {
    List<Path> paths = new ArrayList<Path>();
    for (Member member :
        RotatedSet.discover(dir.resolve("app.log"), null, StandardCharsets.UTF_8, null, false)
            .members()) {
      paths.add(member.path());
    }
    return paths;
  }

  private void assertFound(MemberIdentity identity, String name) throws IOException {
    MemberIdentity.Match match = identity.find(members());
    assertEquals(MemberIdentity.Outcome.FOUND, match.outcome(), name);
    assertEquals(dir.resolve(name), match.path());
  }

  private static MemberIdentity withoutKey(MemberIdentity identity) {
    return new MemberIdentity(
        null,
        identity.creationMillis(),
        identity.indexedBytes(),
        identity.headDigest(),
        identity.tailDigest());
  }

  private Path write(String name, byte[] bytes) throws IOException {
    return Files.write(dir.resolve(name), bytes);
  }

  /** Lines of one letter with a running number, so no two 4 KiB windows are equal. */
  private static byte[] text(char letter, int bytes) {
    StringBuilder text = new StringBuilder();
    for (int line = 0; text.length() < bytes; line++) {
      text.append(letter).append(line).append('\n');
    }
    return text.substring(0, bytes).getBytes(StandardCharsets.US_ASCII);
  }
}
