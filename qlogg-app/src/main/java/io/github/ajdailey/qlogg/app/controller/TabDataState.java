package io.github.ajdailey.qlogg.app.controller;

/** What a tab shows about lines that arrived while another tab was shown. */
public enum TabDataState {
  /** Nothing arrived, or the tab was shown since. */
  NONE(0),
  /** The document gained lines. */
  NEW_LINES(1),
  /** The tab's search results gained a line from the document's growth. */
  NEW_MATCHES(2);

  private final int rank;

  TabDataState(int rank) {
    this.rank = rank;
  }

  /**
   * Reports whether this state replaces another on the tab.
   *
   * @param other the tab's current state
   * @return true when this state ranks higher
   */
  public boolean outranks(TabDataState other) {
    return rank > other.rank;
  }
}
