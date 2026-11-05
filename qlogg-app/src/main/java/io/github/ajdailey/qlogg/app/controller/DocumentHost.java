package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.highlight.HighlightSettings;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.watch.FileChange;

/** What a document's controllers need from the application. Called on the UI thread. */
public interface DocumentHost {
  /**
   * Returns the toggles a new document's search starts with: the saved {@code search} toggles,
   * shared by every window.
   *
   * @return the toggles last saved by any window
   */
  SearchOptions initialOptions();

  /**
   * Records a search that ran: its text goes first in the history and its toggles, without {@code
   * inSelection}, are saved for the next document in any window.
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

  // Keyword triggers.

  /**
   * A trigger turned follow off for a document.
   *
   * @param document the document's controller
   * @param rule the rule's pattern, cut for display
   * @param line the 0-based line it matched
   */
  void followStoppedByTrigger(DocumentController document, String rule, long line);
}
