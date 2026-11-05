package io.github.ajdailey.qlogg.app.view;

import io.github.ajdailey.qlogg.core.search.MatchMode;

/**
 * The search bar's toggles, the range end and the context line counts.
 *
 * @param mode literal or regex
 * @param caseSensitive false folds case
 * @param wholeWord terms match only between non-word characters
 * @param inverse matches the lines the expression does not match; with {@code range}, the lines
 *     outside every range
 * @param combining the text is a combined expression of quoted terms
 * @param inSelection searches only the selected lines; without a selection, every line
 * @param autoRefresh a finished search extends over lines the file gains
 * @param keepResults the next search keeps the current results as a pinned set
 * @param range the text starts a range and {@code rangeEnd} ends it; the results are the lines of
 *     each range
 * @param rangeEnd the end expression of a range search, read with the same toggles; never null
 * @param contextBefore lines the filtered view lists above each result, 0 to {@link #MAX_CONTEXT}
 * @param contextAfter lines the filtered view lists below each result, 0 to {@link #MAX_CONTEXT}
 */
public record SearchOptions(
    MatchMode mode,
    boolean caseSensitive,
    boolean wholeWord,
    boolean inverse,
    boolean combining,
    boolean inSelection,
    boolean autoRefresh,
    boolean keepResults,
    boolean range,
    String rangeEnd,
    int contextBefore,
    int contextAfter) {
  /** Most context lines above or below a result. */
  public static final int MAX_CONTEXT = 1000;

  /** Regex, case-sensitive, every other toggle off, no range and no context. */
  public static final SearchOptions DEFAULTS =
      new SearchOptions(MatchMode.REGEX, true, false, false, false, false, false, false);

  /** Checks the components. */
  public SearchOptions {
    if (mode == null) {
      throw new IllegalArgumentException("mode is required");
    }
    if (rangeEnd == null) {
      throw new IllegalArgumentException("rangeEnd is required");
    }
    if (contextBefore < 0
        || contextBefore > MAX_CONTEXT
        || contextAfter < 0
        || contextAfter > MAX_CONTEXT) {
      throw new IllegalArgumentException("context counts must be 0 to " + MAX_CONTEXT);
    }
  }

  /**
   * Creates toggles for a line search with no context.
   *
   * @param mode see the record
   * @param caseSensitive see the record
   * @param wholeWord see the record
   * @param inverse see the record
   * @param combining see the record
   * @param inSelection see the record
   * @param autoRefresh see the record
   * @param keepResults see the record
   */
  public SearchOptions(
      MatchMode mode,
      boolean caseSensitive,
      boolean wholeWord,
      boolean inverse,
      boolean combining,
      boolean inSelection,
      boolean autoRefresh,
      boolean keepResults) {
    this(
        mode,
        caseSensitive,
        wholeWord,
        inverse,
        combining,
        inSelection,
        autoRefresh,
        keepResults,
        false,
        "",
        0,
        0);
  }

  /**
   * Returns a copy with another value of {@code mode}.
   *
   * @param value the value
   * @return the copy
   */
  public SearchOptions withMode(MatchMode value) {
    return new SearchOptions(
        value,
        caseSensitive,
        wholeWord,
        inverse,
        combining,
        inSelection,
        autoRefresh,
        keepResults,
        range,
        rangeEnd,
        contextBefore,
        contextAfter);
  }

  /**
   * Returns a copy with another value of {@code caseSensitive}.
   *
   * @param value the value
   * @return the copy
   */
  public SearchOptions withCaseSensitive(boolean value) {
    return new SearchOptions(
        mode,
        value,
        wholeWord,
        inverse,
        combining,
        inSelection,
        autoRefresh,
        keepResults,
        range,
        rangeEnd,
        contextBefore,
        contextAfter);
  }

  /**
   * Returns a copy with another value of {@code wholeWord}.
   *
   * @param value the value
   * @return the copy
   */
  public SearchOptions withWholeWord(boolean value) {
    return new SearchOptions(
        mode,
        caseSensitive,
        value,
        inverse,
        combining,
        inSelection,
        autoRefresh,
        keepResults,
        range,
        rangeEnd,
        contextBefore,
        contextAfter);
  }

  /**
   * Returns a copy with another value of {@code inverse}.
   *
   * @param value the value
   * @return the copy
   */
  public SearchOptions withInverse(boolean value) {
    return new SearchOptions(
        mode,
        caseSensitive,
        wholeWord,
        value,
        combining,
        inSelection,
        autoRefresh,
        keepResults,
        range,
        rangeEnd,
        contextBefore,
        contextAfter);
  }

  /**
   * Returns a copy with another value of {@code combining}.
   *
   * @param value the value
   * @return the copy
   */
  public SearchOptions withCombining(boolean value) {
    return new SearchOptions(
        mode,
        caseSensitive,
        wholeWord,
        inverse,
        value,
        inSelection,
        autoRefresh,
        keepResults,
        range,
        rangeEnd,
        contextBefore,
        contextAfter);
  }

  /**
   * Returns a copy with another value of {@code inSelection}.
   *
   * @param value the value
   * @return the copy
   */
  public SearchOptions withInSelection(boolean value) {
    return new SearchOptions(
        mode,
        caseSensitive,
        wholeWord,
        inverse,
        combining,
        value,
        autoRefresh,
        keepResults,
        range,
        rangeEnd,
        contextBefore,
        contextAfter);
  }

  /**
   * Returns a copy with another value of {@code autoRefresh}.
   *
   * @param value the value
   * @return the copy
   */
  public SearchOptions withAutoRefresh(boolean value) {
    return new SearchOptions(
        mode,
        caseSensitive,
        wholeWord,
        inverse,
        combining,
        inSelection,
        value,
        keepResults,
        range,
        rangeEnd,
        contextBefore,
        contextAfter);
  }

  /**
   * Returns a copy with another value of {@code keepResults}.
   *
   * @param value the value
   * @return the copy
   */
  public SearchOptions withKeepResults(boolean value) {
    return new SearchOptions(
        mode,
        caseSensitive,
        wholeWord,
        inverse,
        combining,
        inSelection,
        autoRefresh,
        value,
        range,
        rangeEnd,
        contextBefore,
        contextAfter);
  }

  /**
   * Returns a copy with another value of {@code range}.
   *
   * @param value the value
   * @return the copy
   */
  public SearchOptions withRange(boolean value) {
    return new SearchOptions(
        mode,
        caseSensitive,
        wholeWord,
        inverse,
        combining,
        inSelection,
        autoRefresh,
        keepResults,
        value,
        rangeEnd,
        contextBefore,
        contextAfter);
  }

  /**
   * Returns a copy with another value of {@code rangeEnd}.
   *
   * @param value the value
   * @return the copy
   */
  public SearchOptions withRangeEnd(String value) {
    return new SearchOptions(
        mode,
        caseSensitive,
        wholeWord,
        inverse,
        combining,
        inSelection,
        autoRefresh,
        keepResults,
        range,
        value,
        contextBefore,
        contextAfter);
  }

  /**
   * Returns a copy with another value of {@code contextBefore}.
   *
   * @param value the value
   * @return the copy
   */
  public SearchOptions withContextBefore(int value) {
    return new SearchOptions(
        mode,
        caseSensitive,
        wholeWord,
        inverse,
        combining,
        inSelection,
        autoRefresh,
        keepResults,
        range,
        rangeEnd,
        value,
        contextAfter);
  }

  /**
   * Returns a copy with another value of {@code contextAfter}.
   *
   * @param value the value
   * @return the copy
   */
  public SearchOptions withContextAfter(int value) {
    return new SearchOptions(
        mode,
        caseSensitive,
        wholeWord,
        inverse,
        combining,
        inSelection,
        autoRefresh,
        keepResults,
        range,
        rangeEnd,
        contextBefore,
        value);
  }
}
