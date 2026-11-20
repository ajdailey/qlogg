package io.github.ajdailey.qlogg.core.format;

import io.github.ajdailey.qlogg.core.format.FormatException.Reason;

/**
 * RFC 8259 escapes in the content of a JSON string, without its quotes, to chars. Every char other
 * than a backslash is kept, a quote included.
 */
public final class JsonStrings {
  private JsonStrings() {}

  /**
   * Replaces {@code \" \\ \/ \b \f \n \r \t} and backslash-u escapes; a surrogate escape counts
   * only as half of an escaped pair.
   *
   * @param text the string content
   * @return the unescaped text
   * @throws FormatException at the backslash of a malformed escape ({@link Reason#INVALID_ESCAPE})
   *     or of a surrogate escape without its escaped pair ({@link Reason#LONE_SURROGATE_ESCAPE})
   */
  public static String unescape(String text) {
    try {
      return run(text);
    } catch (FormatException e) {
      throw e.in(text);
    }
  }

  private static String run(String text) {
    StringBuilder out = new StringBuilder(text.length());
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c != '\\') {
        out.append(c);
        i++;
        continue;
      }
      if (i + 1 >= text.length()) {
        throw malformed(i);
      }
      char kind = text.charAt(i + 1);
      switch (kind) {
        case '"', '\\', '/' -> out.append(kind);
        case 'b' -> out.append('\b');
        case 'f' -> out.append('\f');
        case 'n' -> out.append('\n');
        case 'r' -> out.append('\r');
        case 't' -> out.append('\t');
        case 'u' -> {
          char unit = unit(text, i);
          if (Character.isHighSurrogate(unit)) {
            char low = isEscape(text, i + 6) ? unit(text, i + 6) : 0;
            if (!Character.isLowSurrogate(low)) {
              throw new FormatException(Reason.LONE_SURROGATE_ESCAPE, i, null);
            }
            out.append(unit).append(low);
            i += 12;
            continue;
          }
          if (Character.isLowSurrogate(unit)) {
            throw new FormatException(Reason.LONE_SURROGATE_ESCAPE, i, null);
          }
          out.append(unit);
          i += 6;
          continue;
        }
        default -> throw malformed(i);
      }
      i += 2;
    }
    return out.toString();
  }

  /** Reports whether a backslash-u escape starts at {@code i}. */
  private static boolean isEscape(String text, int i) {
    return i + 1 < text.length() && text.charAt(i) == '\\' && text.charAt(i + 1) == 'u';
  }

  /** Reads the four hex digits of the backslash-u escape at {@code at}. */
  private static char unit(String text, int at) {
    int from = at + 2;
    if (from + 4 > text.length()) {
      throw malformed(at);
    }
    int value = 0;
    for (int i = from; i < from + 4; i++) {
      int digit = hex(text.charAt(i));
      if (digit < 0) {
        throw malformed(at);
      }
      value = value << 4 | digit;
    }
    return (char) value;
  }

  /** Returns the value of an ASCII hex digit, or -1. */
  static int hex(char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
      return c - 'a' + 10;
    }
    return c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
  }

  private static FormatException malformed(int at) {
    return new FormatException(Reason.INVALID_ESCAPE, at, null);
  }
}
