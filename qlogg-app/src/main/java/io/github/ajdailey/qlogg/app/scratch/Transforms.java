package io.github.ajdailey.qlogg.app.scratch;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.format.FormatException;
import io.github.ajdailey.qlogg.core.format.JsonStrings;
import io.github.ajdailey.qlogg.core.format.PrettyPrint;
import io.github.ajdailey.qlogg.core.format.XmlEntities;
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
 * decoded bytes back to text as UTF-8 with malformed input replaced. JSON and XML format and
 * unescape run in {@code core.format}, which reads no entity, DTD or external reference; a refusal
 * names the reason, line and column. Each transform takes and returns a string; nothing runs code
 * from the text. Stateless.
 *
 * <p>Input and output are at most {@link #MAX_CHARS} chars, so a transform's time and memory are
 * linear in {@code MAX_CHARS} whatever the nesting.
 */
public final class Transforms {
  /** Most chars a transform takes or returns. */
  public static final int MAX_CHARS = 1 << 20;

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
    /** JSON laid out two spaces per level; only whitespace between tokens changes. */
    JSON_FORMAT(UiText.TRANSFORM_JSON_FORMAT),
    /** XML laid out two spaces per element level; only whitespace between items changes. */
    XML_FORMAT(UiText.TRANSFORM_XML_FORMAT),
    /** JSON string escapes to chars. */
    JSON_UNESCAPE(UiText.TRANSFORM_JSON_UNESCAPE),
    /** XML and HTML 4.01 entities and character references to chars. */
    XML_UNESCAPE(UiText.TRANSFORM_XML_UNESCAPE);

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
    String output;
    try {
      output = transform(kind, text);
    } catch (FormatException e) {
      throw new IllegalArgumentException(
          UiText.TRANSFORM_FORMAT_ERROR.format(reason(e), e.line(), e.column()), e);
    }
    check(output.length());
    return output;
  }

  private static String transform(Kind kind, String text) {
    return switch (kind) {
      case BASE64_ENCODE -> base64(text.getBytes(StandardCharsets.UTF_8));
      case BASE64_DECODE ->
          new String(
              Base64.getDecoder().decode(text.replaceAll("\\s", "")), StandardCharsets.UTF_8);
      case HEX_ENCODE -> hex(text.getBytes(StandardCharsets.UTF_8));
      case HEX_DECODE -> new String(unhex(text), StandardCharsets.UTF_8);
      case URL_DECODE -> URLDecoder.decode(text, StandardCharsets.UTF_8);
      case JSON_FORMAT -> PrettyPrint.json(text, MAX_CHARS);
      case XML_FORMAT -> PrettyPrint.xml(text, MAX_CHARS);
      case JSON_UNESCAPE -> JsonStrings.unescape(text);
      case XML_UNESCAPE -> XmlEntities.unescape(text);
    };
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

  /** Returns a failure's reason text; throws {@link TooLongException} for {@code TOO_LONG}. */
  private static String reason(FormatException e) {
    UiText text =
        switch (e.reason()) {
          case TOO_LONG -> throw new TooLongException();
          case EXPECTED_VALUE -> UiText.FORMAT_EXPECTED_VALUE;
          case EXPECTED_NAME_OR_CLOSE -> UiText.FORMAT_EXPECTED_NAME_OR_CLOSE;
          case EXPECTED_NAME -> UiText.FORMAT_EXPECTED_NAME;
          case EXPECTED_COLON -> UiText.FORMAT_EXPECTED_COLON;
          case EXPECTED_COMMA_OR_BRACE -> UiText.FORMAT_EXPECTED_COMMA_OR_BRACE;
          case EXPECTED_COMMA_OR_BRACKET -> UiText.FORMAT_EXPECTED_COMMA_OR_BRACKET;
          case TEXT_AFTER_VALUE -> UiText.FORMAT_TEXT_AFTER_VALUE;
          case UNEXPECTED_END -> UiText.FORMAT_UNEXPECTED_END;
          case CONTROL_CHARACTER -> UiText.FORMAT_CONTROL_CHARACTER;
          case INVALID_UNICODE_ESCAPE -> UiText.FORMAT_INVALID_UNICODE_ESCAPE;
          case INVALID_ESCAPE -> UiText.FORMAT_INVALID_ESCAPE;
          case LONE_SURROGATE_ESCAPE -> UiText.FORMAT_LONE_SURROGATE_ESCAPE;
          case UNTERMINATED_STRING -> UiText.FORMAT_UNTERMINATED_STRING;
          case INVALID_NUMBER -> UiText.FORMAT_INVALID_NUMBER;
          case UNEXPECTED_SLASH -> UiText.FORMAT_UNEXPECTED_SLASH;
          case UNTERMINATED_COMMENT -> UiText.FORMAT_UNTERMINATED_COMMENT;
          case UNTERMINATED_TAG -> UiText.FORMAT_UNTERMINATED_TAG;
          case UNTERMINATED_QUOTE -> UiText.FORMAT_UNTERMINATED_QUOTE;
          case UNTERMINATED_CDATA -> UiText.FORMAT_UNTERMINATED_CDATA;
          case UNTERMINATED_PI -> UiText.FORMAT_UNTERMINATED_PI;
          case UNTERMINATED_DOCTYPE -> UiText.FORMAT_UNTERMINATED_DOCTYPE;
          case MISMATCHED_END_TAG -> UiText.FORMAT_MISMATCHED_END_TAG;
          case UNOPENED_END_TAG -> UiText.FORMAT_UNOPENED_END_TAG;
          case UNCLOSED_ELEMENT -> UiText.FORMAT_UNCLOSED_ELEMENT;
        };
    return text.format(e.detail());
  }
}
