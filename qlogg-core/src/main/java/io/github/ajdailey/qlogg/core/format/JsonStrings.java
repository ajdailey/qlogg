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
    StringBuilder out = new StringBuilder(text.length());
    try {
      run(text, 0, text.length(), out, true);
    } catch (FormatException e) {
      throw e.in(text);
    }
    return out.toString();
  }

  /**
   * Appends string content with its escapes replaced as {@link #unescape(String)} does, except that
   * a malformed escape or a surrogate escape without its escaped pair is copied as written. Grows
   * only {@code out}.
   *
   * @param text the chars holding the string content
   * @param from the content's first index
   * @param to the index after the content
   * @param out receives the chars
   */
  public static void unescape(CharSequence text, int from, int to, StringBuilder out) {
    run(text, from, to, out, false);
  }

  private static void run(CharSequence text, int from, int to, StringBuilder out, boolean strict) {
    int i = from;
    while (i < to) {
      char c = text.charAt(i);
      if (c != '\\') {
        out.append(c);
        i++;
        continue;
      }
      int used = escape(text, i, to, out);
      if (used < 0) {
        if (strict) {
          Reason reason = used == -2 ? Reason.LONE_SURROGATE_ESCAPE : Reason.INVALID_ESCAPE;
          throw new FormatException(reason, i - from + (used == -3 ? 6 : 0), null);
        }
        out.append(c);
        used = 1;
      }
      i += used;
    }
  }

  /**
   * Appends the escape at {@code at} and returns the chars it used: -1 for a malformed escape, -2
   * for a surrogate escape without its escaped pair, -3 for a high surrogate escape followed by a
   * malformed one.
   */
  private static int escape(CharSequence text, int at, int to, StringBuilder out) {
    if (at + 1 >= to) {
      return -1;
    }
    char kind = text.charAt(at + 1);
    switch (kind) {
      case '"', '\\', '/' -> out.append(kind);
      case 'b' -> out.append('\b');
      case 'f' -> out.append('\f');
      case 'n' -> out.append('\n');
      case 'r' -> out.append('\r');
      case 't' -> out.append('\t');
      case 'u' -> {
        int unit = unit(text, at, to);
        if (unit < 0) {
          return -1;
        }
        if (Character.isLowSurrogate((char) unit)) {
          return -2;
        }
        if (!Character.isHighSurrogate((char) unit)) {
          out.append((char) unit);
          return 6;
        }
        int low = isEscape(text, at + 6, to) ? unit(text, at + 6, to) : 0;
        if (low < 0) {
          return -3;
        }
        if (!Character.isLowSurrogate((char) low)) {
          return -2;
        }
        out.append((char) unit).append((char) low);
        return 12;
      }
      default -> {
        return -1;
      }
    }
    return 2;
  }

  /** Reports whether a backslash-u escape starts at {@code i}. */
  private static boolean isEscape(CharSequence text, int i, int to) {
    return i + 1 < to && text.charAt(i) == '\\' && text.charAt(i + 1) == 'u';
  }

  /** Reads the four hex digits of the backslash-u escape at {@code at}, or returns -1. */
  private static int unit(CharSequence text, int at, int to) {
    int from = at + 2;
    if (from + 4 > to) {
      return -1;
    }
    int value = 0;
    for (int i = from; i < from + 4; i++) {
      int digit = hex(text.charAt(i));
      if (digit < 0) {
        return -1;
      }
      value = value << 4 | digit;
    }
    return value;
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
}
