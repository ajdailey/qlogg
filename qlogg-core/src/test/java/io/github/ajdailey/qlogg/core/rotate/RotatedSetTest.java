package io.github.ajdailey.qlogg.core.rotate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.text.DetectionReason;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RotatedSetTest {
  private static final long HOUR = 3_600_000L;
  private static final long EPOCH = 1_790_000_000_000L;

  @TempDir Path dir;

  @ParameterizedTest
  @CsvSource({
    "app.log, app.log",
    "app.log.1, app.log",
    "app.log.12, app.log",
    "app.1.log, app.log",
    "app-2026-11-25.log, app.log",
    "app.2026-11-25.0.log, app.log",
    "app.log-20261125, app.log",
    "app.log.2026-11-25_13, app.log",
    "app.log.2.gz, app.log",
    "app.1.log.zip, app.log",
    "app.log.bak, app.log.bak",
    "server, server"
  })
  void theBaseNameDropsTheTokenAndCompression(String name, String base) {
    assertEquals(base, MemberNames.baseOf(name));
  }

  @ParameterizedTest
  @CsvSource({
    "app.log, , false",
    "app.log.1, 1, false",
    "app.log-20261125, 20261125, false",
    "app.log.2026-11-25_13, 2026-11-25_13, false",
    "app.1.log, 1, false",
    "app-2026-11-25.log, 2026-11-25, false",
    "app.2026-11-25.0.log, 2026-11-25.0, false",
    "app.log.2.gz, 2, true",
    "app.1.log.zip, 1, true",
    "app.log.3.bz2, 3, true",
    "app.log.3.xz, 3, true",
    "app.log.3.zst, 3, true",
    "app.log.3.Z, 3, true"
  })
  void membersMatchWithTheirToken(String name, String token, boolean compressed) {
    MemberNames.Match match = MemberNames.of("app.log").match(name);
    assertEquals(token, match.token());
    assertEquals(compressed, match.compressed());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"app.log.bak", "app.logger", "app2.log", "app.log.1.tmp", "app.log.", "other.log"})
  void otherNamesAreNotMembers(String name) {
    assertNull(MemberNames.of("app.log").match(name));
  }

  @Test
  void tokensCompareAsNumbers() {
    assertTrue(RotatedSet.compareTokens("10", "2") > 0);
    assertEquals(0, RotatedSet.compareTokens("007", "7"));
    assertTrue(RotatedSet.compareTokens("2026-11-25.1", "2026-11-25") > 0);
    assertTrue(RotatedSet.compareTokens("2026-11-25", "2026-12-01") < 0);
    assertTrue(RotatedSet.compareTokens(null, "1") < 0);
  }

  @Test
  void membersRunOldestFirstWithTheBaseLast() throws IOException {
    write("app.log", "active\n", 1);
    write("app.log.1", "one\n", 3);
    write("app.log.2", "two\n", 2);
    write("app.log.3", "three\n", 2);
    write("app.log.bak", "no\n", 0);
    write("app2.log", "no\n", 0);
    RotatedSet set = discover("app.log.2");
    assertEquals(dir.resolve("app.log"), set.base());
    assertEquals(List.of("app.log.3", "app.log.2", "app.log.1", "app.log"), names(set.members()));
    assertEquals("3", set.members().get(0).token());
    assertNull(set.members().get(3).token());
    assertTrue(set.leftOut().isEmpty());
  }

  @Test
  void withoutABaseFileTheNewestMemberIsLast() throws IOException {
    write("app-2026-11-24.log", "a\n", 1);
    write("app-2026-11-26.log", "c\n", 3);
    write("app-2026-11-25.log", "b\n", 2);
    RotatedSet set = discover("app-2026-11-25.log");
    assertEquals(dir.resolve("app.log"), set.base());
    assertEquals(
        List.of("app-2026-11-24.log", "app-2026-11-25.log", "app-2026-11-26.log"),
        names(set.members()));
  }

  @Test
  void compressedMembersAreListedAndLeftOut() throws IOException {
    write("app.log", "a\n", 3);
    write("app.log.1", "b\n", 2);
    write("app.log.2.gz", "zz", 1);
    RotatedSet set = discover("app.log");
    assertEquals(List.of("app.log.1", "app.log"), names(set.members()));
    assertEquals(1, set.leftOut().size());
    assertEquals(dir.resolve("app.log.2.gz"), set.leftOut().get(0).path());
    assertEquals(LeftOut.Reason.COMPRESSED, set.leftOut().get(0).reason());
  }

  @Test
  void theOldestPastTheCapAreLeftOut() throws IOException {
    int extra = 5;
    for (int i = 1; i <= RotatedSet.MAX_MEMBERS + extra; i++) {
      write("app.log." + i, "line " + i + "\n", 1000 - i);
    }
    RotatedSet set = discover("app.log.1");
    assertEquals(RotatedSet.MAX_MEMBERS, set.members().size());
    assertEquals("100", set.members().get(0).token());
    assertEquals("1", set.members().get(RotatedSet.MAX_MEMBERS - 1).token());
    assertEquals(extra, set.leftOut().size());
    for (int i = 0; i < extra; i++) {
      assertEquals(LeftOut.Reason.OVER_CAP, set.leftOut().get(i).reason());
      assertEquals(dir.resolve("app.log." + (105 - i)), set.leftOut().get(i).path());
    }
  }

  @Test
  void aMemberWithAnotherLineFeedWidthOrByteOrderIsLeftOut() throws IOException {
    byte[] le = {(byte) 0xff, (byte) 0xfe, 'x', 0, '\n', 0};
    byte[] be = {(byte) 0xfe, (byte) 0xff, 0, 'x', 0, '\n'};
    byte[] utf8 = "x\n".getBytes(StandardCharsets.UTF_8);
    writeBytes("app.log", le, 4);
    writeBytes("app.log.1", le, 3);
    writeBytes("app.log.2", be, 2);
    writeBytes("app.log.3", utf8, 1);
    writeBytes("app.log.4", new byte[0], 0);
    RotatedSet set = discover("app.log");
    assertEquals(StandardCharsets.UTF_16LE, set.encoding().charset());
    assertEquals(0, set.encoding().bomLength());
    assertEquals(DetectionReason.BOM, set.detection().reason());
    assertEquals(List.of("app.log.4", "app.log.1", "app.log"), names(set.members()));
    assertEquals(0, set.members().get(0).encoding().bomLength());
    assertEquals(2, set.members().get(1).encoding().bomLength());
    assertEquals(2, set.leftOut().size());
    assertEquals(dir.resolve("app.log.3"), set.leftOut().get(0).path());
    assertEquals(LeftOut.Reason.ENCODING, set.leftOut().get(0).reason());
    assertEquals(dir.resolve("app.log.2"), set.leftOut().get(1).path());
  }

  @Test
  void theNewestMemberWithBytesSetsTheEncodingAndOneByteMembersKeepTheirMarks() throws IOException {
    byte[] utf8Bom = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'a', '\n'};
    byte[] latin = {'b', (byte) 0xe9, '\n'};
    writeBytes("app.log", new byte[0], 3);
    writeBytes("app.log.1", utf8Bom, 2);
    writeBytes("app.log.2", latin, 1);
    RotatedSet set = discover("app.log");
    assertEquals(StandardCharsets.UTF_8, set.encoding().charset());
    assertEquals(List.of("app.log.2", "app.log.1", "app.log"), names(set.members()));
    assertEquals(0, set.members().get(0).encoding().bomLength());
    assertEquals(3, set.members().get(1).encoding().bomLength());
    assertEquals(StandardCharsets.UTF_8, set.members().get(0).encoding().charset());
  }

  @Test
  void aForcedCharsetAppliesToEveryMember() throws IOException {
    writeBytes("app.log", "a\n".getBytes(StandardCharsets.UTF_8), 2);
    writeBytes("app.log.1", new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'b', '\n'}, 1);
    RotatedSet set =
        RotatedSet.discover(
            dir.resolve("app.log"), null, StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1);
    assertEquals(StandardCharsets.ISO_8859_1, set.encoding().charset());
    assertEquals(DetectionReason.FORCED, set.detection().reason());
    assertEquals(2, set.members().size());
    assertEquals(0, set.members().get(0).encoding().bomLength());
  }

  @Test
  void aMissingBaseWithNoMembersGivesAnEmptySet() throws IOException {
    RotatedSet set = discover("app.log");
    assertTrue(set.members().isEmpty());
    assertEquals(dir.resolve("app.log"), set.base());
  }

  @Test
  void aDirectoryWithAMemberNameIsNotAMember() throws IOException {
    write("app.log", "a\n", 2);
    Files.createDirectory(dir.resolve("app.log.1"));
    assertEquals(List.of("app.log"), names(discover("app.log").members()));
  }

  private RotatedSet discover(String name) throws IOException {
    return RotatedSet.discover(dir.resolve(name), null, StandardCharsets.ISO_8859_1, null);
  }

  private void write(String name, String text, int hours) throws IOException {
    writeBytes(name, text.getBytes(StandardCharsets.US_ASCII), hours);
  }

  private void writeBytes(String name, byte[] bytes, int hours) throws IOException {
    Path file = Files.write(dir.resolve(name), bytes);
    Files.setLastModifiedTime(file, FileTime.fromMillis(EPOCH + hours * HOUR));
  }

  private static List<String> names(List<Member> members) {
    List<String> names = new ArrayList<String>();
    for (Member member : members) {
      names.add(member.path().getFileName().toString());
    }
    return names;
  }
}
