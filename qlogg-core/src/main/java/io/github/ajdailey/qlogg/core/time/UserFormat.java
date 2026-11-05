package io.github.ajdailey.qlogg.core.time;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A user-defined format: a regex with a group named {@code ts} that locates the timestamp in the
 * line's first {@link TimeReader#PREFIX_CHARS} chars, and a {@link DateTimeFormatter} pattern
 * (English month and day names) for the group's text. The construction compiles both, so an
 * instance always produces a working parser.
 *
 * @param name shown and stored; 1 to {@link #MAX_CHARS} chars
 * @param locate the regex, at most {@link #MAX_CHARS} chars
 * @param pattern the formatter pattern, at most {@link #MAX_CHARS} chars
 */
public record UserFormat(String name, String locate, String pattern) implements TimestampFormat {
  /** Most chars in the name, the regex or the pattern. */
  public static final int MAX_CHARS = 256;

  /** Most user formats kept in the configuration. */
  public static final int MAX_FORMATS = 32;

  /** Prefix of a user format's {@link #id}. */
  public static final String ID_PREFIX = "user:";

  /**
   * Checks the lengths and compiles the regex and the pattern.
   *
   * @throws IllegalArgumentException for a blank name, a value over {@link #MAX_CHARS}, a regex
   *     that does not compile ({@link java.util.regex.PatternSyntaxException}) or has no group
   *     {@code ts}, or a pattern {@link DateTimeFormatter#ofPattern} refuses
   */
  public UserFormat {
    if (name.isBlank()) {
      throw new IllegalArgumentException("The name is empty");
    }
    if (name.length() > MAX_CHARS || locate.length() > MAX_CHARS || pattern.length() > MAX_CHARS) {
      throw new IllegalArgumentException("A value is longer than " + MAX_CHARS + " chars");
    }
    compileLocate(locate);
    compilePattern(pattern);
  }

  @Override
  public String id() {
    return ID_PREFIX + name;
  }

  @Override
  public TimestampParser parser(TimeContext context) {
    return new UserParser(
        compileLocate(locate),
        compilePattern(pattern),
        fractionDigits(pattern),
        twoDigitYear(pattern),
        context);
  }

  static Pattern compileLocate(String locate) {
    Pattern compiled = Pattern.compile(locate);
    if (!compiled.namedGroups().containsKey("ts")) {
      throw new IllegalArgumentException("The regex has no group named ts");
    }
    return compiled;
  }

  static DateTimeFormatter compilePattern(String pattern) {
    return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
  }

  /** Reports a run of exactly two {@code y} or {@code u} letters outside quotes. */
  static boolean twoDigitYear(String pattern) {
    boolean quoted = false;
    int i = 0;
    while (i < pattern.length()) {
      char c = pattern.charAt(i);
      int run = 1;
      if (c == '\'') {
        quoted = !quoted;
      } else if (!quoted && (c == 'y' || c == 'u')) {
        while (i + run < pattern.length() && pattern.charAt(i + run) == c) {
          run++;
        }
        if (run == 2) {
          return true;
        }
      }
      i += run;
    }
    return false;
  }

  /** The longest run of {@code S} letters outside quotes, at most 9. */
  static int fractionDigits(String pattern) {
    int longest = 0;
    int run = 0;
    boolean quoted = false;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '\'') {
        quoted = !quoted;
      }
      run = !quoted && c == 'S' ? run + 1 : 0;
      longest = Math.max(longest, run);
    }
    return Math.min(longest, 9);
  }
}
