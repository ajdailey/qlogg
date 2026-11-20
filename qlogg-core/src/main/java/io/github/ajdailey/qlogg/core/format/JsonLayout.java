package io.github.ajdailey.qlogg.core.format;

import io.github.ajdailey.qlogg.core.format.FormatException.Reason;
import java.util.Arrays;

/**
 * Pretty print of one JSON value (RFC 8259) in one pass with an explicit container stack, so
 * nesting depth is bounded by the heap. Whitespace is space, tab, LF and CR. {@code //} and block
 * comments may stand wherever whitespace may; any other extension (trailing commas, single quotes,
 * unquoted names, NaN, Infinity) is an error at its offset.
 *
 * <p>Only the gaps between tokens change. Each member and element goes on its own line, indented
 * one level per open container, a name is followed by {@code ": "}, and an empty {@code {}} or
 * {@code []} stays on one line. A comment that followed a line break goes on its own line at the
 * depth of the container holding it; one on the line of the token before it keeps its gap. A line
 * comment is always followed by a line break. Whitespace before the first token and after the last
 * stays.
 */
final class JsonLayout {
  private static final int NONE = 0;
  private static final int OPEN = 1;
  private static final int CLOSE = 2;
  private static final int COLON = 3;
  private static final int COMMA = 4;
  private static final int VALUE = 5;
  private static final int LINE_COMMENT = 6;
  private static final int BLOCK_COMMENT = 7;

  /** Parser states: what the next token may be. */
  private static final int EXPECT_VALUE = 0;

  private static final int OBJECT_FIRST = 1;
  private static final int EXPECT_NAME = 2;
  private static final int EXPECT_COLON = 3;
  private static final int ARRAY_FIRST = 4;
  private static final int EXPECT_NEXT = 5;
  private static final int DONE = 6;

  private final String s;
  private final Output out;
  private byte[] stack = new byte[64];
  private int depth;
  private boolean first = true;
  private int previous = NONE;
  private int previousToken = NONE;

  private JsonLayout(String source, Output out) {
    this.s = source;
    this.out = out;
  }

  /**
   * Lays a JSON text out into {@code out}.
   *
   * @throws FormatException at the first error
   */
  static void layout(String source, Output out) {
    new JsonLayout(source, out).run();
  }

  private void run() {
    int n = s.length();
    int state = EXPECT_VALUE;
    int pos = 0;
    while (true) {
      int i = pos;
      while (i < n && isSpace(s.charAt(i))) {
        i++;
      }
      if (i == n) {
        break;
      }
      char c = s.charAt(i);
      if (c == '/') {
        pos = comment(pos, i);
        continue;
      }
      int kind;
      int level = depth;
      int end = i + 1;
      int next = state;
      switch (state) {
        case EXPECT_VALUE, ARRAY_FIRST -> {
          if (state == ARRAY_FIRST && c == ']') {
            kind = CLOSE;
            next = pop();
          } else if (c == '{' || c == '[') {
            kind = OPEN;
            push(c);
            next = c == '{' ? OBJECT_FIRST : ARRAY_FIRST;
          } else {
            kind = VALUE;
            end = value(i);
            next = depth == 0 ? DONE : EXPECT_NEXT;
          }
        }
        case OBJECT_FIRST, EXPECT_NAME -> {
          if (state == OBJECT_FIRST && c == '}') {
            kind = CLOSE;
            next = pop();
          } else if (c == '"') {
            kind = VALUE;
            end = string(i);
            next = EXPECT_COLON;
          } else {
            throw new FormatException(
                state == OBJECT_FIRST ? Reason.EXPECTED_NAME_OR_CLOSE : Reason.EXPECTED_NAME,
                i,
                null);
          }
        }
        case EXPECT_COLON -> {
          if (c != ':') {
            throw new FormatException(Reason.EXPECTED_COLON, i, null);
          }
          kind = COLON;
          next = EXPECT_VALUE;
        }
        case EXPECT_NEXT -> {
          boolean object = stack[depth - 1] == '{';
          if (c == ',') {
            kind = COMMA;
            next = object ? EXPECT_NAME : EXPECT_VALUE;
          } else if (c == (object ? '}' : ']')) {
            kind = CLOSE;
            next = pop();
          } else {
            throw new FormatException(
                object ? Reason.EXPECTED_COMMA_OR_BRACE : Reason.EXPECTED_COMMA_OR_BRACKET,
                i,
                null);
          }
        }
        default -> throw new FormatException(Reason.TEXT_AFTER_VALUE, i, null);
      }
      gap(pos, i, kind, level);
      out.copy(i, end);
      previous = kind;
      previousToken = kind;
      state = next;
      pos = end;
    }
    if (state != DONE) {
      throw new FormatException(
          first && state == EXPECT_VALUE ? Reason.EXPECTED_VALUE : Reason.UNEXPECTED_END, n, null);
    }
    out.copy(pos, n);
  }

  /** Copies the comment at {@code at} after its gap; returns the offset after it. */
  private int comment(int pos, int at) {
    int end;
    int kind;
    if (s.startsWith("//", at)) {
      end = at + 2;
      while (end < s.length() && s.charAt(end) != '\n' && s.charAt(end) != '\r') {
        end++;
      }
      kind = LINE_COMMENT;
    } else if (s.startsWith("/*", at)) {
      int close = s.indexOf("*/", at + 2);
      if (close < 0) {
        throw new FormatException(Reason.UNTERMINATED_COMMENT, at, null);
      }
      end = close + 2;
      kind = BLOCK_COMMENT;
    } else {
      throw new FormatException(Reason.UNEXPECTED_SLASH, at, null);
    }
    gap(pos, at, kind, depth);
    out.copy(at, end);
    previous = kind;
    return end;
  }

  /**
   * Writes the gap {@code [from, to)} before a token of {@code kind} met at nesting {@code level}.
   */
  private void gap(int from, int to, int kind, int level) {
    if (first) {
      first = false;
      out.copy(from, to);
      return;
    }
    if (kind == LINE_COMMENT || kind == BLOCK_COMMENT) {
      if (previous == LINE_COMMENT || hasBreak(from, to)) {
        out.newline(from, level);
      } else {
        out.copy(from, to);
      }
      return;
    }
    int inner = kind == CLOSE ? level - 1 : level;
    if (previous == OPEN && kind == CLOSE) {
      return;
    }
    if (previous == LINE_COMMENT
        || kind == CLOSE
        || previousToken == OPEN
        || previousToken == COMMA
        || previousToken == NONE) {
      out.newline(from, inner);
    } else if (previousToken == COLON) {
      out.space(from);
    }
  }

  private boolean hasBreak(int from, int to) {
    for (int i = from; i < to; i++) {
      if (s.charAt(i) == '\n' || s.charAt(i) == '\r') {
        return true;
      }
    }
    return false;
  }

  private void push(char open) {
    if (depth == stack.length) {
      stack = Arrays.copyOf(stack, depth * 2);
    }
    stack[depth++] = (byte) open;
  }

  /** Pops a container; returns the state after it as a finished value. */
  private int pop() {
    depth--;
    return depth == 0 ? DONE : EXPECT_NEXT;
  }

  /** Returns the offset after the string, number or literal at {@code at}. */
  private int value(int at) {
    char c = s.charAt(at);
    if (c == '"') {
      return string(at);
    }
    if (c == '-' || (c >= '0' && c <= '9')) {
      return number(at);
    }
    for (String literal : new String[] {"true", "false", "null"}) {
      if (s.startsWith(literal, at)) {
        return at + literal.length();
      }
    }
    throw new FormatException(Reason.EXPECTED_VALUE, at, null);
  }

  private int string(int at) {
    int i = at + 1;
    int n = s.length();
    while (i < n) {
      char c = s.charAt(i);
      if (c == '"') {
        return i + 1;
      }
      if (c < 0x20) {
        throw new FormatException(Reason.CONTROL_CHARACTER, i, null);
      }
      if (c == '\\') {
        if (i + 1 >= n) {
          break;
        }
        char e = s.charAt(i + 1);
        if (e == 'u') {
          for (int k = i + 2; k < i + 6; k++) {
            if (k >= n || Character.digit(s.charAt(k), 16) < 0) {
              throw new FormatException(Reason.INVALID_UNICODE_ESCAPE, i, null);
            }
          }
          i += 6;
          continue;
        }
        if ("\"\\/bfnrt".indexOf(e) < 0) {
          throw new FormatException(Reason.INVALID_ESCAPE, i, null);
        }
        i += 2;
        continue;
      }
      i++;
    }
    throw new FormatException(Reason.UNTERMINATED_STRING, at, null);
  }

  private int number(int at) {
    int i = at;
    if (s.charAt(i) == '-') {
      i++;
    }
    if (i < s.length() && s.charAt(i) == '0') {
      i++;
    } else {
      i = digits(i, at);
    }
    if (i < s.length() && s.charAt(i) == '.') {
      i = digits(i + 1, at);
    }
    if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
      i++;
      if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
        i++;
      }
      i = digits(i, at);
    }
    return i;
  }

  private int digits(int from, int number) {
    int i = from;
    while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
      i++;
    }
    if (i == from) {
      throw new FormatException(Reason.INVALID_NUMBER, from < s.length() ? from : number, null);
    }
    return i;
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
  }
}
