package io.github.ajdailey.qlogg.app.i18n;

/** Window and dialog titles taken from menu, command and button labels. */
public final class WindowTitles {
  private static final String ELLIPSIS = "...";

  private WindowTitles() {}

  /**
   * Returns a label as a title: each mnemonic marker removed, {@code &&} as one {@code &}, and a
   * trailing ellipsis removed.
   *
   * @param label a label, for example {@code Rena&me Tab...}
   * @return for example {@code Rename Tab}
   */
  public static String plain(String label) {
    StringBuilder out = new StringBuilder(label.length());
    for (int i = 0; i < label.length(); i++) {
      char c = label.charAt(i);
      if (c != '&') {
        out.append(c);
      } else if (i + 1 < label.length() && label.charAt(i + 1) == '&') {
        out.append('&');
        i++;
      }
    }
    int end = out.length();
    if (end >= ELLIPSIS.length() && out.lastIndexOf(ELLIPSIS) == end - ELLIPSIS.length()) {
      out.setLength(end - ELLIPSIS.length());
    }
    return out.toString();
  }
}
