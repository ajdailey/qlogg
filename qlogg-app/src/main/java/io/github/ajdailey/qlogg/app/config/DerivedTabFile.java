package io.github.ajdailey.qlogg.app.config;

import java.util.Properties;

/**
 * A filter tab's definition in {@code qlogg_session.conf}, under {@code window.W.tab.N.derived.}:
 * {@code parent} ({@code W.N} of the parent tab), {@code text}, the toggles under the {@code
 * searchOption.*} names, {@code scope} (two root lines, first and end, when the search had one) and
 * {@code column} (when not empty). A missing or malformed parent, an empty text or a malformed
 * scope leaves the entry a tab of the root's file; a malformed toggle reads as false and a
 * malformed count as 0.
 */
final class DerivedTabFile {
  private static final String DERIVED = "derived.";
  private static final String PARENT = "parent";
  private static final String TEXT = "text";
  private static final String SCOPE = "scope";
  private static final String COLUMN = "column";

  private DerivedTabFile() {}

  /** Writes a filter tab's definition under a tab's key. */
  static void write(Properties p, String key, Session.Derived derived) {
    String at = key + DERIVED;
    p.setProperty(at + PARENT, derived.parentWindow() + "." + derived.parentTab());
    p.setProperty(at + TEXT, derived.text());
    SearchToggles t = derived.toggles();
    p.setProperty(at + OptionsFile.SEARCH_REGEX, Boolean.toString(t.regex()));
    p.setProperty(at + OptionsFile.SEARCH_CASE, Boolean.toString(t.caseSensitive()));
    p.setProperty(at + OptionsFile.SEARCH_WORD, Boolean.toString(t.wholeWord()));
    p.setProperty(at + OptionsFile.SEARCH_INVERSE, Boolean.toString(t.inverse()));
    p.setProperty(at + OptionsFile.SEARCH_COMBINING, Boolean.toString(t.combining()));
    p.setProperty(at + OptionsFile.SEARCH_REFRESH, Boolean.toString(t.autoRefresh()));
    p.setProperty(at + OptionsFile.SEARCH_KEEP, Boolean.toString(t.keepResults()));
    p.setProperty(at + OptionsFile.SEARCH_RANGE, Boolean.toString(t.range()));
    p.setProperty(at + OptionsFile.SEARCH_RANGE_END, t.rangeEnd());
    p.setProperty(at + OptionsFile.SEARCH_BEFORE, Integer.toString(t.contextBefore()));
    p.setProperty(at + OptionsFile.SEARCH_AFTER, Integer.toString(t.contextAfter()));
    if (derived.scopeFirst() >= 0) {
      p.setProperty(at + SCOPE, derived.scopeFirst() + "," + derived.scopeEnd());
    }
    if (!derived.column().isEmpty()) {
      p.setProperty(at + COLUMN, derived.column());
    }
  }

  /**
   * Reads a tab's filter-tab definition.
   *
   * @return the definition, or null for a file tab or a dropped entry
   */
  static Session.Derived read(Properties p, String key) {
    String at = key + DERIVED;
    String parent = p.getProperty(at + PARENT);
    String text = p.getProperty(at + TEXT, "");
    if (parent == null || text.isEmpty()) {
      return null;
    }
    long[] link = pair(parent.strip(), '.');
    long[] scope = pair(p.getProperty(at + SCOPE, "-1,-1").strip(), ',');
    if (link.length == 0
        || scope.length == 0
        || link[0] < 0
        || link[1] < 0
        || link[0] > Integer.MAX_VALUE
        || link[1] > Integer.MAX_VALUE
        || (scope[0] >= 0 && scope[1] < scope[0])) {
      return null;
    }
    SearchToggles toggles =
        new SearchToggles(
            flag(p, at + OptionsFile.SEARCH_REGEX),
            flag(p, at + OptionsFile.SEARCH_CASE),
            flag(p, at + OptionsFile.SEARCH_WORD),
            flag(p, at + OptionsFile.SEARCH_INVERSE),
            flag(p, at + OptionsFile.SEARCH_COMBINING),
            flag(p, at + OptionsFile.SEARCH_REFRESH),
            flag(p, at + OptionsFile.SEARCH_KEEP),
            flag(p, at + OptionsFile.SEARCH_RANGE),
            p.getProperty(at + OptionsFile.SEARCH_RANGE_END, ""),
            context(p, at + OptionsFile.SEARCH_BEFORE),
            context(p, at + OptionsFile.SEARCH_AFTER));
    return new Session.Derived(
        (int) link[0],
        (int) link[1],
        text,
        toggles,
        scope[0] < 0 ? -1 : scope[0],
        scope[0] < 0 ? -1 : scope[1],
        p.getProperty(at + COLUMN, ""));
  }

  /** Reads {@code a<separator>b}; empty when malformed. */
  private static long[] pair(String text, char separator) {
    int split = text.indexOf(separator);
    if (split <= 0) {
      return new long[0];
    }
    try {
      return new long[] {
        Long.parseLong(text.substring(0, split)), Long.parseLong(text.substring(split + 1))
      };
    } catch (NumberFormatException e) {
      return new long[0];
    }
  }

  private static boolean flag(Properties p, String key) {
    return "true".equals(p.getProperty(key, "false").strip());
  }

  private static int context(Properties p, String key) {
    try {
      int count = Integer.parseInt(p.getProperty(key, "0").strip());
      return count < 0 || count > SearchToggles.MAX_CONTEXT ? 0 : count;
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
