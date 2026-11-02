package io.github.ajdailey.qlogg.core.testkit;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;

/**
 * Deterministic synthetic log content for tests and benchmarks. Lines look like log entries with a
 * timestamp, level, thread and mock message, 30 to about 160 bytes before the newline. The same
 * seed gives the same bytes.
 */
public final class SyntheticLog {
  private static final String[] LEVELS = {"INFO ", "DEBUG", "WARN ", "ERROR", "TRACE"};
  private static final String[] WORDS = {
    "mock", "event", "flagword", "queue", "worker", "record", "BLOCKTERM", "accepted", "batch"
  };

  private SyntheticLog() {}

  /**
   * Returns one line without its newline.
   *
   * @param random the generator
   * @param number the line number written into the line
   * @return ASCII text
   */
  public static String line(Random random, long number) {
    StringBuilder line = new StringBuilder(160);
    line.append("2026-11-01 12:")
        .append(two(random.nextInt(60)))
        .append(':')
        .append(two(random.nextInt(60)))
        .append('.')
        .append(100 + random.nextInt(900))
        .append(' ')
        .append(LEVELS[random.nextInt(LEVELS.length)])
        .append(" [worker-")
        .append(random.nextInt(16))
        .append("] #")
        .append(number);
    int words = random.nextInt(16);
    for (int i = 0; i < words; i++) {
      line.append(' ').append(WORDS[random.nextInt(WORDS.length)]);
    }
    return line.toString();
  }

  /**
   * Builds lines in memory.
   *
   * @param lines line count
   * @param seed generator seed
   * @param newline appended to every line
   * @return the text
   */
  public static String text(int lines, long seed, String newline) {
    Random random = new Random(seed);
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < lines; i++) {
      text.append(line(random, i)).append(newline);
    }
    return text.toString();
  }

  /**
   * Writes ASCII lines to a new file until it holds at least {@code targetBytes}.
   *
   * @param file created; must not exist
   * @param targetBytes least size
   * @param seed generator seed
   * @param newline appended to every line
   * @return bytes written
   * @throws IOException on a write error
   */
  public static long write(Path file, long targetBytes, long seed, String newline)
      throws IOException {
    Random random = new Random(seed);
    byte[] end = newline.getBytes(StandardCharsets.US_ASCII);
    long written = 0;
    long number = 0;
    try (OutputStream out =
        new BufferedOutputStream(
            Files.newOutputStream(file, StandardOpenOption.CREATE_NEW), 1 << 20)) {
      while (written < targetBytes) {
        byte[] bytes = line(random, number++).getBytes(StandardCharsets.US_ASCII);
        out.write(bytes);
        out.write(end);
        written += bytes.length + end.length;
      }
    }
    return written;
  }

  private static String two(int value) {
    return value < 10 ? "0" + value : Integer.toString(value);
  }
}
