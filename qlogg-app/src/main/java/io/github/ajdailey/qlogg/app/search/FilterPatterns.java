package io.github.ajdailey.qlogg.app.search;

import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.util.List;

/**
 * Builds search text from predefined filters and reads back which filters a text holds. Without
 * combining, filters join as regex alternatives ({@code a|b}), a {@code LITERAL} filter escaped;
 * with combining, each is a quoted term joined with {@code or}. Composed text is searched as a
 * regex.
 */
public final class FilterPatterns {
  private static final String REGEX_SPECIALS = "\\^$.|?*+()[]{}";

  private FilterPatterns() {}

  /**
   * Returns a filter as it appears in the search text.
   *
   * @param filter the filter
   * @param combining true for a quoted term
   * @return the text
   */
  public static String render(PredefinedFilter filter, boolean combining) {
    String regex =
        filter.mode() == MatchMode.REGEX ? filter.pattern() : escapeRegex(filter.pattern());
    return combining ? quote(regex) : regex;
  }

  /**
   * Joins filters into search text.
   *
   * @param filters the checked filters, in menu order
   * @param combining true for quoted terms joined with {@code or}
   * @return the text, empty for no filters
   */
  public static String compose(List<PredefinedFilter> filters, boolean combining) {
    StringBuilder text = new StringBuilder();
    for (PredefinedFilter filter : filters) {
      if (text.length() > 0) {
        text.append(combining ? " or " : "|");
      }
      text.append(render(filter, combining));
    }
    return text.toString();
  }

  /**
   * Reports which filters the text holds: a filter is present when its rendered form stands between
   * separators ({@code |} without combining; a space or parenthesis with combining) or at an end of
   * the text.
   *
   * @param text the search text
   * @param filters the filters
   * @param combining how the text was composed
   * @return one flag per filter
   */
  public static boolean[] present(String text, List<PredefinedFilter> filters, boolean combining) {
    boolean[] found = new boolean[filters.size()];
    for (int i = 0; i < found.length; i++) {
      String rendered = render(filters.get(i), combining);
      found[i] = !rendered.isEmpty() && standsAlone(text, rendered, combining);
    }
    return found;
  }

  private static boolean standsAlone(String text, String part, boolean combining) {
    int at = text.indexOf(part);
    while (at >= 0) {
      int end = at + part.length();
      boolean before = at == 0 || separator(text.charAt(at - 1), combining, true);
      boolean after = end == text.length() || separator(text.charAt(end), combining, false);
      if (before && after) {
        return true;
      }
      at = text.indexOf(part, at + 1);
    }
    return false;
  }

  private static boolean separator(char c, boolean combining, boolean before) {
    if (!combining) {
      return c == '|';
    }
    return Character.isWhitespace(c) || c == (before ? '(' : ')');
  }

  /**
   * Escapes regex metacharacters with a backslash, so the text matches as typed.
   *
   * @param text the text
   * @return the escaped text
   */
  public static String escapeRegex(String text) {
    StringBuilder out = new StringBuilder(text.length() + 8);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (REGEX_SPECIALS.indexOf(c) >= 0) {
        out.append('\\');
      }
      out.append(c);
    }
    return out.toString();
  }

  /**
   * Quotes a term for combining syntax, escaping each double quote.
   *
   * @param term the term
   * @return the quoted term
   */
  public static String quote(String term) {
    return "\"" + term.replace("\"", "\\\"") + "\"";
  }
}
