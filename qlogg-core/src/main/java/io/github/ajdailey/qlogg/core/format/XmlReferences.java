package io.github.ajdailey.qlogg.core.format;

/**
 * XML reference replacement over a char range, as {@link XmlEntities#unescape(String)} applies it:
 * named references from {@link XmlEntities} and decimal and hex character references. Allocates
 * nothing beyond the growth of the output.
 */
public final class XmlReferences {
  /** The longest reference body considered, between {@code &} and {@code ;}. */
  private static final int MAX_BODY = 32;

  private XmlReferences() {}

  /**
   * Appends chars with each known reference replaced, as {@link XmlEntities#unescape(String)} does.
   * A reference cut by the range end stays as written.
   *
   * @param text the chars
   * @param from the first index
   * @param to the index after the last
   * @param out receives at most {@code to - from} chars
   */
  public static void unescape(CharSequence text, int from, int to, StringBuilder out) {
    int i = from;
    while (i < to) {
      char c = text.charAt(i);
      int end = c == '&' ? semicolon(text, i + 1, to) : -1;
      int cp = end < 0 ? -1 : reference(text, i + 1, end);
      if (cp < 0) {
        out.append(c);
        i++;
      } else {
        out.appendCodePoint(cp);
        i = end + 1;
      }
    }
  }

  /** Returns the offset of the {@code ;} closing a reference body starting at {@code from}. */
  private static int semicolon(CharSequence text, int from, int to) {
    int limit = Math.min(to, from + MAX_BODY + 1);
    for (int i = from; i < limit; i++) {
      char c = text.charAt(i);
      if (c == ';') {
        return i > from ? i : -1;
      }
      if (c == '&' || c == '<' || Character.isWhitespace(c)) {
        return -1;
      }
    }
    return -1;
  }

  /** Returns the code point of a reference body, or -1 when it names none. */
  private static int reference(CharSequence text, int from, int end) {
    if (text.charAt(from) != '#') {
      return XmlEntities.named(text, from, end);
    }
    int radix = 10;
    int at = from + 1;
    if (at < end && (text.charAt(at) == 'x' || text.charAt(at) == 'X')) {
      radix = 16;
      at++;
    }
    if (at == end) {
      return -1;
    }
    int value = 0;
    for (int i = at; i < end; i++) {
      char c = text.charAt(i);
      int digit = radix == 16 ? JsonStrings.hex(c) : c >= '0' && c <= '9' ? c - '0' : -1;
      if (digit < 0) {
        return -1;
      }
      value = value * radix + digit;
      if (value > Character.MAX_CODE_POINT) {
        return -1;
      }
    }
    boolean surrogate = value >= Character.MIN_SURROGATE && value <= Character.MAX_SURROGATE;
    return value == 0 || surrogate ? -1 : value;
  }
}
