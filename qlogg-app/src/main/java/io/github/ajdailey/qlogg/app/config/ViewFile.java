package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.app.highlight.HighlightSelector;
import io.github.ajdailey.qlogg.core.model.FoldRules;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.time.SpreadMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes {@link ViewOptions} as {@code qlogg.conf} properties. A value that does not
 * parse falls back to its default with one WARN naming the key; a selector entry that is not
 * complete is dropped. Values are never logged.
 */
final class ViewFile {
  static final String WRAP_KEY = "view.wrapOnOpen";
  static final String WHITESPACE_KEY = "view.showWhitespace";
  static final String ANSI_KEY = "view.hideAnsi";
  static final String FOLD_START_KEY = "fold.entryStart";
  static final String FOLD_CONTINUATION_KEY = "fold.continuation.";
  static final String SELECTOR_KEY = "highlight.selector.";
  // Time spread.
  static final String TIME_SPREAD_KEY = "view.timeSpread";

  /** Most continuation patterns read. */
  static final int MAX_CONTINUATION = 64;

  private static final Logger LOG = LoggerFactory.getLogger(ViewFile.class);

  private ViewFile() {}

  /**
   * Reads the view settings; absent keys keep their defaults.
   *
   * @param properties the file's properties
   * @return the settings
   */
  static ViewOptions load(Properties properties) {
    ViewOptions defaults = ViewOptions.DEFAULTS;
    FoldRules folds = defaults.folds();
    String start = properties.getProperty(FOLD_START_KEY);
    List<String> continuation = new ArrayList<String>();
    for (int i = 0; i < MAX_CONTINUATION; i++) {
      String pattern = properties.getProperty(FOLD_CONTINUATION_KEY + i);
      if (pattern == null) {
        break;
      }
      continuation.add(pattern);
    }
    if (start != null || !continuation.isEmpty()) {
      folds =
          new FoldRules(
              start == null ? "" : start,
              continuation.isEmpty() ? folds.continuation() : continuation);
    }
    List<HighlightSelector> selectors = new ArrayList<HighlightSelector>();
    for (int i = 0; i < HighlightSelector.MAX_SELECTORS; i++) {
      String set = properties.getProperty(SELECTOR_KEY + i + ".set");
      if (set == null) {
        break;
      }
      try {
        selectors.add(
            new HighlightSelector(
                properties.getProperty(SELECTOR_KEY + i + ".glob", ""),
                properties.getProperty(SELECTOR_KEY + i + ".firstLine", ""),
                set));
      } catch (IllegalArgumentException e) {
        LOG.warn("Ignoring an invalid {}", LogText.oneLine(SELECTOR_KEY + i));
      }
    }
    return new ViewOptions(
        flag(properties, WRAP_KEY, defaults.wrapOnOpen()),
        flag(properties, WHITESPACE_KEY, defaults.showWhitespace()),
        flag(properties, ANSI_KEY, defaults.hideAnsi()),
        folds,
        selectors,
        spread(properties, defaults.timeSpread()));
  }

  /**
   * Writes the view settings.
   *
   * @param view the settings
   * @param properties receives the keys
   */
  static void store(ViewOptions view, Properties properties) {
    properties.setProperty(WRAP_KEY, Boolean.toString(view.wrapOnOpen()));
    properties.setProperty(WHITESPACE_KEY, Boolean.toString(view.showWhitespace()));
    properties.setProperty(ANSI_KEY, Boolean.toString(view.hideAnsi()));
    properties.setProperty(TIME_SPREAD_KEY, view.timeSpread().id());
    properties.setProperty(FOLD_START_KEY, view.folds().entryStart());
    List<String> continuation = view.folds().continuation();
    for (int i = 0; i < continuation.size() && i < MAX_CONTINUATION; i++) {
      properties.setProperty(FOLD_CONTINUATION_KEY + i, continuation.get(i));
    }
    List<HighlightSelector> selectors = view.selectors();
    for (int i = 0; i < selectors.size(); i++) {
      HighlightSelector selector = selectors.get(i);
      properties.setProperty(SELECTOR_KEY + i + ".glob", selector.fileNameGlob());
      properties.setProperty(SELECTOR_KEY + i + ".firstLine", selector.firstLinePattern());
      properties.setProperty(SELECTOR_KEY + i + ".set", selector.setId());
    }
  }

  private static SpreadMode spread(Properties properties, SpreadMode fallback) {
    String value = properties.getProperty(TIME_SPREAD_KEY);
    if (value == null) {
      return fallback;
    }
    SpreadMode mode = SpreadMode.byId(value.strip());
    if (mode == null) {
      LOG.warn("Ignoring an invalid {}", LogText.oneLine(TIME_SPREAD_KEY));
      return fallback;
    }
    return mode;
  }

  private static boolean flag(Properties properties, String key, boolean fallback) {
    String value = properties.getProperty(key);
    if (value == null) {
      return fallback;
    }
    String text = value.strip();
    if (text.equals("true") || text.equals("false")) {
      return text.equals("true");
    }
    LOG.warn("Ignoring an invalid {}", LogText.oneLine(key));
    return fallback;
  }
}
