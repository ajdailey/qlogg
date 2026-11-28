package io.github.ajdailey.qlogg.core.model;

/**
 * Colors a Level cell ({@link io.github.ajdailey.qlogg.core.columns.ColumnSchema#levelColumn}) by
 * its whole value, ASCII case ignored, with the token kind {@link LogTokens} gives the same level
 * in text: Serilog's Verbose, Debug, Information, Warning, Error and Fatal, log4j's TRACE to FATAL,
 * and the other names the text rules know.
 */
final class LevelNames {
  private LevelNames() {}

  /**
   * Returns a level name's kind.
   *
   * @param value the cell's value
   * @return the kind, or null when the value is no level name
   */
  static TokenKind kind(CharSequence value) {
    String name = lower(value);
    return switch (name) {
      case "fatal", "error", "err", "severe", "critical", "crit", "emerg", "emergency", "alert" ->
          TokenKind.INVALID;
      case "warning", "warn", "wrn" -> TokenKind.ESCAPE;
      case "information", "info", "inf", "notice", "config" -> TokenKind.TYPE;
      case "debug", "fine", "dbg" -> TokenKind.KEYWORD2;
      case "verbose", "trace", "trc", "finer", "finest" -> TokenKind.COMMENT;
      default -> null;
    };
  }

  /**
   * Returns one span of a kind over a cell's shown chars.
   *
   * @param kind the kind
   * @param end the shown chars
   * @return the spans, empty when {@code end} is 0
   */
  static TokenSpans span(TokenKind kind, int end) {
    return end <= 0 ? TokenSpans.NONE : new TokenSpans(new int[] {0, end, kind.ordinal()}, 1);
  }

  /** Lowercases ASCII letters of a short value; a longer value returns empty. */
  private static String lower(CharSequence value) {
    if (value.length() > 16) {
      return "";
    }
    char[] chars = new char[value.length()];
    for (int i = 0; i < chars.length; i++) {
      char c = value.charAt(i);
      chars[i] = c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }
    return new String(chars);
  }
}
