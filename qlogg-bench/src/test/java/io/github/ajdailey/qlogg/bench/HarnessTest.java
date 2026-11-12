package io.github.ajdailey.qlogg.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The feature commands' output over small generated files. */
class HarnessTest {
  private static final long BYTES = 2_000_000;
  private static final String SEARCH = "literal:" + LogGenerator.COMMON_TOKEN;

  @TempDir Path temp;

  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

  private Harness harness() {
    return new Harness(new PrintStream(bytes, true, StandardCharsets.UTF_8));
  }

  private Path generated(LogGenerator.Profile profile, String name) throws IOException {
    Path file = temp.resolve(name);
    new LogGenerator(profile, 1).write(file, BYTES);
    return file;
  }

  /** Returns the value of a key's last line, or null. */
  private String value(String key) {
    String found = null;
    for (String line : bytes.toString(StandardCharsets.UTF_8).split("\\R")) {
      if (line.startsWith(key + "=")) {
        found = line.substring(key.length() + 1);
      }
    }
    return found;
  }

  private long number(String key) {
    return Long.parseLong(value(key));
  }

  @Test
  void multiCountsTheSameMatchesInTurnAndAtOnce() throws Exception {
    Path one = generated(LogGenerator.Profile.ASCII_SHORT, "one.log");
    Path two = generated(LogGenerator.Profile.TIMED, "two.log");
    Harness harness = harness();
    try {
      harness.multi(SEARCH, List.of(one, two));
    } finally {
      harness.close();
    }
    assertEquals(2, number("multi.files"));
    assertTrue(number("multi.file0.matches") > 0);
    assertEquals(
        number("multi.file0.matches") + number("multi.file1.matches"), number("multi.matches"));
    assertEquals(number("search[" + SEARCH + "].matches"), number("multi.file1.matches"));
    assertTrue(value("multi.file1.outcome").startsWith("COMPLETED"));
  }

  @Test
  void folderCountsTheSameMatchesAsASearchOfEachFile() throws Exception {
    Path dir = Files.createDirectory(temp.resolve("logs"));
    Path one = dir.resolve("one.log");
    new LogGenerator(LogGenerator.Profile.ASCII_SHORT, 1).write(one, BYTES);
    new LogGenerator(LogGenerator.Profile.ASCII_SHORT, 2).write(dir.resolve("one.log.1"), BYTES);
    Harness harness = harness();
    try {
      harness.multi(SEARCH, List.of(one, dir.resolve("one.log.1")));
      harness.folder(SEARCH, dir, "*.log*", false);
    } finally {
      harness.close();
    }
    assertEquals(2, number("folder.files"));
    assertTrue(value("folder.outcome").startsWith("COMPLETED"));
    assertEquals(number("multi.matches"), number("folder.matches"));
    assertEquals(number("folder.matches"), number("folder.listed"));
  }

  @Test
  void filteredListsContextMatchesAndMarks() throws Exception {
    Path file = generated(LogGenerator.Profile.ASCII_SHORT, "filtered.log");
    Harness harness = harness();
    try {
      harness.filtered(file, "literal:" + LogGenerator.RARE_TOKEN, 2, 1);
    } finally {
      harness.close();
    }
    long matches = number("search[literal:" + LogGenerator.RARE_TOKEN + "].matches");
    assertTrue(matches > 0);
    assertEquals("true", value("filtered.context.complete"));
    assertEquals("true", value("filtered.extras.complete"));
    assertTrue(number("filtered.extras.lines") > 0);
    assertTrue(number("filtered.rows") > matches + number("filtered.extras.lines"));
    assertTrue(value("filtered.read.p99.us") != null);
  }

  @Test
  void filteredWithoutContextListsMatchesAndMarks() throws Exception {
    Path file = generated(LogGenerator.Profile.ASCII_SHORT, "plain.log");
    Harness harness = harness();
    try {
      harness.filtered(file, SEARCH, 0, 0);
    } finally {
      harness.close();
    }
    assertEquals(
        number("search[" + SEARCH + "].matches") + number("filtered.extras.lines"),
        number("filtered.rows"));
  }

  @Test
  void timeRunsEveryLookupOnATimedFile() throws Exception {
    Path file = generated(LogGenerator.Profile.TIMED, "timed.log");
    Harness harness = harness();
    try {
      harness.time(file, 50);
    } finally {
      harness.close();
    }
    assertEquals("ISO", value("time.format"));
    assertEquals(0, number("time.samples.descents"));
    assertTrue(value("time.goTo.p99.us") != null);
    assertTrue(value("time.caret.p99.us") != null);
    assertTrue(number("time.linear.ms") >= 0);
  }

  @Test
  void timeStopsAfterDetectionWithoutAFormat() throws Exception {
    Path file = temp.resolve("plain.txt");
    Files.writeString(file, "no time here\nnor here\nnor here\nnor here\n");
    Harness harness = harness();
    try {
      harness.time(file, 50);
    } finally {
      harness.close();
    }
    assertEquals("null", value("time.format"));
    assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("time.goTo"));
  }
}
