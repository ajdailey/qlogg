package io.github.ajdailey.qlogg.core.model;

/** What a syntax-colored token of a log line is; {@link LogTokens} assigns it. */
public enum TokenKind {
  /** Stack frames, {@code ... N more} and trace levels. */
  COMMENT,
  /** {@code Caused by}, {@code Suppressed}, {@code Traceback} and exception names. */
  KEYWORD,
  /** Timestamps. */
  NUMBER,
  /** Error and fatal levels. */
  INVALID,
  /** Warning levels, and an escape inside a quoted string. */
  ESCAPE,
  /** Info levels. */
  TYPE,
  /** Debug levels. */
  KEYWORD2,
  /** A bracketed group such as {@code [thread]}. */
  DELIMITER,
  /** URLs. */
  LINK,
  /** Qualified class names. */
  FUNCTION,
  /** The name of a {@code key=value} pair. */
  ATTRIBUTE,
  /** Double-quoted strings. */
  STRING
}
