package io.github.ajdailey.qlogg.app.command;

import io.github.ajdailey.qlogg.app.i18n.UiText;

/**
 * Every command a menu item or key can run. Menus list their commands in declaration order; {@code
 * separatorBefore} starts a new section, a {@code check} command shows as a check item, and
 * consecutive commands with the same {@code submenu} form one cascade in their menu. The color
 * labels are contiguous, in label order.
 */
public enum CommandId {
  // File menu.
  /** Choose files and open them. */
  OPEN(CommandGroup.FILE, UiText.COMMAND_OPEN, false, false),
  /** Open the clipboard's text in a new tab. */
  OPEN_CLIPBOARD(CommandGroup.FILE, UiText.COMMAND_OPEN_CLIPBOARD, false, false),
  /** Open every recent file. */
  OPEN_ALL_RECENT(
      CommandGroup.FILE, UiText.COMMAND_OPEN_ALL_RECENT, true, false, UiText.MENU_RECENT_FILES),
  /** Drop the recent files that no longer exist; the cascade lists the recent files before it. */
  CLEAN_RECENT(
      CommandGroup.FILE, UiText.COMMAND_CLEAN_RECENT, false, false, UiText.MENU_RECENT_FILES),
  // Clear Recent Files.
  /** Empty the recent files list. */
  CLEAR_RECENT(
      CommandGroup.FILE, UiText.COMMAND_CLEAR_RECENT, false, false, UiText.MENU_RECENT_FILES),
  /** Open the newest Recently Closed file again. */
  REOPEN_CLOSED(CommandGroup.FILE, UiText.COMMAND_REOPEN_CLOSED, false, false),
  /** Open every recently closed file. */
  OPEN_ALL_CLOSED(
      CommandGroup.FILE, UiText.COMMAND_OPEN_ALL_CLOSED, false, false, UiText.MENU_RECENTLY_CLOSED),
  /** Empty Recently Closed; the cascade lists the closed files before Open All. */
  CLEAR_CLOSED(
      CommandGroup.FILE, UiText.COMMAND_CLEAR_CLOSED, false, false, UiText.MENU_RECENTLY_CLOSED),
  /** Add the shown file to the favorites. */
  ADD_FAVORITE(CommandGroup.FILE, UiText.COMMAND_ADD_FAVORITE, false, false, UiText.MENU_FAVORITES),
  /** Remove the shown file from the favorites. */
  REMOVE_FAVORITE(
      CommandGroup.FILE, UiText.COMMAND_REMOVE_FAVORITE, false, false, UiText.MENU_FAVORITES),
  /** Open the active file's folder in the system file manager. */
  OPEN_FOLDER(CommandGroup.FILE, UiText.COMMAND_OPEN_FOLDER, true, false),
  /** Open the active file in the system's application for it. */
  OPEN_IN_EDITOR(CommandGroup.FILE, UiText.COMMAND_OPEN_IN_EDITOR, false, false),
  /** Write the selected lines to a chosen file. */
  SAVE_SELECTION(CommandGroup.FILE, UiText.COMMAND_SAVE_SELECTION, true, false),
  /** Write the filtered view's lines to a chosen file. */
  SAVE_FILTERED(CommandGroup.FILE, UiText.COMMAND_SAVE_FILTERED, false, false),
  /** Close the active tab. */
  CLOSE(CommandGroup.FILE, UiText.COMMAND_CLOSE, true, false),
  /** Close every tab. */
  CLOSE_ALL(CommandGroup.FILE, UiText.COMMAND_CLOSE_ALL, false, false),
  /** Close every tab except the active one. */
  CLOSE_OTHERS(
      CommandGroup.FILE, UiText.TAB_CLOSE_OTHERS, false, false, UiText.MENU_CLOSE_MULTIPLE),
  /** Close the tabs before the active one. */
  CLOSE_LEFT(CommandGroup.FILE, UiText.TAB_CLOSE_LEFT, false, false, UiText.MENU_CLOSE_MULTIPLE),
  /** Close the tabs after the active one. */
  CLOSE_RIGHT(CommandGroup.FILE, UiText.TAB_CLOSE_RIGHT, false, false, UiText.MENU_CLOSE_MULTIPLE),
  /** Close every window and exit. */
  QUIT(CommandGroup.FILE, UiText.COMMAND_QUIT, true, false),
  // Edit menu.
  /** Copy the selection, or the current line when nothing is selected. */
  COPY(CommandGroup.EDIT, UiText.COMMAND_COPY, false, false),
  /** Copy the current line. */
  COPY_LINE(CommandGroup.EDIT, UiText.COMMAND_COPY_LINE, false, false),
  /** Copy the selection with each line's number in front. */
  COPY_WITH_LINE_NUMBERS(CommandGroup.EDIT, UiText.COMMAND_COPY_WITH_LINE_NUMBERS, false, false),
  /** Select every line. */
  SELECT_ALL(CommandGroup.EDIT, UiText.COMMAND_SELECT_ALL, false, false),
  /** Remember the current line as one end of a line selection. */
  SET_SELECTION_START(CommandGroup.EDIT, UiText.COMMAND_SET_SELECTION_START, true, false),
  /** Select the lines from the remembered line to the current line. */
  SET_SELECTION_END(CommandGroup.EDIT, UiText.COMMAND_SET_SELECTION_END, false, false),
  /** Make a drag select a rectangle of columns. */
  BLOCK_SELECTION(CommandGroup.EDIT, UiText.COMMAND_BLOCK_SELECTION, false, true),
  /** Copy the active file's full path. */
  COPY_PATH(CommandGroup.EDIT, UiText.COMMAND_COPY_PATH, true, false),
  /** Copy the active file's name. */
  COPY_FILE_NAME(CommandGroup.EDIT, UiText.COMMAND_COPY_FILE_NAME, false, false),
  // Search menu.
  /** Open the quick find bar. */
  FIND(CommandGroup.SEARCH, UiText.COMMAND_FIND, false, false),
  /** Go to the next match after the current line, or the next quick find match. */
  FIND_NEXT(CommandGroup.SEARCH, UiText.COMMAND_FIND_NEXT, false, false),
  /** Go to the previous match before the current line, or the previous quick find match. */
  FIND_PREVIOUS(CommandGroup.SEARCH, UiText.COMMAND_FIND_PREVIOUS, false, false),
  /** Move focus to the search field. */
  FOCUS_SEARCH(CommandGroup.SEARCH, UiText.COMMAND_FOCUS_SEARCH, true, false),
  // Clear Results.
  /** Cancel the shown tab's search and empty its results; kept sets stay. */
  CLEAR_RESULTS(CommandGroup.SEARCH, UiText.COMMAND_CLEAR_RESULTS, false, false),
  /** Run the search in every open file. */
  SEARCH_ALL_FILES(CommandGroup.SEARCH, UiText.COMMAND_SEARCH_ALL_FILES, false, false),
  /** Search the files of a folder without opening them. */
  SEARCH_FOLDER(CommandGroup.SEARCH, UiText.COMMAND_SEARCH_FOLDER, false, false),
  // Search in Folder step keys.
  /** Open the next listed line of the shown Search in Folder set. */
  NEXT_FOLDER_MATCH(CommandGroup.SEARCH, UiText.COMMAND_NEXT_FOLDER_MATCH, false, false),
  /** Open the previous listed line of the shown Search in Folder set. */
  PREVIOUS_FOLDER_MATCH(CommandGroup.SEARCH, UiText.COMMAND_PREVIOUS_FOLDER_MATCH, false, false),
  /** Add the selected text to the search as an alternative. */
  ADD_TO_SEARCH(CommandGroup.SEARCH, UiText.COMMAND_ADD_TO_SEARCH, true, false),
  /** Exclude lines holding the selected text from the search. */
  EXCLUDE_FROM_SEARCH(CommandGroup.SEARCH, UiText.COMMAND_EXCLUDE_FROM_SEARCH, false, false),
  /** Replace the search with the selected text. */
  REPLACE_SEARCH(CommandGroup.SEARCH, UiText.COMMAND_REPLACE_SEARCH, false, false),
  /** Select the range around the main view's caret. */
  SELECT_RANGE(CommandGroup.SEARCH, UiText.COMMAND_SELECT_RANGE, false, false),
  /** Toggle case-sensitive search. */
  TOGGLE_CASE(
      CommandGroup.SEARCH, UiText.COMMAND_TOGGLE_CASE, true, true, UiText.MENU_SEARCH_OPTIONS),
  /** Toggle regex and fixed-text search. */
  TOGGLE_REGEX(
      CommandGroup.SEARCH, UiText.COMMAND_TOGGLE_REGEX, false, true, UiText.MENU_SEARCH_OPTIONS),
  /** Toggle inverse search. */
  TOGGLE_INVERSE(
      CommandGroup.SEARCH, UiText.COMMAND_TOGGLE_INVERSE, false, true, UiText.MENU_SEARCH_OPTIONS),
  /** Toggle combined expressions. */
  TOGGLE_COMBINING(
      CommandGroup.SEARCH,
      UiText.COMMAND_TOGGLE_COMBINING,
      false,
      true,
      UiText.MENU_SEARCH_OPTIONS),
  /** Toggle range search: from a line matching the search to the next line matching the end. */
  TOGGLE_RANGE(
      CommandGroup.SEARCH, UiText.COMMAND_TOGGLE_RANGE, false, true, UiText.MENU_SEARCH_OPTIONS),
  /** Toggle auto-refresh. */
  TOGGLE_AUTO_REFRESH(
      CommandGroup.SEARCH,
      UiText.COMMAND_TOGGLE_AUTO_REFRESH,
      true,
      true,
      UiText.MENU_SEARCH_OPTIONS),
  /** Toggle keeping the current results when the next search runs. */
  TOGGLE_KEEP_RESULTS(
      CommandGroup.SEARCH,
      UiText.COMMAND_TOGGLE_KEEP_RESULTS,
      false,
      true,
      UiText.MENU_SEARCH_OPTIONS),
  /** Toggle running the search after add, exclude and replace. */
  RUN_SEARCH_ON_ADD(
      CommandGroup.SEARCH,
      UiText.COMMAND_RUN_SEARCH_ON_ADD,
      false,
      true,
      UiText.MENU_SEARCH_OPTIONS),
  /** Start searches at the current line. */
  SET_SEARCH_START(
      CommandGroup.SEARCH,
      UiText.COMMAND_SET_SEARCH_START,
      false,
      false,
      UiText.MENU_SEARCH_LIMITS),
  /** End searches at the current line. */
  SET_SEARCH_END(
      CommandGroup.SEARCH, UiText.COMMAND_SET_SEARCH_END, false, false, UiText.MENU_SEARCH_LIMITS),
  /** Search every line again. */
  CLEAR_SEARCH_LIMITS(
      CommandGroup.SEARCH,
      UiText.COMMAND_CLEAR_SEARCH_LIMITS,
      false,
      false,
      UiText.MENU_SEARCH_LIMITS),
  /** Edit the search history. */
  EDIT_HISTORY(CommandGroup.SEARCH, UiText.COMMAND_EDIT_HISTORY, true, false),
  /** Empty the search history. */
  CLEAR_HISTORY(CommandGroup.SEARCH, UiText.COMMAND_CLEAR_HISTORY, false, false),
  // Go menu.
  /** Ask for a line and go to it. */
  GO_TO_LINE(CommandGroup.GO, UiText.COMMAND_GO_TO_LINE, false, false),
  /** Ask for a time and go to the line with it. */
  GO_TO_TIME(CommandGroup.GO, UiText.COMMAND_GO_TO_TIME, false, false),
  /** Go to the first line. */
  GO_TO_TOP(CommandGroup.GO, UiText.COMMAND_GO_TO_TOP, false, false),
  /** Go to the last line. */
  GO_TO_BOTTOM(CommandGroup.GO, UiText.COMMAND_GO_TO_BOTTOM, false, false),
  /** Go back to the line before the last jump. */
  GO_BACK(CommandGroup.GO, UiText.COMMAND_GO_BACK, true, false),
  /** Go forward to the line Go Back left. */
  GO_FORWARD(CommandGroup.GO, UiText.COMMAND_GO_FORWARD, false, false),
  /** Mark or unmark the selected lines. */
  TOGGLE_MARK(CommandGroup.GO, UiText.COMMAND_TOGGLE_MARK, true, false),
  /** Go to the next mark. */
  NEXT_MARK(CommandGroup.GO, UiText.COMMAND_NEXT_MARK, false, false),
  /** Go to the previous mark. */
  PREVIOUS_MARK(CommandGroup.GO, UiText.COMMAND_PREVIOUS_MARK, false, false),
  /** Edit the comment of the current line's mark, marking the line when it is unmarked. */
  EDIT_MARK_COMMENT(CommandGroup.GO, UiText.COMMAND_EDIT_MARK_COMMENT, false, false),
  /** Show the window listing the shown file's marks. */
  MARKS_LIST(CommandGroup.GO, UiText.COMMAND_MARKS_LIST, false, false),
  /** Go to the caret line's time in every other tab. */
  SCROLL_TABS_TO_TIME(CommandGroup.GO, UiText.COMMAND_SCROLL_TABS_TO_TIME, true, false),
  // View menu.
  /** Follow the end of the active file. */
  FOLLOW(CommandGroup.VIEW, UiText.COMMAND_FOLLOW, false, true),
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
  /** Show or hide line numbers in the main view. */
  TOGGLE_LINE_NUMBERS(CommandGroup.VIEW, UiText.COMMAND_TOGGLE_LINE_NUMBERS, false, true),
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
  TOGGLE_FOLD(CommandGroup.VIEW, UiText.COMMAND_TOGGLE_FOLD, true, false, UiText.MENU_FOLDING),
  /** Collapse every multi-line entry as it is shown. */
  COLLAPSE_ALL_FOLDS(
      CommandGroup.VIEW, UiText.COMMAND_COLLAPSE_ALL_FOLDS, false, false, UiText.MENU_FOLDING),
  /** Expand every entry. */
  EXPAND_ALL_FOLDS(
      CommandGroup.VIEW, UiText.COMMAND_EXPAND_ALL_FOLDS, false, false, UiText.MENU_FOLDING),
  /** Edit the user timestamp formats; the cascade lists the formats before it. */
  TIMESTAMP_FORMATS(
      CommandGroup.VIEW, UiText.COMMAND_TIMESTAMP_FORMATS, false, false, UiText.MENU_TIMESTAMPS),
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
      CommandGroup.VIEW,
      UiText.COMMAND_SHOW_MARKS_AND_MATCHES,
      false,
      true,
      UiText.MENU_FILTERED_VIEW),
  /** List marks only. */
  SHOW_MARKS(CommandGroup.VIEW, UiText.COMMAND_SHOW_MARKS, false, true, UiText.MENU_FILTERED_VIEW),
  /** List matches only. */
  SHOW_MATCHES(
      CommandGroup.VIEW, UiText.COMMAND_SHOW_MATCHES, false, true, UiText.MENU_FILTERED_VIEW),
  /** Switch the filtered view to the next mode. */
  NEXT_VISIBILITY(
      CommandGroup.VIEW, UiText.COMMAND_NEXT_VISIBILITY, true, false, UiText.MENU_FILTERED_VIEW),
  /** Switch the filtered view to the previous mode. */
  PREVIOUS_VISIBILITY(
      CommandGroup.VIEW,
      UiText.COMMAND_PREVIOUS_VISIBILITY,
      false,
      false,
      UiText.MENU_FILTERED_VIEW),
  /** Give the main view more of the window. */
  GROW_MAIN_VIEW(
      CommandGroup.VIEW, UiText.COMMAND_GROW_MAIN_VIEW, true, false, UiText.MENU_FILTERED_VIEW),
  /** Give the main view less of the window. */
  SHRINK_MAIN_VIEW(
      CommandGroup.VIEW, UiText.COMMAND_SHRINK_MAIN_VIEW, false, false, UiText.MENU_FILTERED_VIEW),
  /** Keep the current split as the default. */
  SAVE_SPLIT(CommandGroup.VIEW, UiText.COMMAND_SAVE_SPLIT, false, false, UiText.MENU_FILTERED_VIEW),
  /** Make the log views' font one point larger. */
  ZOOM_IN(CommandGroup.VIEW, UiText.COMMAND_ZOOM_IN, true, false),
  /** Make the log views' font one point smaller. */
  ZOOM_OUT(CommandGroup.VIEW, UiText.COMMAND_ZOOM_OUT, false, false),
  /** Return the log views' font to the size set in Preferences. */
  ZOOM_RESET(CommandGroup.VIEW, UiText.COMMAND_ZOOM_RESET, false, false),
  /** Light palette. */
  THEME_LIGHT(CommandGroup.VIEW, UiText.COMMAND_THEME_LIGHT, true, true, UiText.MENU_THEME),
  /** Dark palette. */
  THEME_DARK(CommandGroup.VIEW, UiText.COMMAND_THEME_DARK, false, true, UiText.MENU_THEME),
  /** The palette that follows the system setting. */
  THEME_SYSTEM(CommandGroup.VIEW, UiText.COMMAND_THEME_SYSTEM, false, true, UiText.MENU_THEME),
  /** Keep the window above other windows; disabled where the platform offers no way. */
  ALWAYS_ON_TOP(CommandGroup.VIEW, UiText.COMMAND_ALWAYS_ON_TOP, false, true),
  /** Choose a command from a filtered list and run it. */
  COMMAND_PALETTE(CommandGroup.VIEW, UiText.COMMAND_COMMAND_PALETTE, true, false),
  // Highlighters menu.
  /** Opens the highlighters editor. */
  EDIT_HIGHLIGHTERS(CommandGroup.HIGHLIGHT, UiText.COMMAND_EDIT_HIGHLIGHTERS, false, false),
  /** Edit the rules that pick a highlighter set for a file. */
  AUTO_HIGHLIGHTERS(CommandGroup.HIGHLIGHT, UiText.COMMAND_AUTO_HIGHLIGHTERS, false, false),
  /** Switches every highlighter set off; the menu lists the sets after it. */
  NO_HIGHLIGHTERS(CommandGroup.HIGHLIGHT, UiText.COMMAND_NO_HIGHLIGHTERS, true, true),
  /** Colors the last search's matched text in both views. */
  TOGGLE_MATCH_HIGHLIGHT(CommandGroup.HIGHLIGHT, UiText.COMMAND_TOGGLE_MATCH_HIGHLIGHT, true, true),
  /** Labels the selected text with the next color of the cycle. */
  NEXT_COLOR_LABEL(CommandGroup.HIGHLIGHT, UiText.COMMAND_NEXT_COLOR_LABEL, true, false),
  /** Removes every color label and restarts the cycle. */
  CLEAR_COLOR_LABELS(CommandGroup.HIGHLIGHT, UiText.COMMAND_CLEAR_COLOR_LABELS, false, false),
  // Encoding menu.
  /** Detect the shown file's encoding; the menu lists the encodings to force after it. */
  ENCODING_AUTO(CommandGroup.ENCODING, UiText.COMMAND_ENCODING_AUTO, false, true),
  // Tools menu.
  /** Edit the predefined filters. */
  PREDEFINED_FILTERS(CommandGroup.TOOLS, UiText.COMMAND_PREDEFINED_FILTERS, false, false),
  /** Save the search text as a predefined filter. */
  SAVE_AS_FILTER(CommandGroup.TOOLS, UiText.COMMAND_SAVE_AS_FILTER, false, false),
  /** Open the scratchpad window. */
  SCRATCHPAD(CommandGroup.TOOLS, UiText.COMMAND_SCRATCHPAD, true, false),
  /** Open the Preferences dialog. */
  PREFERENCES(CommandGroup.TOOLS, UiText.COMMAND_PREFERENCES, true, false),
  // Window menu.
  /** Open an empty main window. */
  NEW_WINDOW(CommandGroup.WINDOW, UiText.COMMAND_NEW_WINDOW, false, false),
  /** Move the active tab to a new window. */
  MOVE_TO_NEW_WINDOW(CommandGroup.WINDOW, UiText.COMMAND_MOVE_TO_NEW_WINDOW, true, false),
  /** Show the tab to the right. */
  NEXT_TAB(CommandGroup.WINDOW, UiText.COMMAND_NEXT_TAB, true, false),
  /** Show the tab to the left. */
  PREVIOUS_TAB(CommandGroup.WINDOW, UiText.COMMAND_PREVIOUS_TAB, false, false),
  /** Choose an open file from a filtered list and show its tab. */
  SWITCH_FILE(CommandGroup.WINDOW, UiText.COMMAND_SWITCH_FILE, false, false),
  // Help menu.
  /** Open the user guide shipped in the jar. */
  USER_GUIDE(CommandGroup.HELP, UiText.COMMAND_USER_GUIDE, false, false),
  /** Show the key bindings. */
  KEYBOARD_SHORTCUTS(CommandGroup.HELP, UiText.COMMAND_KEYBOARD_SHORTCUTS, false, false),
  /** Show the command-line usage. */
  COMMAND_LINE_OPTIONS(CommandGroup.HELP, UiText.COMMAND_COMMAND_LINE_OPTIONS, false, false),
  /** Show the recent log lines. */
  SHOW_LOG(CommandGroup.HELP, UiText.COMMAND_SHOW_LOG, true, false),
  /** Write the log to the debug log file too. */
  DEBUG_LOG_FILE(CommandGroup.HELP, UiText.COMMAND_DEBUG_LOG_FILE, false, true),
  /** Open the log folder in the file manager. */
  OPEN_LOG_FOLDER(CommandGroup.HELP, UiText.COMMAND_OPEN_LOG_FOLDER, false, false),
  /** Open the settings folder in the file manager. */
  OPEN_SETTINGS_FOLDER(CommandGroup.HELP, UiText.COMMAND_OPEN_SETTINGS_FOLDER, false, false),
  /** Copy the runtime details and open the project's issue page. */
  REPORT_ISSUE(CommandGroup.HELP, UiText.COMMAND_REPORT_ISSUE, true, false),
  /** Show the About dialog. */
  ABOUT(CommandGroup.HELP, UiText.COMMAND_ABOUT, true, false),
  // Log view keys.
  /** In the filtered view, keep the row's line as a jump the main view can go back from. */
  GO_TO_RESULT(CommandGroup.LOG_VIEW, UiText.COMMAND_GO_TO_RESULT, false, false),
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
