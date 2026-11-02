package io.github.ajdailey.qlogg.bench;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.SplittableRandom;

/**
 * Writes deterministic benchmark logs: the same profile, size and seed give the same bytes. Every
 * profile except {@link Profile#TINY} writes log-like lines with a timestamp, level, thread and
 * line number, and puts {@link #RARE_TOKEN} in 0.1% and {@link #COMMON_TOKEN} in 10% of the lines,
 * so one file serves both match densities. {@link Profile#TINY} writes 10-byte lines ({@code
 * mock12345} and LF) with {@link #TINY_RARE} in 0.1% and {@link #TINY_COMMON} in 1% of them.
 */
public final class LogGenerator {
  /** Token in 0.1% of the lines. */
  public static final String RARE_TOKEN = "flagword";

  /** Token in 10% of the lines. */
  public static final String COMMON_TOKEN = "blockterm";

  /** Prefix of 0.1% of the {@link Profile#TINY} lines. */
  public static final String TINY_RARE = "flag";

  /** Prefix of 1% of the {@link Profile#TINY} lines. */
  public static final String TINY_COMMON = "blkt";

  private static final double RARE = 0.001;
  private static final double COMMON = 0.1;
  private static final double TINY_COMMON_RATE = 0.01;
  private static final int LONG_LINE_EVERY = 2000;
  private static final int LONG_LINE_BYTES = 1 << 20;

  private static final String[] LEVELS = {"INFO ", "DEBUG", "WARN ", "ERROR", "TRACE"};
  private static final String[] WORDS = {
    "mock", "event", "queue", "worker", "record", "accepted", "batch", "sample", "entry", "value"
  };
  private static final String[] NON_ASCII = {"café", "naïve", "数据", "日志", "Ωmega", "straße"};

  /** Line shapes. */
  public enum Profile {
    /** ASCII lines of 45 to about 120 bytes, average about 85. */
    ASCII_SHORT("ascii-short"),
    /**
     * UTF-8 lines of about 45 to 2,000 characters, the length drawn evenly; 5% of the lines carry
     * non-ASCII words.
     */
    UTF8_MIXED("utf8-mixed"),
    /** {@link #ASCII_SHORT} encoded as UTF-16LE after a byte order mark. */
    UTF16LE("utf16le"),
    /** ASCII lines of 0 to 7 KiB plus one line of 1 MiB in 2,000; average about 4 KiB. */
    LONG_LINES("long-lines"),
    /** {@link #ASCII_SHORT} without a newline after the last line. */
    NO_FINAL_NEWLINE("no-final-newline"),
    /** 10-byte lines. */
    TINY("tiny");

    private final String key;

    Profile(String key) {
      this.key = key;
    }

    /**
     * Returns the command-line name.
     *
     * @return the name
     */
    public String key() {
      return key;
    }

    /**
     * Finds a profile by its command-line name.
     *
     * @param key the name
     * @return the profile
     * @throws IllegalArgumentException when no profile has that name
     */
    public static Profile of(String key) {
      for (Profile profile : values()) {
        if (profile.key.equals(key)) {
          return profile;
        }
      }
      throw new IllegalArgumentException("Unknown profile: " + key);
    }
  }

  private final Profile profile;
  private final SplittableRandom random;
  private final StringBuilder line = new StringBuilder(LONG_LINE_BYTES + 64);
  private final byte[] tinyLine = new byte[10];
  private long number;

  /**
   * Creates a generator.
   *
   * @param profile the line shape
   * @param seed the seed
   */
  public LogGenerator(Profile profile, long seed) {
    this.profile = profile;
    this.random = new SplittableRandom(seed);
  }

  /**
   * Writes a new file of at least {@code targetBytes} (exactly, for {@link Profile#TINY} with a
   * multiple of 10).
   *
   * @param file created; must not exist
   * @param targetBytes least size
   * @return bytes written
   * @throws IOException on a write error, or when the file exists
   */
  public long write(Path file, long targetBytes) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocateDirect(8 << 20);
    long written = 0;
    try (FileChannel channel =
        FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      if (profile == Profile.UTF16LE) {
        buffer.put((byte) 0xff).put((byte) 0xfe);
        written += 2;
      }
      while (written < targetBytes) {
        byte[] bytes = nextLine();
        boolean last =
            profile == Profile.NO_FINAL_NEWLINE && written + bytes.length + 1 >= targetBytes;
        int length = last ? bytes.length - unitBytes() : bytes.length;
        if (buffer.remaining() < length) {
          drain(channel, buffer);
        }
        if (length > buffer.capacity()) {
          channel.write(ByteBuffer.wrap(bytes, 0, length));
        } else {
          buffer.put(bytes, 0, length);
        }
        written += length;
        if (last) {
          break;
        }
      }
      drain(channel, buffer);
    }
    return written;
  }

  private int unitBytes() {
    return profile == Profile.UTF16LE ? 2 : 1;
  }

  private static void drain(FileChannel channel, ByteBuffer buffer) throws IOException {
    buffer.flip();
    while (buffer.hasRemaining()) {
      channel.write(buffer);
    }
    buffer.clear();
  }

  /**
   * Returns the next line with its LF, encoded for the profile.
   *
   * @return the bytes; for {@link Profile#TINY} an array reused by the next call
   */
  byte[] nextLine() {
    if (profile == Profile.TINY) {
      return tiny();
    }
    line.setLength(0);
    switch (profile) {
      case UTF8_MIXED -> mixed();
      case LONG_LINES -> longLine();
      default -> shortLine();
    }
    line.append('\n');
    number++;
    if (profile == Profile.UTF16LE) {
      return line.toString().getBytes(StandardCharsets.UTF_16LE);
    }
    if (profile == Profile.UTF8_MIXED) {
      return line.toString().getBytes(StandardCharsets.UTF_8);
    }
    return line.toString().getBytes(StandardCharsets.ISO_8859_1);
  }

  /** Fills and returns the one reused 10-byte array. */
  private byte[] tiny() {
    double draw = random.nextDouble();
    String prefix = draw < RARE ? TINY_RARE : draw < RARE + TINY_COMMON_RATE ? TINY_COMMON : "mock";
    for (int i = 0; i < 4; i++) {
      tinyLine[i] = (byte) prefix.charAt(i);
    }
    int value = random.nextInt(100_000);
    for (int i = 8; i >= 4; i--) {
      tinyLine[i] = (byte) ('0' + value % 10);
      value /= 10;
    }
    tinyLine[9] = '\n';
    number++;
    return tinyLine;
  }

  private void prefix() {
    line.append("2026-11-01 12:");
    two(random.nextInt(60));
    line.append(':');
    two(random.nextInt(60));
    line.append('.').append(100 + random.nextInt(900)).append(' ');
    line.append(LEVELS[random.nextInt(LEVELS.length)]);
    line.append(" [worker-").append(random.nextInt(16)).append("] #").append(number);
    if (random.nextDouble() < RARE) {
      line.append(' ').append(RARE_TOKEN);
    }
    if (random.nextDouble() < COMMON) {
      line.append(' ').append(COMMON_TOKEN);
    }
  }

  private void shortLine() {
    prefix();
    int words = random.nextInt(11);
    for (int i = 0; i < words; i++) {
      line.append(' ').append(WORDS[random.nextInt(WORDS.length)]);
    }
  }

  private void mixed() {
    prefix();
    int target = 20 + random.nextInt(1981);
    boolean nonAscii = random.nextDouble() < 0.05;
    while (line.length() < target) {
      if (nonAscii && random.nextInt(4) == 0) {
        line.append(' ').append(NON_ASCII[random.nextInt(NON_ASCII.length)]);
      } else {
        line.append(' ').append(WORDS[random.nextInt(WORDS.length)]);
      }
    }
  }

  private void longLine() {
    prefix();
    int target =
        random.nextInt(LONG_LINE_EVERY) == 0 ? LONG_LINE_BYTES : random.nextInt(7 * 1024 + 1);
    while (line.length() < target) {
      line.append(' ').append(WORDS[random.nextInt(WORDS.length)]);
    }
  }

  private void two(int value) {
    if (value < 10) {
      line.append('0');
    }
    line.append(value);
  }
}
