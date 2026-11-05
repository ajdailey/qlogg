package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.text.BoundedChars;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A mark comment template, parsed once. Placeholders:
 *
 * <table>
 *   <caption>Placeholders</caption>
 *   <tr><th>Text</th><th>Expands to</th></tr>
 *   <tr><td>{@code %L}</td><td>The 1-based line number</td></tr>
 *   <tr><td>{@code %F}</td><td>The full path</td></tr>
 *   <tr><td>{@code %P}</td><td>The directory</td></tr>
 *   <tr><td>{@code %N}</td><td>The file name</td></tr>
 *   <tr><td>{@code %M}</td><td>The file name without its extension</td></tr>
 *   <tr><td>{@code %E}</td><td>The extension, without the dot</td></tr>
 *   <tr><td>{@code {regex}{replacement}}</td><td>The line text with every match of the regex
 *       replaced ({@link Matcher#appendReplacement} syntax: {@code $1} is group 1)</td></tr>
 * </table>
 *
 * <p>Any other text is kept, a {@code %} not followed by one of those letters included. A brace
 * pair nests and a backslash escapes the next char, so a regex can hold quantifiers such as {@code
 * {2}}; a {@code {} that does not open a complete {@code {...}{...}} pair is kept as text. Path
 * placeholders expand to nothing for a document without a file. Thread-safe.
 *
 * <p>A regex runs over a {@link BoundedChars} limited to {@code 8 * length + 65,536} reads of the
 * line. A regex that does not compile, reaches that limit, overflows the stack, or a replacement
 * naming a missing group fails the expansion with {@link TemplateException}; the caller then keeps
 * {@link #text()} unexpanded. An expansion is cut at {@link #MAX_EXPANDED_CHARS}.
 */
public final class CommentTemplate {
  /** Longest expansion; the comment's own limit cuts it further. */
  public static final int MAX_EXPANDED_CHARS = 1024;

  /** An expansion failed; carries no stack trace. */
  public static final class TemplateException extends Exception {
    private static final long serialVersionUID = 1L;

    TemplateException(String message) {
      super(message, null, false, false);
    }
  }

  /** One parsed part: literal text, a placeholder letter, or a regex replacement. */
  private record Part(String literal, char placeholder, Pattern regex, String replacement) {}

  private final String text;
  private final List<Part> parts;
  private final String invalid;

  private CommentTemplate(String text, List<Part> parts, String invalid) {
    this.text = text;
    this.parts = parts;
    this.invalid = invalid;
  }

  /**
   * Parses a template.
   *
   * @param text the template
   * @return the template; one whose regex does not compile fails every expansion
   */
  public static CommentTemplate parse(String text) {
    List<Part> parts = new ArrayList<Part>();
    StringBuilder literal = new StringBuilder();
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '%' && i + 1 < text.length() && "LFPNME".indexOf(text.charAt(i + 1)) >= 0) {
        flush(literal, parts);
        parts.add(new Part(null, text.charAt(i + 1), null, null));
        i += 2;
        continue;
      }
      if (c == '{') {
        int regexEnd = closing(text, i);
        int replacementEnd =
            regexEnd > 0 && regexEnd + 1 < text.length() && text.charAt(regexEnd + 1) == '{'
                ? closing(text, regexEnd + 1)
                : -1;
        if (replacementEnd > 0) {
          flush(literal, parts);
          String regex = text.substring(i + 1, regexEnd);
          String replacement = text.substring(regexEnd + 2, replacementEnd);
          try {
            parts.add(new Part(null, '\0', Pattern.compile(regex), replacement));
          } catch (PatternSyntaxException e) {
            return new CommentTemplate(text, List.<Part>of(), e.getDescription());
          }
          i = replacementEnd + 1;
          continue;
        }
      }
      literal.append(c);
      i++;
    }
    flush(literal, parts);
    return new CommentTemplate(text, List.copyOf(parts), null);
  }

  private static void flush(StringBuilder literal, List<Part> parts) {
    if (literal.length() > 0) {
      parts.add(new Part(literal.toString(), '\0', null, null));
      literal.setLength(0);
    }
  }

  /** Returns the index of the brace closing the one at {@code open}, or -1. */
  private static int closing(String text, int open) {
    int depth = 0;
    for (int i = open; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == '{') {
        depth++;
      } else if (c == '}' && --depth == 0) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Returns the template as written.
   *
   * @return the text
   */
  public String text() {
    return text;
  }

  /**
   * Returns why the template cannot expand.
   *
   * @return the regex compile error, or null when it parsed
   */
  public String invalid() {
    return invalid;
  }

  /**
   * Expands the template for one line.
   *
   * @param line the 0-based line
   * @param path the document's path, or null for a document without a file
   * @param lineText the line's text
   * @return the expansion, at most {@link #MAX_EXPANDED_CHARS}
   * @throws TemplateException when a regex does not compile or fails on the line
   */
  public String expand(long line, Path path, CharSequence lineText) throws TemplateException {
    if (invalid != null) {
      throw new TemplateException(invalid);
    }
    StringBuilder out = new StringBuilder();
    BoundedChars chars = null;
    for (Part part : parts) {
      if (out.length() >= MAX_EXPANDED_CHARS) {
        break;
      }
      if (part.literal() != null) {
        out.append(part.literal());
      } else if (part.regex() != null) {
        if (chars == null) {
          chars = new BoundedChars();
        }
        replace(part, chars.reset(lineText, BoundedChars.lineLimit(lineText)), out);
      } else {
        out.append(placeholder(part.placeholder(), line, path));
      }
    }
    if (out.length() > MAX_EXPANDED_CHARS) {
      out.setLength(MAX_EXPANDED_CHARS);
    }
    return out.toString();
  }

  private static void replace(Part part, BoundedChars chars, StringBuilder out)
      throws TemplateException {
    Matcher matcher = part.regex().matcher(chars);
    try {
      while (matcher.find()) {
        matcher.appendReplacement(out, part.replacement());
        if (out.length() >= MAX_EXPANDED_CHARS) {
          return;
        }
      }
      matcher.appendTail(out);
    } catch (BoundedChars.LimitReached e) {
      throw new TemplateException("the regular expression reached its step limit");
    } catch (StackOverflowError e) {
      throw new TemplateException("the regular expression overflowed the stack");
    } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
      throw new TemplateException(String.valueOf(e.getMessage()));
    }
  }

  private static String directory(Path path) {
    Path parent = path.getParent();
    return parent == null ? "" : parent.toString();
  }

  private static String placeholder(char letter, long line, Path path) {
    if (letter == 'L') {
      return Long.toString(line + 1);
    }
    if (path == null) {
      return "";
    }
    Path fileName = path.getFileName();
    String name = fileName == null ? "" : fileName.toString();
    int dot = name.lastIndexOf('.');
    return switch (letter) {
      case 'F' -> path.toString();
      case 'P' -> directory(path);
      case 'N' -> name;
      case 'M' -> dot > 0 ? name.substring(0, dot) : name;
      case 'E' -> dot > 0 ? name.substring(dot + 1) : "";
      default -> "";
    };
  }
}
