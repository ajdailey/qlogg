package io.github.ajdailey.qlogg.core.format;

/** A text that did not format or unescape, at an offset with its 1-based line and column. */
public final class FormatException extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;

  /** Why the text was refused. */
  public enum Reason {
    /** The formatted text would exceed the output limit. */
    TOO_LONG,
    /** JSON: no value where one must stand, or an empty text. */
    EXPECTED_VALUE,
    /** JSON: an object's first member name or its {@code '}'} is missing. */
    EXPECTED_NAME_OR_CLOSE,
    /** JSON: a member name is missing after a comma. */
    EXPECTED_NAME,
    /** JSON: the {@code ':'} after a member name is missing. */
    EXPECTED_COLON,
    /** JSON: an object member is followed by neither {@code ','} nor {@code '}'}. */
    EXPECTED_COMMA_OR_BRACE,
    /** JSON: an array element is followed by neither {@code ','} nor {@code ']'}. */
    EXPECTED_COMMA_OR_BRACKET,
    /** JSON: text after the top-level value. */
    TEXT_AFTER_VALUE,
    /** JSON: the text ends inside a container. */
    UNEXPECTED_END,
    /** JSON: a control character below U+0020 inside a string. */
    CONTROL_CHARACTER,
    /** JSON: a backslash-u escape without four hex digits. */
    INVALID_UNICODE_ESCAPE,
    /** JSON: a backslash escape that is not valid. */
    INVALID_ESCAPE,
    /** JSON unescape: a surrogate escape without its escaped pair. */
    LONE_SURROGATE_ESCAPE,
    /** JSON: a string without its closing quote. */
    UNTERMINATED_STRING,
    /** JSON: a number that does not follow the grammar. */
    INVALID_NUMBER,
    /** JSON: a {@code '/'} that starts no comment. */
    UNEXPECTED_SLASH,
    /** JSON block comment or XML comment without its end. */
    UNTERMINATED_COMMENT,
    /** XML: a tag without its {@code '>'}. */
    UNTERMINATED_TAG,
    /** XML: an attribute value without its closing quote. */
    UNTERMINATED_QUOTE,
    /** XML: a CDATA section without {@code ]]>}. */
    UNTERMINATED_CDATA,
    /** XML: a processing instruction without {@code ?>}. */
    UNTERMINATED_PI,
    /** XML: a DOCTYPE without its {@code '>'}. */
    UNTERMINATED_DOCTYPE,
    /** XML: an end tag that does not match the open element, named by {@link #detail()}. */
    MISMATCHED_END_TAG,
    /** XML: an end tag, named by {@link #detail()}, with no element open. */
    UNOPENED_END_TAG,
    /** XML: the element named by {@link #detail()} is still open at the end of the text. */
    UNCLOSED_ELEMENT
  }

  private final Reason reason;
  private final int offset;
  private final String detail;
  private final int line;
  private final int column;

  FormatException(Reason reason, int offset, String detail) {
    this(reason, offset, detail, 0, 0);
  }

  private FormatException(Reason reason, int offset, String detail, int line, int column) {
    super(reason.name(), null);
    this.reason = reason;
    this.offset = offset;
    this.detail = detail == null ? "" : detail;
    this.line = line;
    this.column = column;
  }

  /** Returns this failure with the line and column of its offset in {@code text}. */
  FormatException in(String text) {
    int lineStart = 0;
    int lines = 1;
    for (int i = 0; i < offset && i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n' || c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n')) {
        lines++;
        lineStart = i + 1;
      }
    }
    return new FormatException(reason, offset, detail, lines, offset - lineStart + 1);
  }

  /**
   * Returns the reason.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }

  /**
   * Returns the offset of the failure in the text.
   *
   * @return the offset, in chars
   */
  public int offset() {
    return offset;
  }

  /**
   * Returns the element name the XML tag reasons name.
   *
   * @return the name, or empty
   */
  public String detail() {
    return detail;
  }

  /**
   * Returns the 1-based line of the offset; {@code \n}, {@code \r\n} and {@code \r} end a line.
   *
   * @return the line
   */
  public int line() {
    return line;
  }

  /**
   * Returns the 1-based column of the offset, in chars, so a supplementary character counts two.
   *
   * @return the column
   */
  public int column() {
    return column;
  }
}
