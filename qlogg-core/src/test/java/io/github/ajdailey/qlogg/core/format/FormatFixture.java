package io.github.ajdailey.qlogg.core.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.function.Executable;

/**
 * Runs {@link PrettyPrint} and checks the whitespace-only contract: the output's tokens, whitespace
 * inside strings, comments and text included, equal the input's, and the two are equal with every
 * whitespace char removed.
 */
final class FormatFixture {
  static final int LIMIT = 1 << 26;

  private FormatFixture() {}

  static String json(String text) {
    String out = PrettyPrint.json(text, LIMIT);
    assertEquals(jsonTokens(text), jsonTokens(out));
    assertEquals(strip(text), strip(out));
    return out;
  }

  static String xml(String text) {
    String out = PrettyPrint.xml(text, LIMIT);
    assertEquals(xmlTokens(text), xmlTokens(out));
    assertEquals(strip(text), strip(out));
    return out;
  }

  static FormatException jsonError(final String text) {
    return assertThrows(
        FormatException.class,
        new Executable() {
          @Override
          public void execute() {
            PrettyPrint.json(text, LIMIT);
          }
        },
        text);
  }

  static FormatException xmlError(final String text) {
    return assertThrows(
        FormatException.class,
        new Executable() {
          @Override
          public void execute() {
            PrettyPrint.xml(text, LIMIT);
          }
        },
        text);
  }

  /** Removes every whitespace char. */
  static String strip(String s) {
    return s.replaceAll("[ \t\r\n]", "");
  }

  /** Splits XML into markup and text, dropping whitespace-only text. */
  static List<String> xmlTokens(String s) {
    List<String> tokens = new ArrayList<String>();
    int i = 0;
    while (i < s.length()) {
      int end;
      if (s.startsWith("<!--", i)) {
        end = s.indexOf("-->", i) + 3;
      } else if (s.startsWith("<![CDATA[", i)) {
        end = s.indexOf("]]>", i) + 3;
      } else if (s.startsWith("<!DOCTYPE", i)) {
        end = i;
        int brackets = 0;
        while (brackets > 0 || s.charAt(end) != '>') {
          char c = s.charAt(end);
          if (c == '"' || c == '\'') {
            end = s.indexOf(c, end + 1);
          } else if (s.startsWith("<!--", end)) {
            end = s.indexOf("-->", end) + 2;
          } else if (c == '[') {
            brackets++;
          } else if (c == ']') {
            brackets--;
          }
          end++;
        }
        end++;
      } else if (s.charAt(i) == '<') {
        end = i + 1;
        char quote = 0;
        while (quote != 0 || s.charAt(end) != '>') {
          char c = s.charAt(end);
          if (quote == 0 && (c == '"' || c == '\'')) {
            quote = c;
          } else if (c == quote) {
            quote = 0;
          }
          end++;
        }
        end++;
      } else {
        end = s.indexOf('<', i);
        end = end < 0 ? s.length() : end;
      }
      String token = s.substring(i, end);
      if (!token.isBlank()) {
        tokens.add(token);
      }
      i = end;
    }
    return tokens;
  }

  /** Splits JSON into strings, comments, punctuation and other runs. */
  static List<String> jsonTokens(String s) {
    List<String> tokens = new ArrayList<String>();
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      int end;
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        i++;
        continue;
      } else if (c == '"') {
        end = i + 1;
        while (s.charAt(end) != '"') {
          end += s.charAt(end) == '\\' ? 2 : 1;
        }
        end++;
      } else if (s.startsWith("//", i)) {
        end = i;
        while (end < s.length() && s.charAt(end) != '\n' && s.charAt(end) != '\r') {
          end++;
        }
      } else if (s.startsWith("/*", i)) {
        end = s.indexOf("*/", i) + 2;
      } else if ("{}[]:,".indexOf(c) >= 0) {
        end = i + 1;
      } else {
        end = i;
        while (end < s.length() && " \t\r\n{}[]:,\"/".indexOf(s.charAt(end)) < 0) {
          end++;
        }
      }
      tokens.add(s.substring(i, end));
      i = end;
    }
    return tokens;
  }
}
