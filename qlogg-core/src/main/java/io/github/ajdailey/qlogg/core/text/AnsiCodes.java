package io.github.ajdailey.qlogg.core.text;

import java.nio.CharBuffer;

/**
 * Removes ANSI SGR color sequences, {@code ESC [} then digits, {@code ;} or {@code :}, then {@code
 * m}, from decoded text. Any other escape sequence, and an SGR sequence cut off at the end of the
 * text, stays.
 */
public final class AnsiCodes {
  private static final char ESC = 0x1b;

  private AnsiCodes() {}

  /**
   * Strips the sequences in place between a buffer's position and limit; the limit moves back over
   * the removed chars.
   *
   * @param text decoded chars, position at the first
   */
  public static void strip(CharBuffer text) {
    int limit = text.limit();
    int out = text.position();
    int i = out;
    while (i < limit) {
      int end = sequenceEnd(text, i, limit);
      if (end > i) {
        i = end;
      } else {
        text.put(out++, text.get(i++));
      }
    }
    text.limit(out);
  }

  /**
   * Strips the sequences in place from the first {@code length} chars of an array.
   *
   * @param chars the text
   * @param length chars used
   * @return the new length
   */
  public static int strip(char[] chars, int length) {
    CharBuffer view = CharBuffer.wrap(chars, 0, length);
    strip(view);
    return view.limit();
  }

  /**
   * Returns a string without the sequences.
   *
   * @param text any text
   * @return the text, the same instance when it holds no {@code ESC}
   */
  public static String strip(String text) {
    if (text.indexOf(ESC) < 0) {
      return text;
    }
    char[] chars = text.toCharArray();
    return new String(chars, 0, strip(chars, chars.length));
  }

  /** The index after the SGR sequence at {@code at}, or {@code at} when none starts there. */
  private static int sequenceEnd(CharBuffer text, int at, int limit) {
    if (text.get(at) != ESC || at + 1 >= limit || text.get(at + 1) != '[') {
      return at;
    }
    for (int k = at + 2; k < limit; k++) {
      char c = text.get(k);
      if (c == 'm') {
        return k + 1;
      }
      if (!(c >= '0' && c <= '9' || c == ';' || c == ':')) {
        return at;
      }
    }
    return at;
  }
}
