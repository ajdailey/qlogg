package io.github.ajdailey.qlogg.core.format;

import io.github.ajdailey.qlogg.core.format.FormatException.Reason;
import java.util.Arrays;

/**
 * Pretty print of an XML document or of several top-level items. The text splits into items: tags,
 * comments, PIs, CDATA sections, the DOCTYPE and text runs. The split refuses an unterminated tag,
 * quote, comment, CDATA section, PI or DOCTYPE, an end tag whose name differs from the open
 * element's, an end tag with nothing open and an element still open at the end. It checks nothing
 * else, so names, attributes and entity references are not validated and nothing is read.
 *
 * <p>Only the gap between two items of a laid-out content changes:
 *
 * <ul>
 *   <li>The top level and the content of an element are laid out unless the content holds
 *       non-whitespace text or CDATA (mixed content or text only), the element is inside such
 *       content, or {@code xml:space="preserve"} applies to it; {@code xml:space="default"} resets
 *       the inherited preserve.
 *   <li>Each item of a laid-out content goes on its own line, indented one level per open element;
 *       an element with nothing but whitespace inside becomes {@code <a></a>}.
 *   <li>Whitespace before the first item and after the last stays.
 * </ul>
 *
 * Both passes are iterative, so nesting depth is bounded by the heap.
 */
final class XmlLayout {
  private static final byte TEXT = 0;
  private static final byte SPACE = 1;
  private static final byte START = 2;
  private static final byte END = 3;
  private static final byte EMPTY = 4;
  private static final byte CDATA = 5;
  private static final byte OTHER = 6;

  /** A start tag's content holds text or CDATA. */
  private static final byte MIXED = 1;

  /** {@code xml:space="preserve"} applies to a start tag's element. */
  private static final byte PRESERVE = 2;

  private final String s;
  private final Output out;
  private int count;
  private boolean topMixed;
  private int[] starts = new int[64];
  private int[] ends = new int[64];
  private byte[] types = new byte[64];
  private byte[] flags = new byte[64];

  private XmlLayout(String source, Output out) {
    this.s = source;
    this.out = out;
  }

  /**
   * Lays an XML text out into {@code out}.
   *
   * @throws FormatException at the first error
   */
  static void layout(String source, Output out) {
    XmlLayout layout = new XmlLayout(source, out);
    layout.scan();
    layout.emit();
  }

  /** Splits the text into items, checks tag balance and flags each start tag. */
  private void scan() {
    int n = s.length();
    int[] open = new int[16];
    int depth = 0;
    int i = 0;
    while (i < n) {
      int end;
      byte type;
      if (s.charAt(i) != '<') {
        end = s.indexOf('<', i);
        end = end < 0 ? n : end;
        type = blank(i, end) ? SPACE : TEXT;
      } else if (s.startsWith("<!--", i)) {
        end = after("-->", i + 4, i, Reason.UNTERMINATED_COMMENT);
        type = OTHER;
      } else if (s.startsWith("<![CDATA[", i)) {
        end = after("]]>", i + 9, i, Reason.UNTERMINATED_CDATA);
        type = CDATA;
      } else if (s.startsWith("<?", i)) {
        end = after("?>", i + 2, i, Reason.UNTERMINATED_PI);
        type = OTHER;
      } else if (s.startsWith("<!", i)) {
        end = doctypeEnd(i);
        type = OTHER;
      } else if (s.startsWith("</", i)) {
        end = after(">", i + 2, i, Reason.UNTERMINATED_TAG);
        type = END;
      } else {
        end = tagEnd(i);
        type = s.charAt(end - 2) == '/' ? EMPTY : START;
      }
      add(i, end, type);
      if ((type == TEXT || type == CDATA) && depth == 0) {
        topMixed = true;
      } else if (type == TEXT || type == CDATA) {
        flags[open[depth - 1]] |= MIXED;
      } else if (type == START || type == EMPTY) {
        boolean inherited = depth > 0 && (flags[open[depth - 1]] & PRESERVE) != 0;
        String space = xmlSpace(i + 1, end);
        boolean preserve = space == null ? inherited : space.equals("preserve");
        if (preserve) {
          flags[count - 1] |= PRESERVE;
        }
        if (type == START) {
          if (depth == open.length) {
            open = Arrays.copyOf(open, depth * 2);
          }
          open[depth++] = count - 1;
        }
      } else if (type == END) {
        String name = name(i + 2);
        if (depth == 0) {
          throw new FormatException(Reason.UNOPENED_END_TAG, i + 2, name);
        }
        String expected = name(starts[open[depth - 1]] + 1);
        if (!name.equals(expected)) {
          throw new FormatException(Reason.MISMATCHED_END_TAG, i + 2, expected);
        }
        depth--;
      }
      i = end;
    }
    if (depth > 0) {
      throw new FormatException(Reason.UNCLOSED_ELEMENT, n, name(starts[open[depth - 1]] + 1));
    }
  }

  /** Writes the items, replacing the gaps of laid-out content. */
  private void emit() {
    // Per open element: whether its content is laid out, and whether it or an ancestor is mixed.
    boolean[] laidOut = new boolean[16];
    boolean[] inMixed = new boolean[16];
    int depth = 0;
    boolean topLaidOut = !topMixed;
    int pos = 0;
    boolean first = true;
    byte previous = SPACE;
    for (int k = 0; k < count; k++) {
      byte type = types[k];
      if (type == SPACE) {
        continue;
      }
      int start = starts[k];
      boolean context = depth == 0 ? topLaidOut : laidOut[depth - 1];
      if (first || !context) {
        out.copy(pos, start);
        first = false;
      } else if (type != END || previous != START) {
        out.newline(pos, type == END ? depth - 1 : depth);
      }
      out.copy(start, ends[k]);
      pos = ends[k];
      previous = type;
      if (type == START) {
        boolean mixedAbove = depth > 0 ? inMixed[depth - 1] : topMixed;
        if (depth == laidOut.length) {
          laidOut = Arrays.copyOf(laidOut, depth * 2);
          inMixed = Arrays.copyOf(inMixed, depth * 2);
        }
        inMixed[depth] = mixedAbove || (flags[k] & MIXED) != 0;
        laidOut[depth] = !inMixed[depth] && (flags[k] & PRESERVE) == 0;
        depth++;
      } else if (type == END) {
        depth--;
      }
    }
    out.copy(pos, s.length());
  }

  private void add(int start, int end, byte type) {
    if (count == starts.length) {
      int size = count * 2;
      starts = Arrays.copyOf(starts, size);
      ends = Arrays.copyOf(ends, size);
      types = Arrays.copyOf(types, size);
      flags = Arrays.copyOf(flags, size);
    }
    starts[count] = start;
    ends[count] = end;
    types[count] = type;
    flags[count] = 0;
    count++;
  }

  private boolean blank(int from, int to) {
    for (int i = from; i < to; i++) {
      if (!isSpace(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /** Returns the tag name starting at {@code at}: up to whitespace, {@code '/'} or {@code '>'}. */
  private String name(int at) {
    int i = at;
    while (i < s.length() && !isSpace(s.charAt(i)) && s.charAt(i) != '/' && s.charAt(i) != '>') {
      i++;
    }
    return s.substring(at, i);
  }

  /** Returns the offset after {@code close} found from {@code from}, else fails at {@code at}. */
  private int after(String close, int from, int at, Reason reason) {
    int found = s.indexOf(close, from);
    if (found < 0) {
      throw new FormatException(reason, at, null);
    }
    return found + close.length();
  }

  private int skipQuoted(int at) {
    return after(String.valueOf(s.charAt(at)), at + 1, at, Reason.UNTERMINATED_QUOTE);
  }

  /** Returns the offset after the start or empty tag at {@code at}. */
  private int tagEnd(int at) {
    int i = at + 1;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '"' || c == '\'') {
        i = skipQuoted(i);
      } else if (c == '>') {
        return i + 1;
      } else {
        i++;
      }
    }
    throw new FormatException(Reason.UNTERMINATED_TAG, at, null);
  }

  /** Returns the offset after the DOCTYPE at {@code at}. */
  private int doctypeEnd(int at) {
    boolean subset = false;
    int i = at + 2;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '"' || c == '\'') {
        i = skipQuoted(i);
      } else if (subset && s.startsWith("<!--", i)) {
        i = after("-->", i + 4, i, Reason.UNTERMINATED_COMMENT);
      } else if (subset && s.startsWith("<?", i)) {
        i = after("?>", i + 2, i, Reason.UNTERMINATED_PI);
      } else if (c == '[') {
        subset = true;
        i++;
      } else if (c == ']') {
        subset = false;
        i++;
      } else if (c == '>' && !subset) {
        return i + 1;
      } else {
        i++;
      }
    }
    throw new FormatException(Reason.UNTERMINATED_DOCTYPE, at, null);
  }

  /**
   * Returns the value of the tag's {@code xml:space} attribute, or null when it has none or another
   * value.
   */
  private String xmlSpace(int from, int to) {
    int i = from;
    while (i < to && !isSpace(s.charAt(i)) && s.charAt(i) != '>' && s.charAt(i) != '/') {
      i++;
    }
    while (i < to) {
      while (i < to && isSpace(s.charAt(i))) {
        i++;
      }
      int nameStart = i;
      while (i < to && !isSpace(s.charAt(i)) && s.charAt(i) != '=') {
        if (s.charAt(i) == '>' || s.charAt(i) == '/') {
          return null;
        }
        i++;
      }
      String name = s.substring(nameStart, i);
      while (i < to && s.charAt(i) != '"' && s.charAt(i) != '\'') {
        i++;
      }
      if (i >= to) {
        return null;
      }
      int valueEnd = s.indexOf(s.charAt(i), i + 1);
      String value = s.substring(i + 1, valueEnd);
      i = valueEnd + 1;
      if (name.equals("xml:space")) {
        return value.equals("preserve") || value.equals("default") ? value : null;
      }
    }
    return null;
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
  }
}
