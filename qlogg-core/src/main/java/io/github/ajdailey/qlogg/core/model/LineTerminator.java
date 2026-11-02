package io.github.ajdailey.qlogg.core.model;

/** How a line ends in the file. */
public enum LineTerminator {
  /** LF. */
  LF,
  /** CR then LF. */
  CRLF,
  /** Nothing: the last line of a file that does not end with LF. */
  NONE
}
