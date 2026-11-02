package io.github.ajdailey.qlogg.core.search;

/** How a search term's pattern is read. */
public enum MatchMode {
  /** The pattern is text matched as is. */
  LITERAL,
  /** The pattern is a {@link java.util.regex.Pattern} regular expression. */
  REGEX
}
