package io.github.ajdailey.qlogg.core.format;

/**
 * The formatted text, built from verbatim copies of the source and replaced gaps in source order; a
 * gap a layout does not write is dropped. Growth past the limit throws {@link
 * FormatException.Reason#TOO_LONG}. A check-only output collects nothing.
 */
final class Output {
  /** One indent level. */
  static final String INDENT = "  ";

  private final String source;
  private final StringBuilder out;
  private final int maxChars;

  /**
   * Creates an output.
   *
   * @param source the text laid out
   * @param maxChars the longest output; negative for a check-only output
   */
  Output(String source, int maxChars) {
    this.source = source;
    this.maxChars = maxChars;
    this.out = maxChars < 0 ? null : new StringBuilder(Math.min(source.length(), maxChars));
  }

  /** Copies {@code source[from, to)} unchanged. */
  void copy(int from, int to) {
    if (out == null) {
      return;
    }
    reserve(to - from, from);
    out.append(source, from, to);
  }

  /** Replaces the gap at {@code from} with a line break and {@code depth} indent levels. */
  void newline(int from, int depth) {
    if (out == null) {
      return;
    }
    reserve(1 + (long) INDENT.length() * depth, from);
    out.append('\n');
    for (int i = 0; i < depth; i++) {
      out.append(INDENT);
    }
  }

  /** Replaces the gap at {@code from} with one space. */
  void space(int from) {
    if (out == null) {
      return;
    }
    reserve(1, from);
    out.append(' ');
  }

  String text() {
    return out.toString();
  }

  private void reserve(long chars, int at) {
    if (out.length() + chars > maxChars) {
      throw new FormatException(FormatException.Reason.TOO_LONG, at, null);
    }
  }
}
