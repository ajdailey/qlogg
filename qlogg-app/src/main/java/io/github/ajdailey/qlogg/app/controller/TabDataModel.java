package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.view.DocumentViewListener;
import io.github.ajdailey.qlogg.app.view.DocumentViewState;
import io.github.ajdailey.qlogg.app.view.SearchViewListener;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Each tab's {@link TabDataState} in one window. Confined to the UI thread.
 *
 * <p>A tab that is not shown takes {@code NEW_LINES} when its document's line count grows and
 * {@code NEW_MATCHES} when its search's match count grows within one generation from {@code DONE}
 * to {@code DONE}, which only the auto-refresh of appended lines does. A higher state replaces a
 * lower one, never the reverse; showing the tab sets {@code NONE}. Lines indexed before the
 * document's first indexing pass ends are not growth. A reloaded or reopened document keeps the
 * tab's state and, while not shown, takes {@code NEW_LINES} when its first pass ends with at least
 * one line. A run of a new search starts a new generation and never sets a state.
 *
 * <p>Listeners attach to a document's controllers at {@link #started} and detach when the tab
 * closes, moves out or is replaced. Each tab costs one small object, not charged to the budget.
 */
public final class TabDataModel implements ApplicationListener {
  private final Map<DocumentController, Watch> watches = new HashMap<DocumentController, Watch>();
  private final List<TabDataListener> listeners = new ArrayList<TabDataListener>();
  private DocumentController shown;

  TabDataModel() {}

  /**
   * Adds a listener.
   *
   * @param listener called on the UI thread
   */
  public void addListener(TabDataListener listener) {
    listeners.add(listener);
  }

  /**
   * Removes a listener.
   *
   * @param listener a listener added before
   */
  public void removeListener(TabDataListener listener) {
    listeners.remove(listener);
  }

  /**
   * Returns a tab's state.
   *
   * @param document the tab's controller
   * @return the state, {@code NONE} for a document this window does not hold
   */
  public TabDataState state(DocumentController document) {
    Watch watch = watches.get(document);
    return watch == null ? TabDataState.NONE : watch.state;
  }

  /**
   * Attaches to a document whose indexing starts now; call before {@link DocumentController#start}.
   *
   * @param document the tab's controller
   */
  void started(DocumentController document) {
    Watch watch = watches.get(document);
    if (watch == null) {
      watch = new Watch(document, false);
      watches.put(document, watch);
    }
    watch.attach(false);
  }

  @Override
  public void documentOpened(DocumentController document) {
    // Attached at started, before indexing starts.
  }

  @Override
  public void documentClosed(DocumentController document) {
    remove(document);
  }

  @Override
  public void activeDocumentChanged(DocumentController document) {
    shown = document;
    Watch watch = document == null ? null : watches.get(document);
    if (watch != null) {
      watch.set(TabDataState.NONE);
    }
  }

  @Override
  public void documentReplaced(DocumentController closed, DocumentController replacement) {
    Watch old = remove(closed);
    if (shown == closed) {
      shown = replacement;
    }
    Watch watch = new Watch(replacement, true);
    watches.put(replacement, watch);
    if (old != null) {
      watch.set(old.state);
    }
  }

  @Override
  public void documentMovedOut(DocumentController document) {
    remove(document);
  }

  @Override
  public void documentMovedIn(DocumentController document, int index) {
    Watch watch = new Watch(document, false);
    watches.put(document, watch);
    watch.attach(!document.state().indexing());
  }

  private Watch remove(DocumentController document) {
    Watch watch = watches.remove(document);
    if (watch != null) {
      watch.detach();
    }
    return watch;
  }

  /** One tab's state and the baselines its growth is measured from. */
  private final class Watch implements DocumentViewListener, SearchViewListener {
    private final DocumentController document;
    private final boolean reopened;
    private TabDataState state = TabDataState.NONE;
    private boolean attached;
    private boolean settled;
    private long lines;
    private long generation;
    private long matches;
    private boolean searchDone;

    Watch(DocumentController document, boolean reopened) {
      this.document = document;
      this.reopened = reopened;
    }

    void attach(boolean indexed) {
      if (attached) {
        return;
      }
      attached = true;
      settled = indexed;
      lines = document.state().lineCount();
      takeSearch(document.search().state());
      document.addListener(this);
      document.search().addListener(this);
    }

    void detach() {
      if (attached) {
        attached = false;
        document.removeListener(this);
        document.search().removeListener(this);
      }
    }

    void set(TabDataState next) {
      if (next == state) {
        return;
      }
      state = next;
      for (TabDataListener listener : new ArrayList<TabDataListener>(listeners)) {
        listener.tabDataChanged(document, next);
      }
    }

    private void raise(TabDataState next) {
      if (document != shown && next.outranks(state)) {
        set(next);
      }
    }

    @Override
    public void stateChanged(DocumentViewState viewState) {
      long count = viewState.lineCount();
      if (!settled) {
        if (!viewState.indexing()) {
          settled = true;
          if (reopened && count > 0) {
            raise(TabDataState.NEW_LINES);
          }
        }
      } else if (count > lines) {
        raise(TabDataState.NEW_LINES);
      }
      lines = count;
    }

    @Override
    public void contentChanged() {
      // Growth arrives as a state change.
    }

    @Override
    public void stateChanged(SearchViewState searchState) {
      long current = document.search().generation();
      boolean done = searchState.phase() == SearchViewState.Phase.DONE;
      if (current == generation && searchDone && done && searchState.matchCount() > matches) {
        raise(TabDataState.NEW_MATCHES);
      }
      takeSearch(searchState);
    }

    private void takeSearch(SearchViewState searchState) {
      generation = document.search().generation();
      matches = searchState.matchCount();
      searchDone = searchState.phase() == SearchViewState.Phase.DONE;
    }

    @Override
    public void resultsChanged() {
      // The match count arrives as a state change.
    }
  }
}
