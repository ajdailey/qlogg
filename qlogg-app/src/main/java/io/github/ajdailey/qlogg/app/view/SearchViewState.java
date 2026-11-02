package io.github.ajdailey.qlogg.app.view;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.search.SearchLimit;

/**
 * The search bar's state, pushed to views on the UI thread.
 *
 * @param text the search text
 * @param options the toggles it ran with
 * @param phase where the search is
 * @param progressPercent lines searched, as a percentage of the range
 * @param matchCount matching lines so far
 * @param limit what stopped a {@code DONE} search early, else {@code NONE}
 * @param error the message for {@code FAILED}, otherwise null
 */
public record SearchViewState(
    String text,
    SearchOptions options,
    Phase phase,
    int progressPercent,
    long matchCount,
    SearchLimit limit,
    String error) {
  /** Where a search is. */
  public enum Phase {
    /** No search, or cleared. */
    IDLE,
    /** Running. */
    RUNNING,
    /** Ended at the end of its range or at a limit. */
    DONE,
    /** Stopped by the user. */
    CANCELED,
    /** Did not parse, did not compile or failed to read. */
    FAILED
  }

  /** No search. */
  public static final SearchViewState IDLE =
      new SearchViewState("", SearchOptions.DEFAULTS, Phase.IDLE, 0, 0, SearchLimit.NONE, null);

  /**
   * Returns the status line for the search bar.
   *
   * @return the text, empty when idle
   */
  public String statusText() {
    return switch (phase) {
      case IDLE -> "";
      case RUNNING -> UiText.SEARCH_PROGRESS.format(progressPercent, matchCount);
      case CANCELED -> UiText.SEARCH_CANCELED.format(matchCount);
      case FAILED -> error;
      case DONE ->
          switch (limit) {
            case NONE -> UiText.SEARCH_DONE.format(matchCount);
            case MAX_RESULTS -> UiText.SEARCH_LIMIT_RESULTS.format(matchCount);
            case MEMORY -> UiText.SEARCH_LIMIT_MEMORY.format(matchCount);
          };
    };
  }
}
