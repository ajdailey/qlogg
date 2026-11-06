package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.i18n.UiText;

/** Builds a document tab's tooltip text. */
public final class TabTips {
  private TabTips() {}

  /**
   * Builds the tooltip of a document tab: the path, the data-state line, then the sync group line,
   * each on its own line and omitted when empty.
   *
   * @param path the document's path; empty for a pasted document
   * @param state the tab's data state
   * @param group the tab's time sync group number; 0 for none
   * @return the text with every {@code &} doubled, because SWT drops a single one from a tool tip;
   *     null when the tab has no tooltip
   */
  public static String text(String path, TabDataState state, int group) {
    String line =
        switch (state) {
          case NONE -> "";
          case NEW_LINES -> UiText.TAB_DATA_NEW_LINES.text();
          case NEW_MATCHES -> UiText.TAB_DATA_NEW_MATCHES.text();
          case FOLLOW_STOPPED -> UiText.TAB_DATA_FOLLOW_STOPPED.text();
        };
    String tip = path.isEmpty() || line.isEmpty() ? path + line : path + "\n" + line;
    if (group > 0) {
      String synced = UiText.TAB_TIME_SYNCED.format(group);
      tip = tip.isEmpty() ? synced : tip + "\n" + synced;
    }
    return tip.isEmpty() ? null : tip.replace("&", "&&");
  }
}
