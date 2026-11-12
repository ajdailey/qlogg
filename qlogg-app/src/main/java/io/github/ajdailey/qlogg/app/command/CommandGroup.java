package io.github.ajdailey.qlogg.app.command;

import io.github.ajdailey.qlogg.app.i18n.UiText;

/** Where a command appears: a menu-bar menu in bar order, or the log view's own keys. */
public enum CommandGroup {
  /** File menu. */
  FILE(UiText.GROUP_FILE, true),
  /** Edit menu. */
  EDIT(UiText.GROUP_EDIT, true),
  /** Search menu. */
  SEARCH(UiText.GROUP_SEARCH, true),
  /** Go menu. */
  GO(UiText.GROUP_GO, true),
  /** View menu. */
  VIEW(UiText.GROUP_VIEW, true),
  /** Highlighters menu; the menu lists the highlighter sets after None. */
  HIGHLIGHT(UiText.GROUP_HIGHLIGHT, true),
  /** Encoding menu; the menu lists the encodings after Auto. */
  ENCODING(UiText.GROUP_ENCODING, true),
  /** Tools menu. */
  TOOLS(UiText.GROUP_TOOLS, true),
  /** Window menu. */
  WINDOW(UiText.GROUP_WINDOW, true),
  /** Help menu. */
  HELP(UiText.GROUP_HELP, true),
  /** Keys of the focused log view; listed in Keyboard Shortcuts only. */
  LOG_VIEW(UiText.GROUP_LOG_VIEW, false);

  private final UiText label;
  private final boolean menu;

  CommandGroup(UiText label, boolean menu) {
    this.label = label;
    this.menu = menu;
  }

  /**
   * Returns the menu title, with {@code &} before the mnemonic.
   *
   * @return the label
   */
  public String label() {
    return label.text();
  }

  /**
   * Returns the label without the mnemonic marker.
   *
   * @return for example {@code File}
   */
  public String title() {
    return label().replace("&", "");
  }

  /**
   * Reports whether the group is a menu.
   *
   * @return false for {@link #LOG_VIEW}
   */
  public boolean menu() {
    return menu;
  }
}
