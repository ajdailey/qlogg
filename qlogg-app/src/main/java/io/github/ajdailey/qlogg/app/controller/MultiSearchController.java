package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewListener;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import java.util.ArrayList;
import java.util.List;

/**
 * Search across open files: one text and set of toggles run as the current search of every open
 * document, each into that document's own result set through its {@link SearchController}, so each
 * search uses its document's encoding, is charged to the results share and runs on the shared pool.
 * The results are one group per file, in tab order; each group is that file's result set, which the
 * results pane of its tab lists and which {@code keepResults} pins as for any search. Confined to
 * the UI thread.
 *
 * <p>{@link #cancel} stops every search of the group. Closing a tab cancels its search and drops
 * its group ({@link #documentClosed}). A group is a snapshot of its file at run time: growth does
 * not extend it unless the file's own {@code autoRefresh} is on.
 *
 * <p>A run ignores {@code inSelection}, so a selection left in a tab does not narrow its group;
 * each tab keeps its own In Selection toggle for its later searches. Each tab's search start and
 * end limits apply.
 */
public final class MultiSearchController {
  /**
   * One file's group.
   *
   * @param document the file's controller
   * @param state its search's state; {@code matchCount} counts the group
   */
  public record FileResults(DocumentController document, SearchViewState state) {}

  /** Receives group changes. */
  public interface Listener {
    /** A group was added, dropped or progressed. Called on the UI thread. */
    void resultsChanged();
  }

  private final List<DocumentController> group = new ArrayList<DocumentController>();
  private final List<Listener> listeners = new ArrayList<Listener>();
  private final SearchViewListener progress =
      new SearchViewListener() {
        @Override
        public void stateChanged(SearchViewState state) {
          fire();
        }

        @Override
        public void resultsChanged() {
          fire();
        }
      };
  private String text = "";

  /**
   * Runs a search in every listed document and replaces the groups.
   *
   * @param documents the open documents, in tab order
   * @param searchText the search text; empty clears the groups
   * @param options the toggles
   */
  public void run(List<DocumentController> documents, String searchText, SearchOptions options) {
    clear();
    text = searchText;
    if (searchText.isEmpty()) {
      fire();
      return;
    }
    for (DocumentController document : documents) {
      if (document.isClosed()) {
        continue;
      }
      group.add(document);
      document.search().addListener(progress);
      boolean inSelection = document.search().options().inSelection();
      document.search().run(searchText, options.withInSelection(false));
      document.search().setOptions(document.search().options().withInSelection(inSelection));
    }
    fire();
  }

  /** Cancels every search of the group; results so far stay. */
  public void cancel() {
    for (DocumentController document : group) {
      document.search().cancel();
    }
  }

  /**
   * Returns the search text of the last run.
   *
   * @return the text, empty before a run
   */
  public String text() {
    return text;
  }

  /**
   * Returns the groups, in tab order at run time.
   *
   * @return a new list
   */
  public List<FileResults> results() {
    List<FileResults> found = new ArrayList<FileResults>();
    for (DocumentController document : group) {
      found.add(new FileResults(document, document.search().state()));
    }
    return found;
  }

  /**
   * Counts the matches of every group.
   *
   * @return the sum of the groups' match counts
   */
  public long totalMatches() {
    long total = 0;
    for (DocumentController document : group) {
      total += document.search().state().matchCount();
    }
    return total;
  }

  /**
   * Drops a closing document's group; its controller cancels its search as it detaches.
   *
   * @param document the closing document
   */
  public void documentClosed(DocumentController document) {
    if (group.remove(document)) {
      document.search().removeListener(progress);
      fire();
    }
  }

  /**
   * Adds a listener.
   *
   * @param listener called on the UI thread
   */
  public void addListener(Listener listener) {
    listeners.add(listener);
  }

  /**
   * Removes a listener.
   *
   * @param listener a listener added earlier
   */
  public void removeListener(Listener listener) {
    listeners.remove(listener);
  }

  private void clear() {
    for (DocumentController document : group) {
      document.search().removeListener(progress);
    }
    group.clear();
  }

  private void fire() {
    for (Listener listener : new ArrayList<Listener>(listeners)) {
      listener.resultsChanged();
    }
  }
}
