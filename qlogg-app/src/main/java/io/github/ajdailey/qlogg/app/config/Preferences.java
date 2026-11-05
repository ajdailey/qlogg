package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.app.highlight.HighlightSettings;
import io.github.ajdailey.qlogg.app.search.PredefinedFilter;
import io.github.ajdailey.qlogg.app.search.SearchHistory;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import java.util.List;

/**
 * The settings in {@code qlogg.conf}. Immutable; a change creates a new record.
 *
 * @param theme {@code ui.theme}
 * @param historySize {@code search.historySize}, 1 to {@link SearchHistory#MAX_SIZE}
 * @param maxResults {@code search.maxResults}, the match count that stops a search, or 0
 * @param runSearchOnAdd {@code search.runOnAdd}: add, exclude and replace with selection search at
 *     once
 * @param quickFindIncremental {@code quickFind.incremental}: quick find searches while typing
 * @param quickFindRegex {@code quickFind.regex}: quick find patterns are regexes, else fixed text
 * @param splitPercent {@code view.splitPercent}: the main view's share of the split, {@link
 *     #MIN_SPLIT} to {@link #MAX_SPLIT}
 * @param searchHistory {@code search.history.N}, most recent first
 * @param filters {@code filter.N.*}, in menu order
 * @param highlights {@code highlight.*}, {@code label.*} and {@code matchHighlight.*}
 * @param overview {@code view.overview}: the overview bar shows beside the main view
 * @param follow {@code follow.*}
 * @param view {@code view.wrapOnOpen}, {@code view.showWhitespace}, {@code view.hideAnsi}, {@code
 *     fold.*} and {@code highlight.selector.*}
 * @param options font, recent-files count, monitoring, default encoding, session, shortcuts and
 *     favorites
 * @param state remembered UI state
 * @param time {@code time.*}: zones and user timestamp formats
 */
public record Preferences(
    ThemeMode theme,
    int historySize,
    long maxResults,
    boolean runSearchOnAdd,
    boolean quickFindIncremental,
    boolean quickFindRegex,
    int splitPercent,
    List<String> searchHistory,
    List<PredefinedFilter> filters,
    HighlightSettings highlights,
    boolean overview,
    FollowSettings follow,
    ViewOptions view,
    // Options dialog settings, favorites and remembered UI state.
    Options options,
    UiState state,
    // Timestamps.
    TimeSettings time) {
  /** Smallest {@code view.splitPercent}. */
  public static final int MIN_SPLIT = 10;

  /** Largest {@code view.splitPercent}. */
  public static final int MAX_SPLIT = 90;

  /** The settings when the file is missing or malformed. */
  public static final Preferences DEFAULTS =
      new Preferences(
          ThemeMode.LIGHT,
          SearchHistory.DEFAULT_SIZE,
          0,
          true,
          true,
          false,
          70,
          List.of(),
          List.of(),
          HighlightSettings.DEFAULTS,
          true,
          FollowSettings.DEFAULTS,
          ViewOptions.DEFAULTS,
          Options.DEFAULTS,
          UiState.DEFAULTS,
          TimeSettings.DEFAULTS);

  /** Checks the components and copies the lists. */
  public Preferences {
    if (theme == null
        || searchHistory == null
        || filters == null
        || highlights == null
        || follow == null
        || view == null
        || options == null
        || state == null
        || time == null) {
      throw new IllegalArgumentException("every component is required");
    }
    if (historySize < 1 || historySize > SearchHistory.MAX_SIZE) {
      throw new IllegalArgumentException("historySize out of range");
    }
    if (maxResults < 0) {
      throw new IllegalArgumentException("maxResults is negative");
    }
    if (splitPercent < MIN_SPLIT || splitPercent > MAX_SPLIT) {
      throw new IllegalArgumentException("splitPercent out of range");
    }
    searchHistory = SearchHistory.trim(searchHistory, historySize);
    filters = List.copyOf(filters);
  }

  /**
   * Returns a copy with another theme.
   *
   * @param mode the theme
   * @return the copy
   */
  public Preferences withTheme(ThemeMode mode) {
    return new Preferences(
        mode,
        historySize,
        maxResults,
        runSearchOnAdd,
        quickFindIncremental,
        quickFindRegex,
        splitPercent,
        searchHistory,
        filters,
        highlights,
        overview,
        follow,
        view,
        options,
        state,
        time);
  }

  /**
   * Returns a copy with another history size; the history is cut to it.
   *
   * @param size 1 to {@link SearchHistory#MAX_SIZE}
   * @return the copy
   */
  public Preferences withHistorySize(int size) {
    return new Preferences(
        theme,
        size,
        maxResults,
        runSearchOnAdd,
        quickFindIncremental,
        quickFindRegex,
        splitPercent,
        searchHistory,
        filters,
        highlights,
        overview,
        follow,
        view,
        options,
        state,
        time);
  }

  /**
   * Returns a copy with another result limit.
   *
   * @param limit at least 0; 0 is no limit
   * @return the copy
   */
  public Preferences withMaxResults(long limit) {
    return new Preferences(
        theme,
        historySize,
        limit,
        runSearchOnAdd,
        quickFindIncremental,
        quickFindRegex,
        splitPercent,
        searchHistory,
        filters,
        highlights,
        overview,
        follow,
        view,
        options,
        state,
        time);
  }

  /**
   * Returns a copy with the run-after-add option set.
   *
   * @param value true searches after add, exclude and replace
   * @return the copy
   */
  public Preferences withRunSearchOnAdd(boolean value) {
    return new Preferences(
        theme,
        historySize,
        maxResults,
        value,
        quickFindIncremental,
        quickFindRegex,
        splitPercent,
        searchHistory,
        filters,
        highlights,
        overview,
        follow,
        view,
        options,
        state,
        time);
  }

  /**
   * Returns a copy with the quick find options set.
   *
   * @param incremental true searches while typing
   * @param regex true for regex patterns
   * @return the copy
   */
  public Preferences withQuickFind(boolean incremental, boolean regex) {
    return new Preferences(
        theme,
        historySize,
        maxResults,
        runSearchOnAdd,
        incremental,
        regex,
        splitPercent,
        searchHistory,
        filters,
        highlights,
        overview,
        follow,
        view,
        options,
        state,
        time);
  }

  /**
   * Returns a copy with another split.
   *
   * @param percent {@link #MIN_SPLIT} to {@link #MAX_SPLIT}
   * @return the copy
   */
  public Preferences withSplitPercent(int percent) {
    return new Preferences(
        theme,
        historySize,
        maxResults,
        runSearchOnAdd,
        quickFindIncremental,
        quickFindRegex,
        percent,
        searchHistory,
        filters,
        highlights,
        overview,
        follow,
        view,
        options,
        state,
        time);
  }

  /**
   * Returns a copy with another search history.
   *
   * @param history most recent first; cut to {@link #historySize}
   * @return the copy
   */
  public Preferences withSearchHistory(List<String> history) {
    return new Preferences(
        theme,
        historySize,
        maxResults,
        runSearchOnAdd,
        quickFindIncremental,
        quickFindRegex,
        splitPercent,
        history,
        filters,
        highlights,
        overview,
        follow,
        view,
        options,
        state,
        time);
  }

  /**
   * Returns a copy with other predefined filters.
   *
   * @param list the filters, in menu order
   * @return the copy
   */
  public Preferences withFilters(List<PredefinedFilter> list) {
    return new Preferences(
        theme,
        historySize,
        maxResults,
        runSearchOnAdd,
        quickFindIncremental,
        quickFindRegex,
        splitPercent,
        searchHistory,
        list,
        highlights,
        overview,
        follow,
        view,
        options,
        state,
        time);
  }

  /**
   * Returns a copy with other highlight settings.
   *
   * @param settings the settings
   * @return the copy
   */
  public Preferences withHighlights(HighlightSettings settings) {
    return new Preferences(
        theme,
        historySize,
        maxResults,
        runSearchOnAdd,
        quickFindIncremental,
        quickFindRegex,
        splitPercent,
        searchHistory,
        filters,
        settings,
        overview,
        follow,
        view,
        options,
        state,
        time);
  }

  /**
   * Returns a copy with other follow options.
   *
   * @param settings the options
   * @return the copy
   */
  public Preferences withFollow(FollowSettings settings) {
    return new Preferences(
        theme,
        historySize,
        maxResults,
        runSearchOnAdd,
        quickFindIncremental,
        quickFindRegex,
        splitPercent,
        searchHistory,
        filters,
        highlights,
        overview,
        settings,
        view,
        options,
        state,
        time);
  }

  /**
   * Returns a copy with the overview bar shown or hidden.
   *
   * @param value true shows it
   * @return the copy
   */
  public Preferences withOverview(boolean value) {
    return new Preferences(
        theme,
        historySize,
        maxResults,
        runSearchOnAdd,
        quickFindIncremental,
        quickFindRegex,
        splitPercent,
        searchHistory,
        filters,
        highlights,
        value,
        follow,
        view,
        options,
        state,
        time);
  }

  /**
   * Returns a copy with other view settings.
   *
   * @param value the settings
   * @return the copy
   */
  public Preferences withView(ViewOptions value) {
    return new Preferences(
        theme,
        historySize,
        maxResults,
        runSearchOnAdd,
        quickFindIncremental,
        quickFindRegex,
        splitPercent,
        searchHistory,
        filters,
        highlights,
        overview,
        follow,
        value,
        options,
        state,
        time);
  }

  // Options dialog settings, favorites and remembered UI state.

  /**
   * Returns a copy with other options.
   *
   * @param value the options
   * @return the copy
   */
  public Preferences withOptions(Options value) {
    return new Preferences(
        theme,
        historySize,
        maxResults,
        runSearchOnAdd,
        quickFindIncremental,
        quickFindRegex,
        splitPercent,
        searchHistory,
        filters,
        highlights,
        overview,
        follow,
        view,
        value,
        state,
        time);
  }

  /**
   * Returns a copy with other remembered UI state.
   *
   * @param value the state
   * @return the copy
   */
  public Preferences withState(UiState value) {
    return new Preferences(
        theme,
        historySize,
        maxResults,
        runSearchOnAdd,
        quickFindIncremental,
        quickFindRegex,
        splitPercent,
        searchHistory,
        filters,
        highlights,
        overview,
        follow,
        view,
        options,
        value,
        time);
  }

  // Timestamps.

  /**
   * Returns a copy with other timestamp settings.
   *
   * @param value the settings
   * @return the copy
   */
  public Preferences withTime(TimeSettings value) {
    return new Preferences(
        theme,
        historySize,
        maxResults,
        runSearchOnAdd,
        quickFindIncremental,
        quickFindRegex,
        splitPercent,
        searchHistory,
        filters,
        highlights,
        overview,
        follow,
        view,
        options,
        state,
        value);
  }
}
