package io.github.ajdailey.qlogg.core.format;

/**
 * Whitespace-only pretty print of JSON and XML with a two-space indent and LF line breaks. Only the
 * gaps between tokens change: strings, comments, element text, mixed content, {@code
 * xml:space="preserve"} content, CDATA, PIs and the DOCTYPE stay verbatim. Time and memory are
 * linear in the input and the output limit whatever the nesting. Stateless.
 */
public final class PrettyPrint {
  private PrettyPrint() {}

  /**
   * Lays out one JSON value; {@code //} and block comments are kept.
   *
   * @param text the JSON text
   * @param maxChars the longest output
   * @return the laid-out text
   * @throws FormatException at the first error; {@link FormatException.Reason#TOO_LONG} only for a
   *     text without one
   */
  public static String json(String text, int maxChars) {
    Output out = new Output(text, maxChars);
    try {
      // A check-only pass first, so an error in a deep text is not reported as TOO_LONG.
      JsonLayout.layout(text, new Output(text, -1));
      JsonLayout.layout(text, out);
    } catch (FormatException e) {
      throw e.in(text);
    }
    return out.text();
  }

  /**
   * Lays out an XML document or several top-level items.
   *
   * @param text the XML text
   * @param maxChars the longest output
   * @return the laid-out text
   * @throws FormatException at the first error; {@link FormatException.Reason#TOO_LONG} only for a
   *     text without one
   */
  public static String xml(String text, int maxChars) {
    Output out = new Output(text, maxChars);
    try {
      XmlLayout.layout(text, out);
    } catch (FormatException e) {
      throw e.in(text);
    }
    return out.text();
  }
}
