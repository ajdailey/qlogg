package io.github.ajdailey.qlogg.app.scratch;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * The scratchpad's built-in transforms and derived values. Text converts to bytes as UTF-8, and
 * decoded bytes back to text as UTF-8 with malformed input replaced. JSON and XML are reformatted
 * by a scan of brackets, quotes and tags, not parsed, so no entity, DTD or external reference is
 * read. Each transform takes and returns a string; nothing runs code from the text. Stateless.
 *
 * <p>Input and output are at most {@link #MAX_CHARS} chars, and JSON and XML indent at most {@link
 * #MAX_DEPTH} levels, so a transform's time and memory are linear in {@code MAX_CHARS} whatever the
 * nesting.
 */
public final class Transforms {
  /** Most chars a transform takes or returns. */
  public static final int MAX_CHARS = 1 << 20;

  /** Deepest indentation level; deeper JSON and XML levels indent as this one. */
  static final int MAX_DEPTH = 32;

  /** Indent per nesting level. */
  static final String INDENT = "  ";

  /** The input or the output would exceed {@link #MAX_CHARS}. */
  public static final class TooLongException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    TooLongException() {
      super(UiText.SCRATCHPAD_TOO_LONG.format(MAX_CHARS));
    }
  }

  /** One scratchpad transform. */
  public enum Kind {
    /** UTF-8 bytes to Base64. */
    BASE64_ENCODE(UiText.TRANSFORM_BASE64_ENCODE),
    /** Base64, whitespace ignored, to UTF-8 text. */
    BASE64_DECODE(UiText.TRANSFORM_BASE64_DECODE),
    /** UTF-8 bytes to lowercase hex. */
    HEX_ENCODE(UiText.TRANSFORM_HEX_ENCODE),
    /** Hex digit pairs, whitespace and a {@code 0x} prefix ignored, to UTF-8 text. */
    HEX_DECODE(UiText.TRANSFORM_HEX_DECODE),
    /** {@code %XX} escapes and {@code +} decoded as UTF-8. */
    URL_DECODE(UiText.TRANSFORM_URL_DECODE),
    /** JSON indented two spaces per level. */
    JSON_FORMAT(UiText.TRANSFORM_JSON_FORMAT),
    /** XML indented two spaces per element level. */
    XML_FORMAT(UiText.TRANSFORM_XML_FORMAT);

    private final UiText label;

    Kind(UiText label) {
      this.label = label;
    }

    /**
     * Returns the menu label.
     *
     * @return the label
     */
    public String label() {
      return label.text();
    }
  }

  private Transforms() {}

  /**
   * Applies a transform.
   *
   * @param kind the transform
   * @param text the input
   * @return the output
   * @throws TooLongException when the input or the output exceeds {@link #MAX_CHARS}
   * @throws IllegalArgumentException naming the reason when the input does not fit the transform
   */
  public static String apply(Kind kind, String text) {
    check(text.length());
    String output =
        switch (kind) {
          case BASE64_ENCODE -> base64(text.getBytes(StandardCharsets.UTF_8));
          case BASE64_DECODE ->
              new String(
                  Base64.getDecoder().decode(text.replaceAll("\\s", "")), StandardCharsets.UTF_8);
          case HEX_ENCODE -> hex(text.getBytes(StandardCharsets.UTF_8));
          case HEX_DECODE -> new String(unhex(text), StandardCharsets.UTF_8);
          case URL_DECODE -> URLDecoder.decode(text, StandardCharsets.UTF_8);
          case JSON_FORMAT -> formatJson(text);
          case XML_FORMAT -> formatXml(text);
        };
    check(output.length());
    return output;
  }

  private static void check(long chars) {
    if (chars > MAX_CHARS) {
      throw new TooLongException();
    }
  }

  private static String base64(byte[] bytes) {
    check((bytes.length + 2L) / 3 * 4);
    return Base64.getEncoder().encodeToString(bytes);
  }

  /**
   * Returns the values derived from a text: its CRC32, and, when it is a number, the number in the
   * other base and, for 10 or 13 digits, the epoch time in UTC and in a zone.
   *
   * @param text the selection, stripped
   * @param zone the local zone
   * @return one line per value
   */
  public static List<String> values(String text, ZoneId zone) {
    List<String> values = new ArrayList<String>();
    CRC32 crc = new CRC32();
    crc.update(text.getBytes(StandardCharsets.UTF_8));
    long sum = crc.getValue();
    values.add(UiText.VALUE_CRC32.format(String.format(Locale.ROOT, "%08x", sum), sum));
    String number = text.strip();
    Long value = null;
    if (number.matches("-?\\d{1,18}")) {
      value = Long.parseLong(number);
      values.add(UiText.VALUE_NUMBER.format(number, "0x" + Long.toHexString(value)));
    } else if (number.matches("(0[xX])?[0-9a-fA-F]{1,15}")) {
      String digits =
          number.startsWith("0x") || number.startsWith("0X") ? number.substring(2) : number;
      values.add(UiText.VALUE_NUMBER.format(number, Long.parseLong(digits, 16)));
    }
    if (value != null && (number.length() == 10 || number.length() == 13)) {
      Instant instant =
          number.length() == 10 ? Instant.ofEpochSecond(value) : Instant.ofEpochMilli(value);
      DateTimeFormatter format =
          DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
      values.add(
          UiText.VALUE_EPOCH.format(
              format.format(instant.atZone(ZoneOffset.UTC)), format.format(instant.atZone(zone))));
    }
    return values;
  }

  static String hex(byte[] bytes) {
    check(bytes.length * 2L);
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
    }
    return out.toString();
  }

  static byte[] unhex(String text) {
    String digits = text.replaceAll("\\s", "");
    if (digits.startsWith("0x") || digits.startsWith("0X")) {
      digits = digits.substring(2);
    }
    if (digits.length() % 2 != 0) {
      throw new IllegalArgumentException(UiText.TRANSFORM_ODD_HEX.text());
    }
    byte[] bytes = new byte[digits.length() / 2];
    for (int i = 0; i < bytes.length; i++) {
      int high = Character.digit(digits.charAt(2 * i), 16);
      int low = Character.digit(digits.charAt(2 * i + 1), 16);
      if (high < 0 || low < 0) {
        throw new IllegalArgumentException(UiText.TRANSFORM_NOT_HEX.format(2 * i + 1));
      }
      bytes[i] = (byte) (high << 4 | low);
    }
    return bytes;
  }

  /**
   * Indents JSON: a line break after each opening bracket and comma and before each closing one.
   */
  static String formatJson(String text) {
    StringBuilder out = new StringBuilder();
    int depth = 0;
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        out.append(c);
        if (c == '\\' && i + 1 < text.length()) {
          out.append(text.charAt(++i));
        } else if (c == '"') {
          quoted = false;
        }
        continue;
      }
      switch (c) {
        case '"' -> {
          quoted = true;
          out.append(c);
        }
        case '{', '[' -> {
          out.append(c);
          depth++;
          newline(out, depth);
        }
        case '}', ']' -> {
          depth--;
          if (depth < 0) {
            throw new IllegalArgumentException(UiText.TRANSFORM_UNBALANCED_AT.format(c, i + 1));
          }
          trimTrailing(out);
          newline(out, depth);
          out.append(c);
        }
        case ',' -> {
          out.append(c);
          newline(out, depth);
        }
        case ':' -> out.append(": ");
        case ' ', '\t', '\r', '\n' -> {
          // Whitespace between tokens is replaced by the layout.
        }
        default -> out.append(c);
      }
    }
    if (depth != 0 || quoted) {
      throw new IllegalArgumentException(
          quoted
              ? UiText.TRANSFORM_UNTERMINATED_STRING.text()
              : UiText.TRANSFORM_UNBALANCED.text());
    }
    return collapseEmpty(out.toString());
  }

  /** Joins {@code {}} and {@code []} that the layout split over lines. */
  private static String collapseEmpty(String text) {
    return text.replaceAll("\\{\\s+}", "{}").replaceAll("\\[\\s+]", "[]");
  }

  /** Indents XML: one tag per line, text between tags kept on its own line. */
  static String formatXml(String text) {
    StringBuilder out = new StringBuilder();
    int depth = 0;
    int i = 0;
    while (i < text.length()) {
      if (text.charAt(i) != '<') {
        int next = text.indexOf('<', i);
        int end = next < 0 ? text.length() : next;
        String content = text.substring(i, end).strip();
        if (!content.isEmpty()) {
          line(out, depth, content);
        }
        i = end;
        continue;
      }
      int end = tagEnd(text, i);
      String tag = text.substring(i, end);
      if (tag.startsWith("</")) {
        depth = Math.max(0, depth - 1);
        line(out, depth, tag);
      } else {
        line(out, depth, tag);
        boolean opens = !tag.startsWith("<?") && !tag.startsWith("<!") && !tag.endsWith("/>");
        if (opens) {
          depth++;
        }
      }
      i = end;
    }
    return out.toString().stripTrailing();
  }

  /** The index after the tag, comment, CDATA section or declaration starting at {@code at}. */
  private static int tagEnd(String text, int at) {
    String closer =
        text.startsWith("<!--", at) ? "-->" : text.startsWith("<![CDATA[", at) ? "]]>" : ">";
    int end = text.indexOf(closer, at + 1);
    if (end < 0) {
      throw new IllegalArgumentException(UiText.TRANSFORM_UNTERMINATED_TAG.format(at + 1));
    }
    return end + closer.length();
  }

  private static void line(StringBuilder out, int depth, String text) {
    indent(out, depth);
    out.append(text).append('\n');
    check(out.length());
  }

  private static void newline(StringBuilder out, int depth) {
    out.append('\n');
    indent(out, depth);
    check(out.length());
  }

  private static void indent(StringBuilder out, int depth) {
    int levels = Math.min(depth, MAX_DEPTH);
    for (int i = 0; i < levels; i++) {
      out.append(INDENT);
    }
  }

  private static void trimTrailing(StringBuilder out) {
    int end = out.length();
    while (end > 0 && (out.charAt(end - 1) == ' ' || out.charAt(end - 1) == '\n')) {
      end--;
    }
    out.setLength(end);
  }
}
