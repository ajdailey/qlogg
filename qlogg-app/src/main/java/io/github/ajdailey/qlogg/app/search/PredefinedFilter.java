package io.github.ajdailey.qlogg.app.search;

import io.github.ajdailey.qlogg.core.search.MatchMode;

/**
 * A named search pattern the filters menu adds to the search field.
 *
 * @param name the label shown
 * @param pattern the pattern
 * @param mode {@code REGEX}, or {@code LITERAL} for text matched as typed
 */
public record PredefinedFilter(String name, String pattern, MatchMode mode) {
  /** Checks the components. */
  public PredefinedFilter {
    if (name == null || pattern == null || mode == null) {
      throw new IllegalArgumentException("name, pattern and mode are required");
    }
  }
}
