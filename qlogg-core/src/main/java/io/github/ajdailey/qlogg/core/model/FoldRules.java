package io.github.ajdailey.qlogg.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which lines continue the entry above them. With {@code entryStart} set, every line it finds no
 * match in is a continuation; otherwise a line is a continuation when a {@code continuation}
 * pattern finds a match in it. Patterns are Java regexes run with {@code find}, so an anchored
 * pattern starts with {@code ^}. Immutable.
 *
 * @param entryStart the pattern of an entry's first line, or empty
 * @param continuation patterns of continuation lines, used when {@code entryStart} is empty
 */
public record FoldRules(String entryStart, List<String> continuation) {
  /** Indented lines, stack frames, {@code Caused by:} and {@code ... N more}. */
  public static final FoldRules DEFAULTS =
      new FoldRules("", List.of("^\\s", "^at ", "^Caused by:", "^\\.\\.\\. \\d+ more"));

  /** Checks and copies the components. */
  public FoldRules {
    if (entryStart == null || continuation == null) {
      throw new IllegalArgumentException("entryStart and continuation are required");
    }
    continuation = List.copyOf(continuation);
  }

  /**
   * Compiles the patterns for one thread.
   *
   * @return the compiled rules
   * @throws java.util.regex.PatternSyntaxException when a pattern does not compile
   */
  public Compiled compile() {
    Matcher start = entryStart.isEmpty() ? null : Pattern.compile(entryStart).matcher("");
    List<Matcher> patterns = new ArrayList<Matcher>();
    for (String pattern : continuation) {
      patterns.add(Pattern.compile(pattern).matcher(""));
    }
    return new Compiled(start, patterns);
  }

  /** Compiled rules. Not thread-safe: one instance per thread. */
  public static final class Compiled {
    private final Matcher start;
    private final List<Matcher> continuation;

    private Compiled(Matcher start, List<Matcher> continuation) {
      this.start = start;
      this.continuation = continuation;
    }

    /**
     * Reports whether a line continues the entry above it.
     *
     * @param line the decoded line
     * @return true for a continuation line
     */
    public boolean continues(CharSequence line) {
      if (start != null) {
        return !start.reset(line).find();
      }
      for (Matcher pattern : continuation) {
        if (pattern.reset(line).find()) {
          return true;
        }
      }
      return false;
    }
  }
}
