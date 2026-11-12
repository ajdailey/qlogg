package io.github.ajdailey.qlogg.app.highlight;

import io.github.ajdailey.qlogg.core.text.BoundedChars;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.regex.Pattern;

/**
 * Picks a highlighter set for a newly opened file, by its file name or its first line. Exactly one
 * of the two patterns is set. Immutable.
 *
 * @param fileNameGlob a glob matched against the file name only, or empty
 * @param firstLinePattern a Java regex run with {@code find} on the first line, or empty
 * @param setId the set chosen on a match
 */
public record HighlightSelector(String fileNameGlob, String firstLinePattern, String setId) {
  /** Longest pattern kept, in chars. */
  public static final int MAX_PATTERN_CHARS = 1024;

  /** Most selectors kept. */
  public static final int MAX_SELECTORS = 256;

  /** Checks the components: exactly one pattern, each within {@link #MAX_PATTERN_CHARS}. */
  public HighlightSelector {
    if (fileNameGlob == null || firstLinePattern == null || setId == null || setId.isEmpty()) {
      throw new IllegalArgumentException("patterns and set id are required");
    }
    if (fileNameGlob.isEmpty() == firstLinePattern.isEmpty()) {
      throw new IllegalArgumentException("exactly one of the glob and the pattern is set");
    }
    if (fileNameGlob.length() > MAX_PATTERN_CHARS
        || firstLinePattern.length() > MAX_PATTERN_CHARS) {
      throw new IllegalArgumentException("pattern too long");
    }
  }

  /**
   * Reports whether the selector looks at the file name.
   *
   * @return true for a glob selector
   */
  public boolean byFileName() {
    return !fileNameGlob.isEmpty();
  }

  /**
   * Matches the glob against a file name.
   *
   * @param fileName the name without directories
   * @return false for a first-line selector, or a glob that does not parse
   */
  public boolean matchesName(String fileName) {
    if (!byFileName()) {
      return false;
    }
    try {
      PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + fileNameGlob);
      return matcher.matches(Path.of(fileName));
    } catch (IllegalArgumentException | UnsupportedOperationException e) {
      return false;
    }
  }

  /**
   * Runs the first-line pattern with {@code find} on a worker, reading at most {@code 8 *
   * line.length() + 65,536} chars of the line, the bound the highlighter puts on a rule.
   *
   * @param line the first line
   * @return false for a glob selector, a pattern that does not compile, no match, or a match that
   *     spent the bound or overflowed the regex engine's stack
   */
  public boolean matchesFirstLine(CharSequence line) {
    Pattern pattern = firstLine();
    if (pattern == null) {
      return false;
    }
    return new BoundedChars().find(pattern.matcher(""), line);
  }

  /**
   * Compiles the first-line pattern; run the match off the UI thread.
   *
   * @return the pattern, or null for a glob selector or a pattern that does not compile
   */
  public Pattern firstLine() {
    if (byFileName()) {
      return null;
    }
    try {
      return Pattern.compile(firstLinePattern);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
