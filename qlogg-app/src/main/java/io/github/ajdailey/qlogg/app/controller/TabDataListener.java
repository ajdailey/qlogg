package io.github.ajdailey.qlogg.app.controller;

/** Receives tab data state changes on the UI thread. */
public interface TabDataListener {
  /**
   * A tab's state changed.
   *
   * @param document the tab's controller
   * @param state the new state
   */
  void tabDataChanged(DocumentController document, TabDataState state);
}
