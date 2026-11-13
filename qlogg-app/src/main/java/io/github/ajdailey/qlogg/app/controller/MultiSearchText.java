package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.core.search.SearchLimit;

/** Builds a Search All Open Files row: the file's title, its match count and state. */
public final class MultiSearchText {
  private MultiSearchText() {}

  /**
   * Builds one file group's row. A finished search stopped by a limit or with skipped lines shows
   * its notice as the search bar words it.
   *
   * @param title the document's title
   * @param state the group's search state
   * @return the row
   */
  public static String text(String title, SearchViewState state) {
    return switch (state.phase()) {
      case RUNNING -> UiText.MULTI_RUNNING.format(title, state.matchCount());
      case FAILED -> UiText.MULTI_FAILED.format(title, String.valueOf(state.error()));
      case CANCELED -> UiText.MULTI_STOPPED.format(title, state.matchCount());
      default ->
          state.limit() == SearchLimit.NONE && state.skippedLines() == 0
              ? UiText.MULTI_DONE.format(title, state.matchCount())
              : UiText.MULTI_FAILED.format(title, state.statusText());
    };
  }
}
