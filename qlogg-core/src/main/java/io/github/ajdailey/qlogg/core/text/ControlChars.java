package io.github.ajdailey.qlogg.core.text;

/**
 * The control chars a log view draws as a box holding their two uppercase hex digits: C0 ({@code
 * U+0000}-{@code U+001F}) except TAB, DEL ({@code U+007F}) and C1 ({@code U+0080}-{@code U+009F}).
 * Each takes {@link #WIDTH} display columns. The box is display only: copy, export and search keep
 * the char.
 */
public final class ControlChars {
  /** Display columns of a boxed char. */
  public static final int WIDTH = 2;

  private static final String[] LABELS = new String[0xa0];

  static {
    for (int c = 0; c < LABELS.length; c++) {
      LABELS[c] = String.format("%02X", c);
    }
  }

  private ControlChars() {}

  /**
   * Reports whether a char draws as a box.
   *
   * @param c the char
   * @return true for C0 except TAB, DEL and C1
   */
  public static boolean boxed(char c) {
    return c < 0xa0 && (c < 0x20 ? c != '\t' : c >= 0x7f);
  }

  /**
   * Returns the box's text.
   *
   * @param c a char that {@link #boxed} accepts
   * @return its two uppercase hex digits, the same instance on every call
   */
  public static String label(char c) {
    return LABELS[c];
  }

  /**
   * Returns text for a native table cell, which draws no control char: each {@link #boxed} char as
   * {@code <}, its {@link #label} and {@code >}.
   *
   * @param text the text
   * @return the text, the same instance when it holds no boxed char
   */
  public static String visible(String text) {
    StringBuilder shown = null;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (boxed(c)) {
        if (shown == null) {
          shown = new StringBuilder(text.length() + 8);
          shown.append(text, 0, i);
        }
        shown.append('<').append(LABELS[c]).append('>');
      } else if (shown != null) {
        shown.append(c);
      }
    }
    return shown == null ? text : shown.toString();
  }
}
