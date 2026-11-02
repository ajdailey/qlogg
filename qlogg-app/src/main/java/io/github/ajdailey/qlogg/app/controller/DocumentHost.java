package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.highlight.HighlightSettings;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.watch.FileChange;

/** What a document's controllers need from the application. Called on the UI thread. */
public interface DocumentHost {
  /**
   * Returns the toggles a new document's search starts with.
   *
   * @return the toggles last used
   */
  SearchOptions initialOptions();

  /**
   * Records a search that ran: its text goes first in the history and its toggles are kept for the
   * next document.
   *
   * @param text the non-empty search text
   * @param options the toggles
   */
  void searchRan(String text, SearchOptions options);

  /**
   * Reports whether add, exclude and replace with selection run the search at once.
   *
   * @return {@code search.runOnAdd}
   */
  boolean runSearchOnAdd();

  /**
   * Returns the highlighter sets, labels and match highlight.
   *
   * @return the current settings
   */
  HighlightSettings highlightSettings();

  /**
   * Returns the resolved palette, whose match color the match highlight uses by default.
   *
   * @return the palette
   */
  Palette palette();

  /**
   * Reports whether scrolling down past the last line engages follow.
   *
   * @return {@code follow.onOverscroll}
   */
  boolean followOnOverscroll();

  /**
   * A document's file was truncated, replaced or modified on disk; the host reopens it.
   *
   * @param document the document's controller
   * @param change {@code TRUNCATED}, {@code REPLACED} or {@code MODIFIED}
   */
  void reopenChanged(DocumentController document, FileChange change);

  // Sessions.

  /** A document's follow state or marks changed, which the session keeps. */
  void documentStateChanged();
}
