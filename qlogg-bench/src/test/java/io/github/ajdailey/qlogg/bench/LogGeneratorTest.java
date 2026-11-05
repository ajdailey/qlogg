package io.github.ajdailey.qlogg.bench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Generated files: deterministic by seed, with each profile's shape and match densities. */
class LogGeneratorTest {
  private static final long BYTES = 2_000_000;

  @TempDir Path temp;

  @Test
  void theSameSeedWritesTheSameBytes() throws IOException {
    assertArrayEquals(bytes("ascii-short", 5), bytes("ascii-short", 5));
    assertFalse(Arrays.equals(bytes("ascii-short", 5), bytes("ascii-short", 6)));
  }

  @Test
  void asciiShortHasBothTokenDensities() throws IOException {
    String[] lines = text("ascii-short", StandardCharsets.US_ASCII).split("\n");
    int rare = 0;
    int common = 0;
    long length = 0;
    for (String line : lines) {
      rare += line.contains(LogGenerator.RARE_TOKEN) ? 1 : 0;
      common += line.contains(LogGenerator.COMMON_TOKEN) ? 1 : 0;
      length += line.length() + 1;
    }
    double average = (double) length / lines.length;
    assertTrue(average > 75 && average < 95, "average " + average);
    assertTrue(Math.abs(rare / (double) lines.length - 0.001) < 0.0005, "rare " + rare);
    assertTrue(Math.abs(common / (double) lines.length - 0.1) < 0.01, "common " + common);
  }

  @Test
  void tinyLinesAreTenBytes() throws IOException {
    byte[] content = bytes("tiny", 1);
    assertEquals(BYTES, content.length);
    int common = 0;
    for (int at = 0; at < content.length; at += 10) {
      assertEquals('\n', content[at + 9]);
      common += new String(content, at, 4, StandardCharsets.US_ASCII).equals("blkt") ? 1 : 0;
    }
    double share = common / (BYTES / 10.0);
    assertTrue(Math.abs(share - 0.01) < 0.002, "blkt " + share);
  }

  @Test
  void utf16leStartsWithItsByteOrderMark() throws IOException {
    byte[] content = bytes("utf16le", 1);
    assertEquals((byte) 0xff, content[0]);
    assertEquals((byte) 0xfe, content[1]);
    assertEquals(0, content.length % 2);
    String text = new String(content, 2, content.length - 2, StandardCharsets.UTF_16LE);
    assertTrue(text.startsWith("2026-11-01 "), text.substring(0, 20));
  }

  @Test
  void noFinalNewlineEndsWithText() throws IOException {
    byte[] content = bytes("no-final-newline", 1);
    assertFalse(content[content.length - 1] == '\n');
    assertTrue(content.length >= BYTES - 200);
  }

  @Test
  void utf8MixedCarriesNonAsciiLines() throws IOException {
    String[] lines = text("utf8-mixed", StandardCharsets.UTF_8).split("\n");
    int nonAscii = 0;
    for (String line : lines) {
      for (int i = 0; i < line.length(); i++) {
        if (line.charAt(i) > 127) {
          nonAscii++;
          break;
        }
      }
      assertTrue(line.length() <= 2010, "length " + line.length());
    }
    assertTrue(nonAscii > 0 && nonAscii < lines.length / 10, "non-ASCII lines " + nonAscii);
  }

  @Test
  void timedStampsAreInOrderWithStackTraces() throws IOException {
    String[] lines = text("timed", StandardCharsets.US_ASCII).split("\n");
    String previous = "";
    int traces = 0;
    for (String line : lines) {
      if (line.startsWith("\tat ")) {
        traces++;
        continue;
      }
      String stamp = line.substring(0, 23);
      assertTrue(stamp.compareTo(previous) >= 0, stamp + " after " + previous);
      assertTrue(stamp.matches("2026-11-01 \\d\\d:\\d\\d:\\d\\d\\.\\d{3}"), stamp);
      previous = stamp;
    }
    double share = traces / (double) lines.length;
    assertTrue(share > 0.1 && share < 0.2, "trace share " + share);
  }

  private String text(String profile, Charset charset) throws IOException {
    return new String(bytes(profile, 1), charset);
  }

  private byte[] bytes(String profile, long seed) throws IOException {
    Path file = Files.createTempFile(temp, profile, ".log");
    Files.delete(file);
    new LogGenerator(LogGenerator.Profile.of(profile), seed).write(file, BYTES);
    return Files.readAllBytes(file);
  }
}
