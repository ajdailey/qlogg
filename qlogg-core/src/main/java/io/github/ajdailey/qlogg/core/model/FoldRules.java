package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.text.BoundedChars;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which lines continue the entry above them. With {@code entryStart} set, every line it finds no
 * match in is a continuation; otherwise a line is a continuation when a {@code continuation}
 * pattern finds a match in it. Patterns are Java regexes run with {@code find} through {@link
 * BoundedChars#find}, so an anchored pattern starts with {@code ^}. An {@code entryStart} run that
 * reaches the read limit or overflows the stack counts as a match, so the line starts an entry and
 * stays unfolded; a {@code continuation} run that does finds no match. Immutable.
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
     * @param chars the bounded chars the patterns read the line through
     * @return true for a continuation line
     * @throws java.util.concurrent.CancellationException when the chars' token is canceled
     */
    public boolean continues(CharSequence line, BoundedChars chars) {
      if (start != null) {
        return !chars.find(start, line, true);
      }
      for (Matcher pattern : continuation) {
        if (chars.find(pattern, line)) {
          return true;
        }
      }
      return false;
    }
  }
}
