package io.github.ajdailey.qlogg.app.command;

import io.github.ajdailey.qlogg.app.i18n.UiText;

/**
 * Every command a menu item or key can run. Menus list their commands in declaration order; {@code
 * separatorBefore} starts a new section, a {@code check} command shows as a check item, and
 * consecutive commands with the same {@code submenu} form one cascade in their menu.
 */
public enum CommandId {
  // Multiple windows.
  /** Open an empty main window. */
  NEW_WINDOW(CommandGroup.FILE, UiText.COMMAND_NEW_WINDOW, false, false),
  /** Choose files and open them. */
  OPEN(CommandGroup.FILE, UiText.COMMAND_OPEN, false, false),
  /** Open the clipboard's text in a new tab. */
  OPEN_CLIPBOARD(CommandGroup.FILE, UiText.COMMAND_OPEN_CLIPBOARD, false, false),
  // Open All and Recently Closed.
  /** Open every recent file. */
  OPEN_ALL_RECENT(
      CommandGroup.FILE, UiText.COMMAND_OPEN_ALL_RECENT, false, false, UiText.MENU_OPEN_RECENT),
  /** Drop the recent files that no longer exist; the cascade lists the recent files before it. */
  CLEAN_RECENT(
      CommandGroup.FILE, UiText.COMMAND_CLEAN_RECENT, false, false, UiText.MENU_OPEN_RECENT),
  // Open All and Recently Closed.
  /** Open every recently closed file. */
  OPEN_ALL_CLOSED(
      CommandGroup.FILE, UiText.COMMAND_OPEN_ALL_CLOSED, false, false, UiText.MENU_RECENTLY_CLOSED),
  /** Empty Recently Closed; the cascade lists the closed files before Open All. */
  CLEAR_CLOSED(
      CommandGroup.FILE, UiText.COMMAND_CLEAR_CLOSED, false, false, UiText.MENU_RECENTLY_CLOSED),
  /** Write the selected lines to a chosen file. */
  SAVE_SELECTION(CommandGroup.FILE, UiText.COMMAND_SAVE_SELECTION, true, false),
  /** Write the filtered view's lines to a chosen file. */
  SAVE_FILTERED(CommandGroup.FILE, UiText.COMMAND_SAVE_FILTERED, false, false),
  /** Close the active tab. */
  CLOSE(CommandGroup.FILE, UiText.COMMAND_CLOSE, true, false),
  /** Close every tab. */
  CLOSE_ALL(CommandGroup.FILE, UiText.COMMAND_CLOSE_ALL, false, false),
  /** Close the window. */
  QUIT(CommandGroup.FILE, UiText.COMMAND_QUIT, true, false),
  /** Copy the selection, or the current line when nothing is selected. */
  COPY(CommandGroup.EDIT, UiText.COMMAND_COPY, false, false),
  /** Copy the current line. */
  COPY_LINE(CommandGroup.EDIT, UiText.COMMAND_COPY_LINE, false, false),
  /** Select every line. */
  SELECT_ALL(CommandGroup.EDIT, UiText.COMMAND_SELECT_ALL, false, false),
  /** Remember the current line as one end of a line selection. */
  SET_SELECTION_START(CommandGroup.EDIT, UiText.COMMAND_SET_SELECTION_START, true, false),
  /** Select the lines from the remembered line to the current line. */
  SET_SELECTION_END(CommandGroup.EDIT, UiText.COMMAND_SET_SELECTION_END, false, false),
  /** Open the quick find bar. */
  FIND(CommandGroup.EDIT, UiText.COMMAND_FIND, true, false),
  /** Go to the next match after the current line, or the next quick find match. */
  FIND_NEXT(CommandGroup.EDIT, UiText.COMMAND_FIND_NEXT, false, false),
  /** Go to the previous match before the current line, or the previous quick find match. */
  FIND_PREVIOUS(CommandGroup.EDIT, UiText.COMMAND_FIND_PREVIOUS, false, false),
  /** Ask for a line and go to it. */
  GO_TO_LINE(CommandGroup.EDIT, UiText.COMMAND_GO_TO_LINE, true, false),
  // Timestamps.
  /** Ask for a time and go to the line with it. */
  GO_TO_TIME(CommandGroup.EDIT, UiText.COMMAND_GO_TO_TIME, false, false),
  // Time sync.
  /** Go to the caret line's time in every other tab. */
  SCROLL_TABS_TO_TIME(CommandGroup.EDIT, UiText.COMMAND_SCROLL_TABS_TO_TIME, false, false),
  /** Go to the first line. */
  GO_TO_TOP(CommandGroup.EDIT, UiText.COMMAND_GO_TO_TOP, false, false),
  /** Go to the last line. */
  GO_TO_BOTTOM(CommandGroup.EDIT, UiText.COMMAND_GO_TO_BOTTOM, false, false),
  // Navigation history.
  /** Go back to the line before the last jump. */
  GO_BACK(CommandGroup.EDIT, UiText.COMMAND_GO_BACK, false, false),
  /** Go forward to the line Go Back left. */
  GO_FORWARD(CommandGroup.EDIT, UiText.COMMAND_GO_FORWARD, false, false),
  /** In the filtered view, keep the row's line as a jump the main view can go back from. */
  GO_TO_RESULT(CommandGroup.LOG_VIEW, UiText.COMMAND_GO_TO_RESULT, false, false),
  /** Mark or unmark the selected lines. */
  TOGGLE_MARK(CommandGroup.EDIT, UiText.COMMAND_TOGGLE_MARK, true, false),
  /** Go to the next mark. */
  NEXT_MARK(CommandGroup.EDIT, UiText.COMMAND_NEXT_MARK, false, false),
  /** Go to the previous mark. */
  PREVIOUS_MARK(CommandGroup.EDIT, UiText.COMMAND_PREVIOUS_MARK, false, false),
  // Mark comments.
  /** Edit the comment of the current line's mark, marking the line when it is unmarked. */
  EDIT_MARK_COMMENT(CommandGroup.EDIT, UiText.COMMAND_EDIT_MARK_COMMENT, false, false),
  /** Copy the active file's full path. */
  COPY_PATH(CommandGroup.EDIT, UiText.COMMAND_COPY_PATH, true, false),
  /** Copy the active file's name. */
  COPY_FILE_NAME(CommandGroup.EDIT, UiText.COMMAND_COPY_FILE_NAME, false, false),
  /** Open the active file's folder in the system file manager. */
  OPEN_FOLDER(CommandGroup.EDIT, UiText.COMMAND_OPEN_FOLDER, false, false),
  /** Open the active file in the system's application for it. */
  OPEN_IN_EDITOR(CommandGroup.EDIT, UiText.COMMAND_OPEN_IN_EDITOR, true, false),
  /** Move focus to the search field. */
  FOCUS_SEARCH(CommandGroup.EDIT, UiText.COMMAND_FOCUS_SEARCH, true, false, UiText.MENU_SEARCH),
  /** Toggle case-sensitive search. */
  TOGGLE_CASE(CommandGroup.EDIT, UiText.COMMAND_TOGGLE_CASE, true, true, UiText.MENU_SEARCH),
  /** Toggle regex and fixed-text search. */
  TOGGLE_REGEX(CommandGroup.EDIT, UiText.COMMAND_TOGGLE_REGEX, false, true, UiText.MENU_SEARCH),
  /** Toggle inverse search. */
  TOGGLE_INVERSE(CommandGroup.EDIT, UiText.COMMAND_TOGGLE_INVERSE, false, true, UiText.MENU_SEARCH),
  /** Toggle combined expressions. */
  TOGGLE_COMBINING(
      CommandGroup.EDIT, UiText.COMMAND_TOGGLE_COMBINING, false, true, UiText.MENU_SEARCH),
  /** Toggle auto-refresh. */
  TOGGLE_AUTO_REFRESH(
      CommandGroup.EDIT, UiText.COMMAND_TOGGLE_AUTO_REFRESH, false, true, UiText.MENU_SEARCH),
  /** Toggle keeping the current results when the next search runs. */
  TOGGLE_KEEP_RESULTS(
      CommandGroup.EDIT, UiText.COMMAND_TOGGLE_KEEP_RESULTS, false, true, UiText.MENU_SEARCH),
  // Context lines and range filter.
  /** Toggle range search: from a line matching the search to the next line matching the end. */
  TOGGLE_RANGE(CommandGroup.EDIT, UiText.COMMAND_TOGGLE_RANGE, false, true, UiText.MENU_SEARCH),
  /** Select the range around the main view's caret. */
  SELECT_RANGE(CommandGroup.EDIT, UiText.COMMAND_SELECT_RANGE, false, false, UiText.MENU_SEARCH),
  /** Add the selected text to the search as an alternative. */
  ADD_TO_SEARCH(CommandGroup.EDIT, UiText.COMMAND_ADD_TO_SEARCH, true, false, UiText.MENU_SEARCH),
  /** Exclude lines holding the selected text from the search. */
  EXCLUDE_FROM_SEARCH(
      CommandGroup.EDIT, UiText.COMMAND_EXCLUDE_FROM_SEARCH, false, false, UiText.MENU_SEARCH),
  /** Replace the search with the selected text. */
  REPLACE_SEARCH(
      CommandGroup.EDIT, UiText.COMMAND_REPLACE_SEARCH, false, false, UiText.MENU_SEARCH),
  /** Toggle running the search after add, exclude and replace. */
  RUN_SEARCH_ON_ADD(
      CommandGroup.EDIT, UiText.COMMAND_RUN_SEARCH_ON_ADD, false, true, UiText.MENU_SEARCH),
  /** Start searches at the current line. */
  SET_SEARCH_START(
      CommandGroup.EDIT, UiText.COMMAND_SET_SEARCH_START, true, false, UiText.MENU_SEARCH),
  /** End searches at the current line. */
  SET_SEARCH_END(
      CommandGroup.EDIT, UiText.COMMAND_SET_SEARCH_END, false, false, UiText.MENU_SEARCH),
  /** Search every line again. */
  CLEAR_SEARCH_LIMITS(
      CommandGroup.EDIT, UiText.COMMAND_CLEAR_SEARCH_LIMITS, false, false, UiText.MENU_SEARCH),
  /** Edit the search history. */
  EDIT_HISTORY(CommandGroup.EDIT, UiText.COMMAND_EDIT_HISTORY, true, false, UiText.MENU_SEARCH),
  /** Empty the search history. */
  CLEAR_HISTORY(CommandGroup.EDIT, UiText.COMMAND_CLEAR_HISTORY, false, false, UiText.MENU_SEARCH),
  /** Choose an open file from a filtered list and show its tab. */
  SWITCH_FILE(CommandGroup.VIEW, UiText.COMMAND_SWITCH_FILE, false, false),
  /** Show the tab to the right. */
  NEXT_TAB(CommandGroup.VIEW, UiText.COMMAND_NEXT_TAB, false, false),
  /** Show the tab to the left. */
  PREVIOUS_TAB(CommandGroup.VIEW, UiText.COMMAND_PREVIOUS_TAB, false, false),
  // Tab move between windows.
  /** Move the active tab to a new window. */
  MOVE_TO_NEW_WINDOW(CommandGroup.VIEW, UiText.COMMAND_MOVE_TO_NEW_WINDOW, false, false),
  // Command palette.
  /** Choose a command from a filtered list and run it. */
  COMMAND_PALETTE(CommandGroup.VIEW, UiText.COMMAND_COMMAND_PALETTE, true, false),
  /** Follow the end of the active file. */
  FOLLOW(CommandGroup.VIEW, UiText.COMMAND_FOLLOW, true, true),
  /** Set {@code follow.onLoad}. */
  FOLLOW_ON_LOAD(CommandGroup.VIEW, UiText.COMMAND_FOLLOW_ON_LOAD, false, true),
  /** Set {@code follow.onOverscroll}. */
  FOLLOW_ON_OVERSCROLL(CommandGroup.VIEW, UiText.COMMAND_FOLLOW_ON_OVERSCROLL, false, true),
  /** Reopen the active file and index it again. */
  RELOAD(CommandGroup.VIEW, UiText.COMMAND_RELOAD, true, false),
  /** Cancel a go to time, a mark toggle and exports; with no go to time, also indexing. */
  STOP(CommandGroup.VIEW, UiText.COMMAND_STOP, false, false),
  /** Shows or hides the overview bar beside the main view. */
  TOGGLE_OVERVIEW(CommandGroup.VIEW, UiText.COMMAND_TOGGLE_OVERVIEW, true, true),
  /** Colors the last search's matched text in both views. */
  TOGGLE_MATCH_HIGHLIGHT(CommandGroup.VIEW, UiText.COMMAND_TOGGLE_MATCH_HIGHLIGHT, false, true),
  /** Show or hide line numbers in the main view. */
  TOGGLE_LINE_NUMBERS(CommandGroup.VIEW, UiText.COMMAND_TOGGLE_LINE_NUMBERS, true, true),
  /** Show or hide source line numbers in the filtered view. */
  TOGGLE_FILTERED_LINE_NUMBERS(
      CommandGroup.VIEW, UiText.COMMAND_TOGGLE_FILTERED_LINE_NUMBERS, false, true),
  /** Wrap long lines in both views of the shown file. */
  TOGGLE_WRAP(CommandGroup.VIEW, UiText.COMMAND_TOGGLE_WRAP, true, true),
  /** Set {@code view.wrapOnOpen}. */
  WRAP_ON_OPEN(CommandGroup.VIEW, UiText.COMMAND_WRAP_ON_OPEN, false, true),
  /** Mark tabs, trailing spaces and line ends. */
  TOGGLE_WHITESPACE(CommandGroup.VIEW, UiText.COMMAND_TOGGLE_WHITESPACE, false, true),
  /** Remove ANSI color sequences for display and search. */
  HIDE_ANSI(CommandGroup.VIEW, UiText.COMMAND_HIDE_ANSI, false, true),
  /** Collapse the entry at the current line, or expand it. */
  TOGGLE_FOLD(CommandGroup.VIEW, UiText.COMMAND_TOGGLE_FOLD, false, false),
  /** Collapse every multi-line entry as it is shown. */
  COLLAPSE_ALL_FOLDS(CommandGroup.VIEW, UiText.COMMAND_COLLAPSE_ALL_FOLDS, false, false),
  /** Expand every entry. */
  EXPAND_ALL_FOLDS(CommandGroup.VIEW, UiText.COMMAND_EXPAND_ALL_FOLDS, false, false),
  // Timestamps.
  /** Edit the user timestamp formats; the cascade lists the formats before it. */
  TIMESTAMP_FORMATS(
      CommandGroup.VIEW, UiText.COMMAND_TIMESTAMP_FORMATS, true, false, UiText.MENU_TIMESTAMPS),
  /** Choose the zone of the shown file's local times. */
  TIME_ZONE(CommandGroup.VIEW, UiText.COMMAND_TIME_ZONE, true, false, UiText.MENU_TIMESTAMPS),
  /** Set the time offset of the shown tab. */
  TIME_OFFSET(CommandGroup.VIEW, UiText.COMMAND_TIME_OFFSET, false, false, UiText.MENU_TIMESTAMPS),
  // Time spread: radio items of a cascade inside Timestamps (MenuTable).
  /** Show no time spread in the overview bar. */
  TIME_SPREAD_OFF(
      CommandGroup.VIEW, UiText.COMMAND_TIME_SPREAD_OFF, true, true, UiText.MENU_TIMESTAMPS),
  /** Shade the overview bar where lines come faster than average. */
  TIME_SPREAD_ACTIVITY(
      CommandGroup.VIEW, UiText.COMMAND_TIME_SPREAD_ACTIVITY, false, true, UiText.MENU_TIMESTAMPS),
  /** Shade the overview bar where lines span more time than average. */
  TIME_SPREAD_PAUSES(
      CommandGroup.VIEW, UiText.COMMAND_TIME_SPREAD_PAUSES, false, true, UiText.MENU_TIMESTAMPS),
  /** List marks and matches in the filtered view. */
  SHOW_MARKS_AND_MATCHES(
      CommandGroup.VIEW, UiText.COMMAND_SHOW_MARKS_AND_MATCHES, true, true, UiText.MENU_VISIBILITY),
  /** List marks only. */
  SHOW_MARKS(CommandGroup.VIEW, UiText.COMMAND_SHOW_MARKS, false, true, UiText.MENU_VISIBILITY),
  /** List matches only. */
  SHOW_MATCHES(CommandGroup.VIEW, UiText.COMMAND_SHOW_MATCHES, false, true, UiText.MENU_VISIBILITY),
  /** Switch the filtered view to the next mode. */
  NEXT_VISIBILITY(
      CommandGroup.VIEW, UiText.COMMAND_NEXT_VISIBILITY, true, false, UiText.MENU_VISIBILITY),
  /** Switch the filtered view to the previous mode. */
  PREVIOUS_VISIBILITY(
      CommandGroup.VIEW, UiText.COMMAND_PREVIOUS_VISIBILITY, false, false, UiText.MENU_VISIBILITY),
  /** Give the main view more of the window. */
  GROW_MAIN_VIEW(CommandGroup.VIEW, UiText.COMMAND_GROW_MAIN_VIEW, true, false),
  /** Give the main view less of the window. */
  SHRINK_MAIN_VIEW(CommandGroup.VIEW, UiText.COMMAND_SHRINK_MAIN_VIEW, false, false),
  /** Keep the current split as the default. */
  SAVE_SPLIT(CommandGroup.VIEW, UiText.COMMAND_SAVE_SPLIT, false, false),
  /** Make the log views' font one point larger. */
  ZOOM_IN(CommandGroup.VIEW, UiText.COMMAND_ZOOM_IN, true, false),
  /** Make the log views' font one point smaller. */
  ZOOM_OUT(CommandGroup.VIEW, UiText.COMMAND_ZOOM_OUT, false, false),
  /** Return the log views' font to the size set in Options. */
  ZOOM_RESET(CommandGroup.VIEW, UiText.COMMAND_ZOOM_RESET, false, false),
  /** Light palette. */
  THEME_LIGHT(CommandGroup.VIEW, UiText.COMMAND_THEME_LIGHT, true, true, UiText.MENU_THEME),
  /** Dark palette. */
  THEME_DARK(CommandGroup.VIEW, UiText.COMMAND_THEME_DARK, false, true, UiText.MENU_THEME),
  /** The palette that follows the system setting. */
  THEME_SYSTEM(CommandGroup.VIEW, UiText.COMMAND_THEME_SYSTEM, false, true, UiText.MENU_THEME),
  /** Edit the predefined filters. */
  PREDEFINED_FILTERS(CommandGroup.TOOLS, UiText.COMMAND_PREDEFINED_FILTERS, false, false),
  /** Save the search text as a predefined filter. */
  SAVE_AS_FILTER(CommandGroup.TOOLS, UiText.COMMAND_SAVE_AS_FILTER, false, false),
  /** Opens the highlighters editor. */
  EDIT_HIGHLIGHTERS(CommandGroup.HIGHLIGHT, UiText.COMMAND_EDIT_HIGHLIGHTERS, false, false),
  /** Switches every highlighter set off; the menu lists the sets after it. */
  NO_HIGHLIGHTERS(CommandGroup.HIGHLIGHT, UiText.COMMAND_NO_HIGHLIGHTERS, true, true),
  /** Labels the selected text with the next color of the cycle. */
  NEXT_COLOR_LABEL(CommandGroup.HIGHLIGHT, UiText.COMMAND_NEXT_COLOR_LABEL, true, false),
  /** Removes every color label and restarts the cycle. */
  CLEAR_COLOR_LABELS(CommandGroup.HIGHLIGHT, UiText.COMMAND_CLEAR_COLOR_LABELS, false, false),
  // Encoding and Favorites menus.
  /** Detect the shown file's encoding; the menu lists the encodings to force after it. */
  ENCODING_AUTO(CommandGroup.ENCODING, UiText.COMMAND_ENCODING_AUTO, false, true),
  /** Add the shown file to the favorites. */
  ADD_FAVORITE(CommandGroup.FAVORITES, UiText.COMMAND_ADD_FAVORITE, false, false),
  /** Remove the shown file from the favorites. */
  REMOVE_FAVORITE(CommandGroup.FAVORITES, UiText.COMMAND_REMOVE_FAVORITE, false, false),
  /** Open the user guide shipped in the jar. */
  DOCUMENTATION(CommandGroup.HELP, UiText.COMMAND_DOCUMENTATION, false, false),
  /** Show the key bindings. */
  KEYBOARD_SHORTCUTS(CommandGroup.HELP, UiText.COMMAND_KEYBOARD_SHORTCUTS, true, false),
  /** Show the command-line usage. */
  COMMAND_LINE_OPTIONS(CommandGroup.HELP, UiText.COMMAND_COMMAND_LINE_OPTIONS, false, false),
  // Logging and support items.
  /** Show the recent log lines. */
  SHOW_LOG(CommandGroup.HELP, UiText.COMMAND_SHOW_LOG, true, false),
  /** Write the log to the debug log file too. */
  DEBUG_LOG_FILE(CommandGroup.HELP, UiText.COMMAND_DEBUG_LOG_FILE, false, true),
  /** Open the log folder in the file manager. */
  OPEN_LOG_FOLDER(CommandGroup.HELP, UiText.COMMAND_OPEN_LOG_FOLDER, false, false),
  /** Open the settings folder in the file manager. */
  OPEN_SETTINGS_FOLDER(CommandGroup.HELP, UiText.COMMAND_OPEN_SETTINGS_FOLDER, false, false),
  /** Copy the runtime details and open the project's issue page. */
  REPORT_ISSUE(CommandGroup.HELP, UiText.COMMAND_REPORT_ISSUE, false, false),
  /** Show the About dialog. */
  ABOUT(CommandGroup.HELP, UiText.COMMAND_ABOUT, true, false),
  /** Move the current line up. */
  LINE_UP(CommandGroup.LOG_VIEW, UiText.COMMAND_LINE_UP, false, false),
  /** Move the current line down. */
  LINE_DOWN(CommandGroup.LOG_VIEW, UiText.COMMAND_LINE_DOWN, false, false),
  /** Extend the selection up one line. */
  EXTEND_UP(CommandGroup.LOG_VIEW, UiText.COMMAND_EXTEND_UP, false, false),
  /** Extend the selection down one line. */
  EXTEND_DOWN(CommandGroup.LOG_VIEW, UiText.COMMAND_EXTEND_DOWN, false, false),
  /** Scroll up one line without moving the current line. */
  SCROLL_UP(CommandGroup.LOG_VIEW, UiText.COMMAND_SCROLL_UP, false, false),
  /** Scroll down one line without moving the current line. */
  SCROLL_DOWN(CommandGroup.LOG_VIEW, UiText.COMMAND_SCROLL_DOWN, false, false),
  /** Move up one page. */
  PAGE_UP(CommandGroup.LOG_VIEW, UiText.COMMAND_PAGE_UP, false, false),
  /** Move down one page. */
  PAGE_DOWN(CommandGroup.LOG_VIEW, UiText.COMMAND_PAGE_DOWN, false, false),
  /** Scroll one column left. */
  SCROLL_LEFT(CommandGroup.LOG_VIEW, UiText.COMMAND_SCROLL_LEFT, false, false),
  /** Scroll one column right. */
  SCROLL_RIGHT(CommandGroup.LOG_VIEW, UiText.COMMAND_SCROLL_RIGHT, false, false),
  /** Scroll to the first column. */
  LINE_START(CommandGroup.LOG_VIEW, UiText.COMMAND_LINE_START, false, false),
  /** Scroll to the end of the current line. */
  LINE_END(CommandGroup.LOG_VIEW, UiText.COMMAND_LINE_END, false, false),
  /** Scroll right until the longest visible line ends at the right edge. */
  RIGHT_EDGE(CommandGroup.LOG_VIEW, UiText.COMMAND_RIGHT_EDGE, false, false),
  /** Open quick find to search forward. */
  QUICK_FIND_FORWARD(CommandGroup.LOG_VIEW, UiText.COMMAND_QUICK_FIND_FORWARD, false, false),
  /** Open quick find to search backward. */
  QUICK_FIND_BACKWARD(CommandGroup.LOG_VIEW, UiText.COMMAND_QUICK_FIND_BACKWARD, false, false),
  /** Quick find the selected text forward. */
  FIND_SELECTION_FORWARD(
      CommandGroup.LOG_VIEW, UiText.COMMAND_FIND_SELECTION_FORWARD, false, false),
  /** Quick find the selected text backward. */
  FIND_SELECTION_BACKWARD(
      CommandGroup.LOG_VIEW, UiText.COMMAND_FIND_SELECTION_BACKWARD, false, false),
  /** Move focus out of the log view to the search field. */
  RELEASE_FOCUS(CommandGroup.LOG_VIEW, UiText.COMMAND_RELEASE_FOCUS, false, false),
  /** Color label 1; the labels are contiguous in this order. */
  COLOR_LABEL_1(CommandGroup.LOG_VIEW, UiText.COMMAND_COLOR_LABEL_1, false, false),
  /** Color label 2. */
  COLOR_LABEL_2(CommandGroup.LOG_VIEW, UiText.COMMAND_COLOR_LABEL_2, false, false),
  /** Color label 3. */
  COLOR_LABEL_3(CommandGroup.LOG_VIEW, UiText.COMMAND_COLOR_LABEL_3, false, false),
  /** Color label 4. */
  COLOR_LABEL_4(CommandGroup.LOG_VIEW, UiText.COMMAND_COLOR_LABEL_4, false, false),
  /** Color label 5. */
  COLOR_LABEL_5(CommandGroup.LOG_VIEW, UiText.COMMAND_COLOR_LABEL_5, false, false),
  /** Color label 6. */
  COLOR_LABEL_6(CommandGroup.LOG_VIEW, UiText.COMMAND_COLOR_LABEL_6, false, false),
  /** Color label 7. */
  COLOR_LABEL_7(CommandGroup.LOG_VIEW, UiText.COMMAND_COLOR_LABEL_7, false, false),
  /** Color label 8. */
  COLOR_LABEL_8(CommandGroup.LOG_VIEW, UiText.COMMAND_COLOR_LABEL_8, false, false),
  /** Color label 9. */
  COLOR_LABEL_9(CommandGroup.LOG_VIEW, UiText.COMMAND_COLOR_LABEL_9, false, false),
  // Copy variants, block selection and search across open files.
  /** Copy the selection with each line's number in front. */
  COPY_WITH_LINE_NUMBERS(CommandGroup.EDIT, UiText.COMMAND_COPY_WITH_LINE_NUMBERS, true, false),
  /** Make a drag select a rectangle of columns. */
  BLOCK_SELECTION(CommandGroup.EDIT, UiText.COMMAND_BLOCK_SELECTION, false, true),
  /** Run the search in every open file. */
  SEARCH_ALL_FILES(CommandGroup.EDIT, UiText.COMMAND_SEARCH_ALL_FILES, false, false),
  // Search in a folder.
  /** Search the files of a folder without opening them. */
  SEARCH_FOLDER(CommandGroup.EDIT, UiText.COMMAND_SEARCH_FOLDER, false, false),
  // Marks list.
  /** Show the window listing the shown file's marks. */
  MARKS_LIST(CommandGroup.TOOLS, UiText.COMMAND_MARKS_LIST, true, false),
  // Scratchpad, Options dialog and automatic highlighter sets.
  /** Open the scratchpad window. */
  SCRATCHPAD(CommandGroup.TOOLS, UiText.COMMAND_SCRATCHPAD, true, false),
  /** Open the Options dialog. */
  OPTIONS(CommandGroup.TOOLS, UiText.COMMAND_OPTIONS, true, false),
  /** Edit the rules that pick a highlighter set for a file. */
  AUTO_HIGHLIGHTERS(CommandGroup.HIGHLIGHT, UiText.COMMAND_AUTO_HIGHLIGHTERS, true, false),
  /** Append the selection to the scratchpad. */
  SEND_TO_SCRATCHPAD(CommandGroup.LOG_VIEW, UiText.COMMAND_SEND_TO_SCRATCHPAD, false, false),
  /** Replace the scratchpad's text with the selection. */
  REPLACE_SCRATCHPAD(CommandGroup.LOG_VIEW, UiText.COMMAND_REPLACE_SCRATCHPAD, false, false),
  /** Extend a block selection up one row. */
  EXTEND_BLOCK_UP(CommandGroup.LOG_VIEW, UiText.COMMAND_EXTEND_BLOCK_UP, false, false),
  /** Extend a block selection down one row. */
  EXTEND_BLOCK_DOWN(CommandGroup.LOG_VIEW, UiText.COMMAND_EXTEND_BLOCK_DOWN, false, false),
  /** Extend a block selection left one column. */
  EXTEND_BLOCK_LEFT(CommandGroup.LOG_VIEW, UiText.COMMAND_EXTEND_BLOCK_LEFT, false, false),
  /** Extend a block selection right one column. */
  EXTEND_BLOCK_RIGHT(CommandGroup.LOG_VIEW, UiText.COMMAND_EXTEND_BLOCK_RIGHT, false, false);

  private final CommandGroup group;
  private final UiText label;
  private final boolean separatorBefore;
  private final boolean check;
  private final UiText submenu;

  CommandId(CommandGroup group, UiText label, boolean separatorBefore, boolean check) {
    this(group, label, separatorBefore, check, null);
  }

  CommandId(
      CommandGroup group, UiText label, boolean separatorBefore, boolean check, UiText submenu) {
    this.group = group;
    this.label = label;
    this.separatorBefore = separatorBefore;
    this.check = check;
    this.submenu = submenu;
  }

  /**
   * Returns the cascade the command sits in within its menu.
   *
   * @return the cascade's label, with {@code &} before the mnemonic, or null for the menu itself
   */
  public String submenu() {
    return submenu == null ? null : submenu.text();
  }

  /**
   * Returns the group.
   *
   * @return the menu or {@link CommandGroup#LOG_VIEW}
   */
  public CommandGroup group() {
    return group;
  }

  /**
   * Returns the menu label, with {@code &} before the mnemonic.
   *
   * @return the label
   */
  public String label() {
    return label.text();
  }

  /**
   * Returns the label without the mnemonic marker or a trailing ellipsis.
   *
   * @return for example {@code Go to Line}
   */
  public String title() {
    String plain = label().replace("&", "");
    return plain.endsWith("...") ? plain.substring(0, plain.length() - 3) : plain;
  }

  /**
   * Reports whether a menu separator precedes the command.
   *
   * @return true at the start of a menu section
   */
  public boolean separatorBefore() {
    return separatorBefore;
  }

  /**
   * Reports whether the menu shows the command as a check item.
   *
   * @return true for a toggle or a mode
   */
  public boolean check() {
    return check;
  }
}
