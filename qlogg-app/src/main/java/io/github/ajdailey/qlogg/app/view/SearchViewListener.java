package io.github.ajdailey.qlogg.app.view;

/** Receives search updates on the UI thread. */
public interface SearchViewListener {
  /**
   * Called when the search bar state changes.
   *
   * @param state the latest state
   */
  void stateChanged(SearchViewState state);

  /** Called when the shown result set gained lines or was replaced, or a kept set changed. */
  void resultsChanged();

  /**
   * Called when the toggles or the search text change outside the search bar, such as by a key or
   * an add-to-search edit. Does nothing by default.
   *
   * @param text the search text
   * @param options the toggles
   */
  default void optionsChanged(String text, SearchOptions options) {}
}
