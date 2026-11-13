package io.github.ajdailey.qlogg.app.i18n;

import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Every string the UI shows, keyed by constant name in the {@code messages} resource bundle of this
 * package. English is the only bundle shipped. A pattern takes {@link String#format} arguments,
 * formatted in {@link Locale#ROOT}; a label marks its mnemonic with {@code &}.
 */
public enum UiText {
  /** Window title without a file. */
  WINDOW_TITLE,
  /** Window title with a file: the file name. */
  WINDOW_TITLE_FILE,
  /** File menu. */
  GROUP_FILE,
  /** Edit menu. */
  GROUP_EDIT,
  /** View menu. */
  GROUP_VIEW,
  /** Tools menu. */
  GROUP_TOOLS,
  /** Help menu. */
  GROUP_HELP,
  /** The log view's own keys, listed in Keyboard Shortcuts. */
  GROUP_LOG_VIEW,
  /** Command label. */
  COMMAND_OPEN,
  /** Command label. */
  COMMAND_CLOSE,
  /** Command label. */
  COMMAND_QUIT,
  /** Command label. */
  COMMAND_COPY,
  /** Command label. */
  COMMAND_SELECT_ALL,
  /** Command label. */
  COMMAND_TOGGLE_LINE_NUMBERS,
  /** Command label. */
  COMMAND_FOCUS_SEARCH,
  /** Command label. */
  COMMAND_FIND_NEXT,
  /** Command label. */
  COMMAND_FIND_PREVIOUS,
  /** Command label. */
  COMMAND_GO_TO_LINE,
  /** Command label. */
  COMMAND_GO_TO_TOP,
  /** Command label. */
  COMMAND_GO_TO_BOTTOM,
  /** Command label. */
  COMMAND_KEYBOARD_SHORTCUTS,
  /** Command label. */
  COMMAND_COMMAND_LINE_OPTIONS,
  /** Command label. */
  COMMAND_ABOUT,
  /** Command label. */
  COMMAND_LINE_UP,
  /** Command label. */
  COMMAND_LINE_DOWN,
  /** Command label. */
  COMMAND_EXTEND_UP,
  /** Command label. */
  COMMAND_EXTEND_DOWN,
  /** Command label. */
  COMMAND_SCROLL_UP,
  /** Command label. */
  COMMAND_SCROLL_DOWN,
  /** Command label. */
  COMMAND_PAGE_UP,
  /** Command label. */
  COMMAND_PAGE_DOWN,
  /** Command label. */
  COMMAND_SCROLL_LEFT,
  /** Command label. */
  COMMAND_SCROLL_RIGHT,
  /** Command label. */
  COMMAND_LINE_START,
  /** Command label. */
  COMMAND_LINE_END,
  /** Command label. */
  COMMAND_RIGHT_EDGE,
  /** Button. */
  BUTTON_OK,
  /** Button. */
  BUTTON_CANCEL,
  /** Button. */
  BUTTON_CLOSE,
  /** Button. */
  BUTTON_COPY,
  /** Button. */
  BUTTON_LICENSES,
  /** Button. */
  BUTTON_COPY_DETAILS,
  /** File chooser title. */
  OPEN_DIALOG_TITLE,
  /** Error title when a file does not open. */
  OPEN_FAILED_TITLE,
  /** A chosen name that is not a path: the reason. */
  OPEN_INVALID_PATH,
  /** The file is the running instance's lock. */
  INSTANCE_LOCK_REFUSED,
  /** Copy error title. */
  COPY_TITLE,
  /** Copy refused: selection MiB, limit MiB. */
  COPY_TOO_LARGE,
  /** Copy read error: the reason. */
  COPY_READ_FAILED,
  // Copy and save cancel.
  /** Notice: a copy or save starts while one runs. */
  NOTICE_EXPORT_RUNNING,
  /** Indexing failure without a message. */
  INDEXING_FAILED,
  /** Status bar while indexing: percent. */
  STATUS_INDEXING,
  /** Status bar for a sparse line index. */
  STATUS_SPARSE_INDEX,
  /** Status bar while a key sequence waits for its second stroke: the first stroke. */
  STATUS_KEY_PREFIX,
  /** Go to Line prompt: the line count. */
  GO_TO_LINE_PROMPT,
  /** Keyboard Shortcuts filter hint. */
  SHORTCUTS_FILTER,
  /** Keyboard Shortcuts column. */
  SHORTCUTS_GROUP,
  /** Keyboard Shortcuts column. */
  SHORTCUTS_COMMAND,
  /** Keyboard Shortcuts column. */
  SHORTCUTS_KEYS,
  /** About heading: the version. */
  ABOUT_NAME,
  /** About tagline. */
  ABOUT_TAGLINE,
  /** About copyright. */
  ABOUT_COPYRIGHT,
  /** About license line. */
  ABOUT_LICENSE,
  /** Details line: the version. */
  DETAILS_VERSION,
  /** Details line: Java version and vendor. */
  DETAILS_JAVA,
  /** Details line: OS. */
  DETAILS_OS,
  /** Details line: toolkit. */
  DETAILS_TOOLKIT,
  /** Details line: settings directory and mode. */
  DETAILS_SETTINGS,
  /** Settings mode. */
  SETTINGS_EXPLICIT,
  /** Settings mode. */
  SETTINGS_PORTABLE,
  /** Settings mode. */
  SETTINGS_PER_USER,
  /** Licenses window title. */
  LICENSES_TITLE,
  /** Licenses heading: the file name. */
  LICENSES_HEADING,
  /** Licenses: a notice file missing from the build. */
  LICENSES_MISSING,
  /** Licenses read error: the reason. */
  LICENSES_READ_FAILED,
  /** Search field hint. */
  SEARCH_HINT,
  /** Search toggle. */
  SEARCH_REGEX,
  /** Search toggle. */
  SEARCH_MATCH_CASE,
  /** Search toggle. */
  SEARCH_WHOLE_WORD,
  /** Search toggle. */
  SEARCH_INVERSE,
  /** Search toggle. */
  SEARCH_COMBINE,
  /** Search toggle. */
  SEARCH_IN_SELECTION,
  /** Button. */
  BUTTON_SEARCH,
  /** Button. */
  BUTTON_STOP,
  /** Search status while running: percent, matches. */
  SEARCH_PROGRESS,
  /** Search status when done: matches. */
  SEARCH_DONE,
  /** Search status when stopped by the user: matches. */
  SEARCH_CANCELED,
  /** Search status at the result limit: matches. */
  SEARCH_LIMIT_RESULTS,
  /** Search status at the memory limit: matches. */
  SEARCH_LIMIT_MEMORY,
  /** Search status on failure: the reason. */
  SEARCH_FAILED,
  /** Search failure reason when the application is shutting down. */
  SEARCH_SHUT_DOWN,
  /** Search failure reason when a worker failed without a message. */
  SEARCH_WORKER_FAILED,
  /** Regex error: 1-based column, the JDK's description. */
  SEARCH_REGEX_ERROR,
  /** Combined expression error: 1-based column, the reason. */
  SEARCH_EXPRESSION_ERROR,
  /** Expression error reason. */
  EXPRESSION_UNTERMINATED_QUOTE,
  /** Expression error reason. */
  EXPRESSION_EXPECTED_TERM,
  /** Expression error reason. */
  EXPRESSION_EXPECTED_CLOSE,
  /** Expression error reason. */
  EXPRESSION_EXPECTED_OPERATOR,
  /** Expression error reason. */
  EXPRESSION_UNKNOWN_WORD,
  /** Expression error reason. */
  /** Command label. */
  COMMAND_COPY_LINE,
  /** Command label. */
  COMMAND_SET_SELECTION_START,
  /** Command label. */
  COMMAND_SET_SELECTION_END,
  /** Command label. */
  COMMAND_FIND,
  /** Command label. */
  COMMAND_TOGGLE_FILTERED_LINE_NUMBERS,
  /** Command label. */
  COMMAND_SHOW_MARKS_AND_MATCHES,
  /** Command label. */
  COMMAND_SHOW_MARKS,
  /** Command label. */
  COMMAND_SHOW_MATCHES,
  /** Command label. */
  COMMAND_NEXT_VISIBILITY,
  /** Command label. */
  COMMAND_PREVIOUS_VISIBILITY,
  /** Command label. */
  COMMAND_GROW_MAIN_VIEW,
  /** Command label. */
  COMMAND_SHRINK_MAIN_VIEW,
  /** Command label. */
  COMMAND_SAVE_SPLIT,
  /** Command label. */
  COMMAND_TOGGLE_CASE,
  /** Command label. */
  COMMAND_TOGGLE_REGEX,
  /** Command label. */
  COMMAND_TOGGLE_INVERSE,
  /** Command label. */
  COMMAND_TOGGLE_COMBINING,
  /** Command label. */
  COMMAND_TOGGLE_AUTO_REFRESH,
  /** Command label. */
  COMMAND_TOGGLE_KEEP_RESULTS,
  /** Command label. */
  COMMAND_ADD_TO_SEARCH,
  /** Command label. */
  COMMAND_EXCLUDE_FROM_SEARCH,
  /** Command label. */
  COMMAND_REPLACE_SEARCH,
  /** Command label. */
  COMMAND_RUN_SEARCH_ON_ADD,
  /** Command label. */
  COMMAND_SET_SEARCH_START,
  /** Command label. */
  COMMAND_SET_SEARCH_END,
  /** Command label. */
  COMMAND_CLEAR_SEARCH_LIMITS,
  /** Command label. */
  COMMAND_PREDEFINED_FILTERS,
  /** Command label. */
  COMMAND_SAVE_AS_FILTER,
  /** Command label. */
  COMMAND_EDIT_HISTORY,
  /** Command label. */
  COMMAND_CLEAR_HISTORY,
  /** Command label. */
  COMMAND_TOGGLE_MARK,
  /** Command label. */
  COMMAND_NEXT_MARK,
  /** Command label. */
  COMMAND_PREVIOUS_MARK,
  /** Command label. */
  COMMAND_QUICK_FIND_FORWARD,
  /** Command label. */
  COMMAND_QUICK_FIND_BACKWARD,
  /** Command label. */
  COMMAND_FIND_SELECTION_FORWARD,
  /** Command label. */
  COMMAND_FIND_SELECTION_BACKWARD,
  /** Command label. */
  COMMAND_RELEASE_FOCUS,
  /** Filter import: the file is too large. */
  FILTER_FILE_TOO_LARGE,
  /** Filter import: the file does not parse. */
  FILTER_FILE_NOT_PROPERTIES,
  /** Filter import: the version is missing or other. */
  FILTER_FILE_NOT_EXPORT,
  /** Filter import: an entry is malformed. */
  FILTER_FILE_INCOMPLETE,
  /** Filter import or save: too many filters. */
  FILTER_FILE_TOO_MANY,
  /** Notice: a command needs selected text. */
  SELECT_TEXT_FIRST,
  /** Notice: find next found nothing. */
  NOTICE_END_OF_FILE,
  /** Notice: find previous found nothing. */
  NOTICE_START_OF_FILE,
  /** Notice: no next mark. */
  NOTICE_NO_NEXT_MARK,
  /** Notice: no previous mark. */
  NOTICE_NO_PREVIOUS_MARK,
  /** Notice: search start set. */
  NOTICE_SEARCH_START,
  /** Notice: search end set. */
  NOTICE_SEARCH_END,
  /** Notice: search limits cleared. */
  NOTICE_SEARCH_LIMITS_CLEARED,
  /** Notice: splitter position saved. */
  NOTICE_SPLIT_SAVED,
  /** Notice: Save as Filter added a filter. */
  NOTICE_FILTER_SAVED,
  /** Notice: Save as Filter found an equal filter. */
  NOTICE_FILTER_EXISTS,
  /** Status bar: caret line, line count and column. */
  STATUS_POSITION,
  /** Status bar: caret line and line count, without a column. */
  STATUS_LINE,
  /** Status bar: selected columns on one line. */
  STATUS_SELECTED_CHARS,
  /** Status bar: selected lines. */
  STATUS_SELECTED_LINES,
  /** Button label. */
  BUTTON_ADD,
  /** Button label. */
  BUTTON_REMOVE,
  /** Button label. */
  BUTTON_MOVE_UP,
  /** Button label. */
  BUTTON_MOVE_DOWN,
  /** Button label. */
  BUTTON_IMPORT,
  /** Button label. */
  BUTTON_EXPORT,
  /** Button label: empties the search field. */
  BUTTON_CLEAR,
  /** Button label. */
  BUTTON_NEXT,
  /** Button label. */
  BUTTON_PREVIOUS,
  /** Filters dialog field label. */
  FILTER_NAME,
  /** Filters dialog field label. */
  FILTER_PATTERN,
  /** Name and pattern of a filter just added. */
  FILTER_NEW,
  /** Filters dialog: an import or export is running. */
  FILTERS_WORKING,
  /** Filters dialog: import result. */
  FILTERS_IMPORTED,
  /** Filters dialog: export result. */
  FILTERS_EXPORTED,
  /** Filters dialog: import or export failure. */
  FILTERS_FILE_FAILED,
  /** Edit Search History prompt. */
  HISTORY_PROMPT,
  /** Edit Search History size label. */
  HISTORY_SIZE,
  /** Quick find field label. */
  QUICK_FIND_LABEL,
  /** Quick find toggle. */
  QUICK_FIND_IGNORE_CASE,
  /** Quick find toggle. */
  QUICK_FIND_INCREMENTAL,
  /** Quick find toggle. */
  QUICK_FIND_REGEX,
  /** Quick find notice while a find runs. */
  QUICK_FIND_RUNNING,
  /** Results tab: the search text and its match count. */
  RESULTS_TAB,
  /** Results tab with no search. */
  RESULTS_CURRENT_EMPTY,
  /** Search toggle. */
  SEARCH_AUTO_REFRESH,
  /** Search toggle. */
  SEARCH_KEEP_RESULTS,
  /** Search bar button opening the predefined filters menu. */
  SEARCH_FILTERS,
  /** Filtered view mode. */
  VISIBILITY_MARKS_AND_MATCHES,
  /** Filtered view mode. */
  VISIBILITY_MARKS,
  /** Filtered view mode. */
  VISIBILITY_MATCHES,
  /** Filtered view mode tooltip. */
  VISIBILITY_TOOLTIP,
  /** Highlighters menu. */
  GROUP_HIGHLIGHT,
  /** Command label. */
  COMMAND_TOGGLE_OVERVIEW,
  /** Command label. */
  COMMAND_TOGGLE_MATCH_HIGHLIGHT,
  /** Command label. */
  COMMAND_THEME_LIGHT,
  /** Command label. */
  COMMAND_THEME_DARK,
  /** Command label. */
  COMMAND_THEME_SYSTEM,
  /** Command label. */
  COMMAND_EDIT_HIGHLIGHTERS,
  /** Command label. */
  COMMAND_NO_HIGHLIGHTERS,
  /** Command label. */
  COMMAND_NEXT_COLOR_LABEL,
  /** Command label. */
  COMMAND_CLEAR_COLOR_LABELS,
  /** Command label. */
  COMMAND_STOP,
  /** Command label. */
  COMMAND_COLOR_LABEL_1,
  /** Command label. */
  COMMAND_COLOR_LABEL_2,
  /** Command label. */
  COMMAND_COLOR_LABEL_3,
  /** Command label. */
  COMMAND_COLOR_LABEL_4,
  /** Command label. */
  COMMAND_COLOR_LABEL_5,
  /** Command label. */
  COMMAND_COLOR_LABEL_6,
  /** Command label. */
  COMMAND_COLOR_LABEL_7,
  /** Command label. */
  COMMAND_COLOR_LABEL_8,
  /** Command label. */
  COMMAND_COLOR_LABEL_9,
  /** A color label's default name: its number. */
  LABEL_DEFAULT_NAME,
  /** Highlighter import of another file. */
  HIGHLIGHT_FILE_NOT_EXPORT,
  /** Highlighter import of a malformed set: its number. */
  HIGHLIGHT_FILE_INCOMPLETE,
  /** Highlighter import over the limit: the limit. */
  HIGHLIGHT_FILE_TOO_MANY,
  /** Mark refused while one runs. */
  MARK_RUNNING,
  /** Mark progress: percent. */
  MARK_PROGRESS,
  /** Mark stopped by the memory limit: lines marked. */
  MARK_MEMORY_LIMIT,
  /** Mark canceled: lines changed. */
  MARK_CANCELED,
  /** Mark of many rows ended: lines marked. */
  MARK_DONE,
  /** Unmark of many rows ended: lines unmarked. */
  UNMARK_DONE,
  // Mark comments and marks list.
  /** Command label. */
  COMMAND_EDIT_MARK_COMMENT,
  /** Command label. */
  COMMAND_MARKS_LIST,
  /** Mark comment dialog title. */
  MARK_COMMENT_TITLE,
  /** Mark comment dialog prompt: the 1-based line. */
  MARK_COMMENT_PROMPT,
  /** Notice: a mark comment was refused; the most comments per file. */
  NOTICE_COMMENT_NOT_KEPT,
  /** Marks list window title. */
  MARKS_TITLE,
  /** Marks list column. */
  MARKS_COLUMN_LINE,
  /** Marks list column. */
  MARKS_COLUMN_COMMENT,
  /** Marks list column. */
  MARKS_COLUMN_TEXT,
  /** Marks list summary: file title, marks. */
  MARKS_SUMMARY,
  /** Marks list summary when rows are left out: file title, rows listed, marks. */
  MARKS_SUMMARY_PARTIAL,
  /** Marks list summary without a shown file. */
  MARKS_NO_FILE,
  /** Marks list button. */
  MARKS_GO_TO,
  /** Marks list button. */
  MARKS_EDIT_COMMENT,
  /** Marks list button. */
  MARKS_REMOVE,
  /** Color label refused: the limit. */
  LABEL_TOO_MANY,
  /** Color label refused by the memory limit. */
  LABEL_MEMORY_LIMIT,
  /** The filtered view could not hold every mark. */
  FILTERED_MARKS_LIMIT,
  /** Highlighters dialog tab. */
  HIGHLIGHTERS_TAB_SETS,
  /** Highlighters dialog tab. */
  HIGHLIGHTERS_TAB_LABELS,
  /** Highlighters dialog tab. */
  HIGHLIGHTERS_TAB_MATCH,
  /** Highlighters dialog caption. */
  HIGHLIGHTERS_SETS,
  /** Highlighters dialog caption. */
  HIGHLIGHTERS_RULES,
  /** Name of a new set. */
  HIGHLIGHTERS_NEW_SET,
  /** Pattern of a new highlighter. */
  HIGHLIGHTERS_NEW_RULE,
  /** Highlighter import result: sets added. */
  HIGHLIGHTERS_IMPORTED,
  /** Highlighter export result: sets written. */
  HIGHLIGHTERS_EXPORTED,
  /** Highlighter option. */
  RULE_IGNORE_CASE,
  /** Highlighter option. */
  RULE_WHOLE_LINE,
  /** Highlighter color. */
  RULE_FOREGROUND,
  /** Highlighter color. */
  RULE_BACKGROUND,
  /** Highlighter option. */
  RULE_ALPHA,
  /** Highlighter option. */
  RULE_VARIANCE,
  /** Highlighter option. */
  RULE_ENABLED,
  /** Highlighter pattern error: the reason. */
  RULE_INVALID,
  /** Color label option. */
  LABEL_CYCLE,
  /** Match highlight option. */
  MATCH_ENABLED,
  /** Match highlight option. */
  MATCH_THEME_COLOR,
  /** Button opening the color chooser. */
  BUTTON_CHOOSE_COLOR,
  /** Save as Filter prompt. */
  FILTER_NAME_PROMPT,
  /** Context submenu. */
  MENU_COLOR_LABEL,
  /** Command label. */
  COMMAND_RELOAD,
  /** Command label. */
  COMMAND_CLOSE_ALL,
  /** Command label. */
  COMMAND_FOLLOW,
  /** Command label. */
  COMMAND_FOLLOW_ON_LOAD,
  /** Command label. */
  COMMAND_FOLLOW_ON_OVERSCROLL,
  /** Command label. */
  COMMAND_NEXT_TAB,
  /** Command label. */
  COMMAND_PREVIOUS_TAB,
  /** Notice: the file name. */
  NOTICE_FILE_TRUNCATED,
  /** Notice: the file name. */
  NOTICE_FILE_REPLACED,
  /** Notice: the file name. */
  NOTICE_FILE_MODIFIED,
  /** Notice: the file name. */
  NOTICE_FILE_MISSING,
  /** Notice: the file name and the reason. */
  NOTICE_RELOAD_FAILED,
  /** Notice: the line count. */
  NOTICE_LOADING_STOPPED,
  /** Status field. */
  STATUS_FOLLOWING,
  /** Status field. */
  STATUS_MISSING,
  /** Status field. */
  STATUS_LOADING_STOPPED,
  /** Status field: the encoding chosen by the user. */
  STATUS_ENCODING_FORCED,
  /** Tooltip of the top row's size field: the modification time. */
  STATUS_MODIFIED_TIP,
  /** File size in bytes. */
  SIZE_BYTES,
  /** File size in KiB. */
  SIZE_KIB,
  /** File size in MiB. */
  SIZE_MIB,
  /** File size in GiB. */
  SIZE_GIB,
  /** Context submenu item removing a label. */
  MENU_NONE,
  /** Command label. */
  COMMAND_OPEN_CLIPBOARD,
  /** Command label. */
  COMMAND_COPY_PATH,
  /** Command label. */
  COMMAND_OPEN_FOLDER,
  /** Command label. */
  COMMAND_OPEN_IN_EDITOR,
  /** Command label. */
  COMMAND_SWITCH_FILE,
  /** Name of a tab opened from the clipboard: its number in the session. */
  CLIPBOARD_TAB,
  /** Notice. */
  NOTICE_CLIPBOARD_EMPTY,
  /** Notice: the text's size and the limit, in MiB. */
  NOTICE_CLIPBOARD_TOO_LARGE,
  /** Notice: a path command on text opened from the clipboard. */
  NOTICE_NO_FILE,
  /** Notice: the file name. */
  NOTICE_EDITOR_REFUSED,
  /** Notice. */
  NOTICE_LAUNCH_FAILED,
  /** Notice: a reload kept only part of the marks. */
  NOTICE_MARKS_NOT_KEPT,
  /** View submenu listing the open files. */
  MENU_OPENED_FILES,
  /** Tab context menu item. */
  TAB_CLOSE,
  /** Tab context menu item. */
  TAB_CLOSE_OTHERS,
  /** Tab context menu item. */
  TAB_CLOSE_LEFT,
  /** Tab context menu item. */
  TAB_CLOSE_RIGHT,
  /** Tab context menu item. */
  TAB_CLOSE_ALL,
  /** Tab context menu item. */
  TAB_COPY_PATH,
  /** Tab context menu item. */
  TAB_COPY_FILE_NAME,
  /** Tab context menu item. */
  TAB_OPEN_FOLDER,
  /** Tab context menu item. */
  TAB_RENAME,
  /** Tab context menu item. */
  TAB_RESET_NAME,
  /** Rename Tab prompt. */
  TAB_NAME_PROMPT,
  /** Switch to File dialog's filter field label. */
  SWITCH_FILE_FILTER,
  /** Toolbar button. */
  TOOLBAR_OPEN,
  /** Toolbar button. */
  TOOLBAR_RELOAD,
  /** Toolbar check button. */
  TOOLBAR_FOLLOW,
  /** Toolbar button. */
  TOOLBAR_STOP,
  EXPRESSION_TOO_DEEP,
  /** Search menu. */
  GROUP_SEARCH,
  /** Command label. */
  COMMAND_COPY_FILE_NAME,
  /** Command label. */
  COMMAND_USER_GUIDE,
  /** Notice: Help > User Guide in a build without the user guide. */
  NOTICE_NO_DOCUMENTATION,
  /** Button. */
  BUTTON_APPLY,
  /** Predefined Filters table column. */
  FILTER_COLUMN_NAME,
  /** Predefined Filters table column. */
  FILTER_COLUMN_PATTERN,
  /** Predefined Filters table column. */
  FILTER_COLUMN_REGEX,
  /** Tooltip of the splitter between the main view and the filtered view. */
  SPLIT_GRIP,
  /** Path field context menu item. */
  PATH_COPY,
  /** Path field context menu item. */
  PATH_SELECT_ALL,
  // Folding, block selection, wrap, whitespace, save, search across files, scratchpad.
  /** Command label. */
  COMMAND_COPY_WITH_LINE_NUMBERS,
  /** Command label. */
  COMMAND_BLOCK_SELECTION,
  /** Command label. */
  COMMAND_SEARCH_ALL_FILES,
  /** Command label. */
  COMMAND_SAVE_SELECTION,
  /** Command label. */
  COMMAND_SAVE_FILTERED,
  /** Command label. */
  COMMAND_TOGGLE_WRAP,
  /** Command label. */
  COMMAND_WRAP_ON_OPEN,
  /** Command label. */
  COMMAND_TOGGLE_WHITESPACE,
  /** Command label. */
  COMMAND_HIDE_ANSI,
  /** Command label. */
  COMMAND_TOGGLE_FOLD,
  /** Command label. */
  COMMAND_COLLAPSE_ALL_FOLDS,
  /** Command label. */
  COMMAND_EXPAND_ALL_FOLDS,
  /** Command label. */
  COMMAND_SCRATCHPAD,
  /** Command label. */
  COMMAND_AUTO_HIGHLIGHTERS,
  /** Command label. */
  COMMAND_SEND_TO_SCRATCHPAD,
  /** Command label. */
  COMMAND_REPLACE_SCRATCHPAD,
  /** Command label. */
  COMMAND_EXTEND_BLOCK_UP,
  /** Command label. */
  COMMAND_EXTEND_BLOCK_DOWN,
  /** Command label. */
  COMMAND_EXTEND_BLOCK_LEFT,
  /** Command label. */
  COMMAND_EXTEND_BLOCK_RIGHT,
  /** Notice: no multi-line entry at the current line. */
  NOTICE_NO_FOLD,
  /** Notice: the fold share is full. */
  NOTICE_FOLD_LIMIT,
  /** Notice: a fold pattern does not compile; argument: the reason. */
  NOTICE_FOLD_RULES,
  /** Notice: block selection while text wrap is on. */
  NOTICE_BLOCK_WRAP,
  /** Save failure dialog title. */
  SAVE_TITLE,
  /** Save failure; argument: the reason. */
  SAVE_FAILED,
  /** Save refused; arguments: the lines' MiB and the largest MiB that fits. */
  SAVE_TOO_LARGE,
  /** Save refused: the target is an open file. */
  SAVE_OPEN_FILE,
  /** Notice; arguments: line count and file name. */
  NOTICE_SAVED,
  /** Notice: no lines to save. */
  NOTICE_NOTHING_TO_SAVE,
  /** Window title of the search across open files. */
  MULTI_TITLE,
  /** Results group; arguments: file title and matches so far. */
  MULTI_RUNNING,
  /** Results group; arguments: file title and matches. */
  MULTI_DONE,
  /** Results group; arguments: file title and matches. */
  MULTI_STOPPED,
  /** Results group; arguments: file title and the message. */
  MULTI_FAILED,
  /** Summary line; arguments: search text, matches and files. */
  MULTI_SUMMARY,
  /** Notice: search across files with an empty field. */
  MULTI_NO_TEXT,
  // Search in a folder.
  /** Command label. */
  COMMAND_SEARCH_FOLDER,
  /** Window title. */
  FOLDER_TITLE,
  /** Field label: the folder. */
  FOLDER_FOLDER,
  /** Button: choose the folder. */
  FOLDER_BROWSE,
  /** Field label: the file name globs. */
  FOLDER_NAMES,
  /** Check box: search subfolders. */
  FOLDER_RECURSIVE,
  /** Field label: the search text. */
  FOLDER_TEXT,
  /** Files table column. */
  FOLDER_COLUMN_FILE,
  /** Files table column. */
  FOLDER_COLUMN_MATCHES,
  /** Files table column. */
  FOLDER_COLUMN_STATUS,
  /** Lines table column. */
  FOLDER_COLUMN_LINE,
  /** Lines table column. */
  FOLDER_COLUMN_TEXT,
  /** File status: searched to the end. */
  FOLDER_STATUS_DONE,
  /** File status: the results share stopped the listing; argument: lines listed. */
  FOLDER_STATUS_PARTIAL,
  /** File status: not opened or read; argument: the reason. */
  FOLDER_STATUS_UNREADABLE,
  /** File status: compressed or binary. */
  FOLDER_STATUS_NOT_TEXT,
  /** File status: the search of the file failed; argument: the reason. */
  FOLDER_STATUS_FAILED,
  /** Summary before a run. */
  FOLDER_IDLE,
  /** Summary while the folder is walked. */
  FOLDER_WALKING,
  /** Summary while searching; arguments: file name, file number, files, percent, matches. */
  FOLDER_RUNNING,
  /** Summary at the end; arguments: text, matches, files with matches, files listed. */
  FOLDER_DONE,
  /** Summary at the results share stop; arguments: text and matches. */
  FOLDER_LIMITED,
  /** Summary after Stop; arguments: text, matches and files with matches. */
  FOLDER_STOPPED,
  /** Failure: no search text. */
  FOLDER_NO_TEXT,
  /** Failure: no folder. */
  FOLDER_NO_FOLDER,
  /** Failure: a glob that does not parse; argument: the reason. */
  FOLDER_BAD_NAMES,
  /** Failure: the folder does not validate or cannot be walked; argument: the reason. */
  FOLDER_FAILED,
  // Search in Folder exclude, kept sets and step keys.
  /** Field label: the file name globs left out. */
  FOLDER_EXCLUDE,
  /** Label of the kept sets list. */
  FOLDER_SETS,
  /** Kept set; arguments: search text, folder name and matches. */
  FOLDER_SET,
  /** Kept set while its search runs; argument: the set text. */
  FOLDER_SET_RUNNING,
  /** Pinned kept set; argument: the set text. */
  FOLDER_SET_PINNED,
  /** Toggle button: keep the shown set past the newest ten. */
  FOLDER_PIN,
  /** Button: drop the shown set. */
  FOLDER_CLEAR,
  /** Button: drop every set. */
  FOLDER_CLEAR_ALL,
  /** Command label. */
  COMMAND_NEXT_FOLDER_MATCH,
  /** Command label. */
  COMMAND_PREVIOUS_FOLDER_MATCH,
  /** Notice: a step key with no listed line in the shown set. */
  NOTICE_FOLDER_NO_LINES,
  /** Notice: Next passed the last listed line. */
  NOTICE_FOLDER_WRAPPED_FIRST,
  /** Notice: Previous passed the first listed line. */
  NOTICE_FOLDER_WRAPPED_LAST,
  /** Notice: a run dropped older sets to free memory. */
  NOTICE_FOLDER_SET_DROPPED,
  /** Scratchpad window title. */
  SCRATCHPAD_TITLE,
  /** Scratchpad tab; argument: its number. */
  SCRATCHPAD_TAB,
  /** Scratchpad button. */
  SCRATCHPAD_NEW_TAB,
  /** Scratchpad menu button. */
  SCRATCHPAD_TRANSFORMS,
  /** Scratchpad notice; argument: the reason. */
  SCRATCHPAD_FAILED,
  /** Scratchpad transform failure: odd hex digit count. */
  TRANSFORM_ODD_HEX,
  /** Scratchpad transform failure: the character position of a non-hex digit. */
  TRANSFORM_NOT_HEX,
  /** Scratchpad transform failure: the unbalanced character and its position. */
  TRANSFORM_UNBALANCED_AT,
  /** Scratchpad transform failure: unbalanced brackets at the end. */
  TRANSFORM_UNBALANCED,
  /** Scratchpad transform failure: a string without its closing quote. */
  TRANSFORM_UNTERMINATED_STRING,
  /** Scratchpad transform failure: the position of a tag without its closing bracket. */
  TRANSFORM_UNTERMINATED_TAG,
  /** Scratchpad notice; argument: the most chars a transform takes. */
  SCRATCHPAD_TOO_LONG,
  /** Scratchpad transform. */
  TRANSFORM_BASE64_ENCODE,
  /** Scratchpad transform. */
  TRANSFORM_BASE64_DECODE,
  /** Scratchpad transform. */
  TRANSFORM_HEX_ENCODE,
  /** Scratchpad transform. */
  TRANSFORM_HEX_DECODE,
  /** Scratchpad transform. */
  TRANSFORM_URL_DECODE,
  /** Scratchpad transform. */
  TRANSFORM_JSON_FORMAT,
  /** Scratchpad transform. */
  TRANSFORM_XML_FORMAT,
  /** Scratchpad derived value; arguments: hex and decimal. */
  VALUE_CRC32,
  /** Scratchpad derived value; arguments: decimal and hex. */
  VALUE_NUMBER,
  /** Scratchpad derived value; arguments: UTC and local time. */
  VALUE_EPOCH,
  /** Automatic Highlighters dialog title. */
  SELECTORS_TITLE,
  /** Automatic Highlighters column. */
  SELECTORS_COLUMN_KIND,
  /** Automatic Highlighters column. */
  SELECTORS_COLUMN_PATTERN,
  /** Automatic Highlighters column. */
  SELECTORS_COLUMN_SET,
  /** Automatic Highlighters choice. */
  SELECTORS_BY_NAME,
  /** Automatic Highlighters choice. */
  SELECTORS_BY_FIRST_LINE,
  /** Automatic Highlighters note. */
  SELECTORS_HINT,
  /** Automatic Highlighters notice. */
  SELECTORS_INVALID,
  /** Automatic Highlighters button. */
  SELECTORS_UPDATE,
  // Preferences dialog, sessions, recent files, favorites, encoding and zoom.
  /** Encoding menu. */
  GROUP_ENCODING,
  /** File menu cascade of the favorite files. */
  MENU_FAVORITES,
  /** File menu cascade listing the recent files. */
  MENU_RECENT_FILES,
  /** View submenu of the filtered view's modes and the split. */
  MENU_FILTERED_VIEW,
  /** View submenu of the light, dark and system themes. */
  MENU_THEME,
  /** Command label. */
  COMMAND_CLEAN_RECENT,
  /** Disabled Recent Files item while the list is empty. */
  RECENT_NONE,
  /** Command label. */
  COMMAND_PREFERENCES,
  /** Command label. */
  COMMAND_ZOOM_IN,
  /** Command label. */
  COMMAND_ZOOM_OUT,
  /** Command label. */
  COMMAND_ZOOM_RESET,
  /** Command label. */
  COMMAND_ENCODING_AUTO,
  /** Encoding menu cascade listing every other charset. */
  MENU_ENCODING_OTHER,
  /** Command label. */
  COMMAND_ADD_FAVORITE,
  /** Command label. */
  COMMAND_REMOVE_FAVORITE,
  /** Disabled Favorites item while the list is empty. */
  FAVORITES_NONE,
  /** Notice: the file name. */
  NOTICE_FAVORITE_ADDED,
  /** Notice: the file name. */
  NOTICE_FAVORITE_REMOVED,
  /** Notice: the limit. */
  NOTICE_FAVORITES_FULL,
  /** Notice: the count removed. */
  NOTICE_RECENT_CLEANED,
  /** Notice: the file name and the encoding. */
  NOTICE_ENCODING,
  /** Notice: the size in points. */
  NOTICE_FONT_SIZE,
  /** Encoding name shown for detection. */
  ENCODING_AUTO_NAME,
  /** Preferences dialog tab. */
  PREFERENCES_TAB_GENERAL,
  /** Preferences dialog tab. */
  PREFERENCES_TAB_VIEW,
  /** Preferences dialog tab. */
  PREFERENCES_TAB_FILE,
  /** Preferences dialog tab. */
  PREFERENCES_TAB_SHORTCUTS,
  /** Preferences dialog tab. */
  PREFERENCES_TAB_ADVANCED,
  /** Preferences check box. */
  PREFERENCES_LOAD_SESSION,
  /** Preferences field label. */
  PREFERENCES_RECENT_COUNT,
  /** Preferences field label. */
  PREFERENCES_HISTORY_SIZE,
  /** Preferences check box. */
  PREFERENCES_RUN_ON_ADD,
  /** Preferences check box. */
  PREFERENCES_QUICK_INCREMENTAL,
  /** Preferences check box. */
  PREFERENCES_QUICK_REGEX,
  /** Preferences check box. */
  PREFERENCES_FOLLOW_ON_LOAD,
  /** Preferences check box. */
  PREFERENCES_FOLLOW_ON_OVERSCROLL,
  /** Preferences field label. */
  PREFERENCES_FONT_FAMILY,
  /** Font choice for the platform's monospace font. */
  PREFERENCES_FONT_DEFAULT,
  /** Preferences field label. */
  PREFERENCES_FONT_SIZE,
  /** Preferences check box. */
  PREFERENCES_FONT_BOLD,
  /** Preferences check box. */
  PREFERENCES_FONT_ANTIALIAS,
  /** Preferences field label. */
  PREFERENCES_ENCODING,
  /** Preferences check box. */
  PREFERENCES_WATCH,
  /** Preferences field label. */
  PREFERENCES_POLL,
  /** Preferences field label. */
  PREFERENCES_MAX_RESULTS,
  // Keep file closed.
  /** Preferences check box. */
  PREFERENCES_KEEP_CLOSED,
  /** Preferences note under the keep file closed check box. */
  PREFERENCES_KEEP_CLOSED_NOTE,
  /** Notice: a file opened only for each read is gone; the argument is its name. */
  NOTICE_FILE_MISSING_CLOSED,
  /** Preferences note under fields read at startup. */
  PREFERENCES_NEXT_START,
  /** Shortcut scope. */
  SHORTCUTS_WINDOW,
  /** Shortcut scope. */
  SHORTCUTS_VIEW,
  /** Shortcut editor key field hint. */
  SHORTCUTS_CAPTURE,
  /** Shortcut editor button: the selected command's defaults. */
  SHORTCUTS_RESET,
  /** Shortcut editor button. */
  SHORTCUTS_RESET_ALL,
  /** Shortcut editor message: the keys and the command holding them. */
  SHORTCUTS_CONFLICT,
  /** Shortcut editor mark on an overridden command. */
  SHORTCUTS_OVERRIDDEN,
  // Multiple windows, logging and support items.
  /** Command label. */
  COMMAND_NEW_WINDOW,
  /** Command label. */
  COMMAND_SHOW_LOG,
  /** Command label. */
  COMMAND_DEBUG_LOG_FILE,
  /** Command label. */
  COMMAND_OPEN_LOG_FOLDER,
  /** Command label. */
  COMMAND_OPEN_SETTINGS_FOLDER,
  /** Command label. */
  COMMAND_REPORT_ISSUE,
  /** Show Log window title. */
  SHOW_LOG_TITLE,
  /** Show Log text with no line yet. */
  SHOW_LOG_EMPTY,
  /** Status bar warning marker. */
  STATUS_WARNINGS,
  /** Warning marker tooltip. */
  STATUS_WARNINGS_TIP,
  /** Notice: the debug log file opened; the file's folder. */
  NOTICE_LOG_FILE_ON,
  /** Notice: the debug log file closed. */
  NOTICE_LOG_FILE_OFF,
  /** Notice: the debug log file did not open; the reason. */
  NOTICE_LOG_FILE_FAILED,
  /** Notice: a folder could not be created; the folder and the reason. */
  NOTICE_FOLDER_FAILED,
  /** Notice: Report an Issue copied the runtime details. */
  NOTICE_ISSUE_DETAILS,
  // User guide and the Preferences Folding tab.
  /** User guide window title. */
  GUIDE_TITLE,
  /** Notice: the user guide could not be written to the cache; the reason. */
  NOTICE_GUIDE_FAILED,
  /** Notice: neither a browser widget nor an application showed the guide; the file. */
  NOTICE_GUIDE_AT,
  /** Preferences tab. */
  PREFERENCES_TAB_FOLDING,
  /** Preferences Folding label. */
  PREFERENCES_FOLD_START,
  /** Preferences Folding label. */
  PREFERENCES_FOLD_CONTINUATION,
  /** Preferences Folding note on the pattern syntax. */
  PREFERENCES_FOLD_NOTE,
  /** Button. */
  PREFERENCES_FOLD_DEFAULTS,
  /** Preferences Folding error: the pattern and the parser's description. */
  PREFERENCES_FOLD_INVALID,
  /** Preferences Folding error: no pattern at all. */
  PREFERENCES_FOLD_EMPTY,
  /** Preferences Folding error: the most continuation patterns kept. */
  PREFERENCES_FOLD_TOO_MANY,

  // Theme restart notice.
  /** Title of the notice that native controls change at the next start. */
  THEME_RESTART_TITLE,
  /** The notice that native controls change at the next start. */
  THEME_RESTART_MESSAGE,

  // Lines a search skips.
  /** A finished search's status and the count of lines too long to search. */
  SEARCH_SKIPPED_LINES,

  // Regex stack overflow in search and quick find.
  /** A regex overflowed the stack: the 1-based line. */
  SEARCH_PATTERN_TOO_COMPLEX,

  // Scratchpad transform on a worker.
  /** Scratchpad status while a transform runs on a worker. */
  SCRATCHPAD_RUNNING,

  // Tab move between windows.
  /** Command label. */
  COMMAND_MOVE_TO_NEW_WINDOW,
  /** Tab context menu item. */
  TAB_MOVE_TO_NEW_WINDOW,
  /** Notice: the target window shows the moved tab's file already; the tab title. */
  NOTICE_MOVE_SHOWN,

  // Tab data dot.
  /** Tab tooltip line for a tab whose document gained lines. */
  TAB_DATA_NEW_LINES,
  /** Tab tooltip line for a tab whose search gained matches. */
  TAB_DATA_NEW_MATCHES,
  // Context lines and range filter.
  /** Command label. */
  COMMAND_TOGGLE_RANGE,
  /** Command label. */
  COMMAND_SELECT_RANGE,
  /** Search row tooltip of the range toggle. */
  SEARCH_RANGE,
  /** Search row tooltip of the range end field. */
  SEARCH_RANGE_END_HINT,
  /** Search row tooltip of the context lines above each result. */
  SEARCH_CONTEXT_BEFORE,
  /** Search row tooltip of the context lines below each result. */
  SEARCH_CONTEXT_AFTER,
  /** Search failure in the range end: the 1-based column and the reason. */
  SEARCH_RANGE_END_ERROR,
  /** Notice: no range around the caret's line. */
  NOTICE_NO_RANGE,
  /** Notice: context lines stop at the memory limit. */
  FILTERED_CONTEXT_LIMIT,
  /** Results tab text of a range search: the start and end expressions. */
  RESULTS_RANGE,

  // Keyword triggers.
  /** Tab tooltip line for a tab whose follow a trigger turned off. */
  TAB_DATA_FOLLOW_STOPPED,
  /** Notice: a trigger turned follow off; the rule's pattern and the 1-based line. */
  NOTICE_TRIGGER_FOLLOW_STOPPED,
  /** Notice: trigger marks or comments were refused; the tab title and the comment cap. */
  NOTICE_TRIGGER_MARKS_REFUSED,
  /** Notice: quiet lines count toward the tab dot again at the memory limit. */
  NOTICE_TRIGGER_QUIET_LIMIT,
  /** Highlighter editor caption of the trigger actions. */
  RULE_TRIGGERS,
  /** Highlighter editor check box. */
  RULE_STOP_FOLLOW,
  /** Highlighter editor check box before the comment template field. */
  RULE_SET_MARK,
  /** Tooltip of the comment template field, listing the placeholders. */
  RULE_COMMENT_TEMPLATE_HINT,
  /** Highlighter editor check box. */
  RULE_QUIET,
  /** Editor status: the comment template's regex does not compile; the reason. */
  RULE_TEMPLATE_INVALID,

  // Timestamps.
  /** Command label. */
  COMMAND_GO_TO_TIME,
  /** Command label. */
  COMMAND_TIMESTAMP_FORMATS,
  /** Command label. */
  COMMAND_TIME_ZONE,
  /** Command label. */
  COMMAND_TIME_OFFSET,
  /** View cascade of the timestamp commands. */
  MENU_TIMESTAMPS,
  /** Timestamps cascade listing the formats. */
  MENU_TIME_FORMAT,
  // Time spread.
  /** Timestamps cascade of the time-spread modes. */
  MENU_TIME_SPREAD,
  /** Command label. */
  COMMAND_TIME_SPREAD_OFF,
  /** Command label. */
  COMMAND_TIME_SPREAD_ACTIVITY,
  /** Command label. */
  COMMAND_TIME_SPREAD_PAUSES,
  /** Format list entry: no timestamps. */
  TIME_FORMAT_NONE,
  /** Built-in format name. */
  TIME_FORMAT_ISO,
  /** Built-in format name. */
  TIME_FORMAT_YMD_SLASH,
  /** Built-in format name. */
  TIME_FORMAT_YMD_DOT,
  /** Built-in format name. */
  TIME_FORMAT_DMY_DOT,
  /** Built-in format name. */
  TIME_FORMAT_DMY_SLASH,
  /** Built-in format name. */
  TIME_FORMAT_MDY_SLASH,
  /** Built-in format name. */
  TIME_FORMAT_DMONY,
  /** Built-in format name. */
  TIME_FORMAT_SYSLOG,
  /** Built-in format name. */
  TIME_FORMAT_CLF,
  /** Built-in format name. */
  TIME_FORMAT_JUL,
  /** Built-in format name. */
  TIME_FORMAT_EPOCH,
  /** Time field tooltip: the format, the zone and the offset. */
  TIME_FIELD_TIP,
  /** Go to Time prompt: the display zone. */
  GO_TO_TIME_PROMPT,
  /** Time Offset dialog: the offset field. */
  TIME_OFFSET_PROMPT,
  /** Time Offset dialog: the caret line's time field; the display zone. */
  TIME_OFFSET_CARET_PROMPT,
  /** Time Offset dialog: the caret line has no time. */
  TIME_OFFSET_NO_CARET,
  /** Time Zone dialog prompt. */
  TIME_ZONE_PROMPT,
  /** Time Zone dialog entry for the default zone; the zone. */
  TIME_ZONE_DEFAULT,
  /** Timestamp format editor title. */
  TIME_FORMATS_TITLE,
  /** Timestamp format editor field. */
  TIME_FORMAT_NAME,
  /** Timestamp format editor field. */
  TIME_FORMAT_LOCATE,
  /** Timestamp format editor field. */
  TIME_FORMAT_PATTERN,
  /** Timestamp format editor: the caret line's time. */
  TIME_FORMAT_PREVIEW,
  /** Timestamp format editor: the caret line has no timestamp in this format. */
  TIME_FORMAT_PREVIEW_NONE,
  /** Name of a new user format; its number. */
  TIME_FORMAT_NEW_NAME,
  /** Timestamp format editor status: two formats share a name. */
  TIME_FORMAT_DUPLICATE,
  /** Timestamp format editor status: the format's name and why it does not compile. */
  TIME_FORMAT_INVALID,
  /** Notice: the file has no timestamp format. */
  NOTICE_TIME_NO_FORMAT,
  /** Notice: no line has a timestamp. */
  NOTICE_TIME_NONE,
  /** Notice: the target is before the first timestamp. */
  NOTICE_TIME_BEFORE_FIRST,
  /** Notice: the target is after the last timestamp while indexing runs. */
  NOTICE_TIME_INDEXED_PART,
  /** Notice: the order check found timestamps out of order. */
  NOTICE_TIME_OUT_OF_ORDER,
  /** Notice: progress of a go to time over every line. */
  NOTICE_TIME_PROGRESS,
  /** Notice: Esc canceled a go to time. */
  NOTICE_TIME_CANCELED,
  /** Notice: a go to time failed; the reason. */
  NOTICE_TIME_FAILED,
  // Time sync.
  /** Command label. */
  COMMAND_SCROLL_TABS_TO_TIME,
  /** Cascade label in the tab and log-view context menus. */
  MENU_TIME_SYNC,
  /** Time Sync cascade: a tab of another group; its title and group number. */
  TIME_SYNC_OTHER_GROUP,
  /** Time Sync cascade: leaves the group. */
  TIME_SYNC_LEAVE,
  /** Time Sync cascade: no other tab has a format. */
  TIME_SYNC_NO_TABS,
  /** Tab tooltip line; the group number. */
  TAB_TIME_SYNCED,
  /** Notice: the caret line has no time to scroll the tabs to. */
  NOTICE_TIME_NO_CARET,
  // Copy and save.
  /** The reason of a failed copy or save: the file shrank below its index. */
  EXPORT_FILE_SHORTER,
  /** Notice: a save finished; the lines, the file name and the lines cut at the read limit. */
  NOTICE_SAVED_CUT,
  // Session mark comments.
  /** Notice after a restore: comments the last save left out; their count and the cap. */
  NOTICE_COMMENTS_DROPPED,
  // Reopen with auto-refresh.
  /** Notice: a file changed on disk and its auto-refresh search runs again; the tab title. */
  NOTICE_FILE_SEARCH_RERUN,
  // Failed reopen retries.
  /** Status field: a failed reopen waits for the next check of the file. */
  STATUS_REOPEN_WAITING,
  // Navigation history, Recently Closed, Open All and the command palette.
  /** Command label. */
  COMMAND_GO_BACK,
  /** Command label. */
  COMMAND_GO_FORWARD,
  /** Log view key description. */
  COMMAND_GO_TO_RESULT,
  /** Command label. */
  COMMAND_OPEN_ALL_RECENT,
  /** File menu cascade listing the recently closed files. */
  MENU_RECENTLY_CLOSED,
  /** Command label. */
  COMMAND_OPEN_ALL_CLOSED,
  /** Command label. */
  COMMAND_CLEAR_CLOSED,
  /** Disabled Recently Closed item while the list is empty. */
  CLOSED_NONE,
  /** Notice after Open All: the count and names of the files skipped. */
  NOTICE_OPEN_ALL_SKIPPED,
  /** Command label. */
  COMMAND_COMMAND_PALETTE,
  /** Command palette title. */
  PALETTE_TITLE,
  /** Command palette filter field hint. */
  PALETTE_FILTER,
  /** Command palette column: the menu a command sits in. */
  PALETTE_MENU,

  // View colors.
  /** Preferences label: the log views' color scheme. */
  PREFERENCES_VIEW_COLORS,
  /** View colors choice: the theme's palette. */
  VIEW_COLORS_THEME,
  /** Bundled color scheme. */
  VIEW_COLORS_SANDSTONE,
  /** Bundled color scheme. */
  VIEW_COLORS_NIGHTWATER,
  /** Bundled color scheme. */
  VIEW_COLORS_GARDEN_LIGHT,
  /** Bundled color scheme. */
  VIEW_COLORS_GARDEN_DARK,
  /** Bundled color scheme. */
  VIEW_COLORS_LEGAL_PAD,
  /** Bundled color scheme. */
  VIEW_COLORS_PAPER,
  /** Bundled color scheme. */
  VIEW_COLORS_MIDNIGHT,
  /** Bundled color scheme. */
  VIEW_COLORS_PHOSPHOR,
  /** Bundled color scheme. */
  VIEW_COLORS_GRAPHITE,
  /** Preferences note: where user schemes are read. */
  PREFERENCES_VIEW_COLORS_NOTE,

  // Dropped folders.
  /** Notice: a drop held folders, which are not opened. */
  NOTICE_FOLDERS_NOT_OPENED,

  // Light and Dark view colors.
  /** View colors choice: the light palette whatever the theme. */
  VIEW_COLORS_LIGHT,
  /** View colors choice: the dark palette whatever the theme. */
  VIEW_COLORS_DARK,

  // Reopen Closed Tab.
  /** Command label. */
  COMMAND_REOPEN_CLOSED,

  // Always on Top.
  /** Command label. */
  COMMAND_ALWAYS_ON_TOP,
  /** Tool tip of the disabled Always on Top item where the window manager's menu offers it. */
  ALWAYS_ON_TOP_UNSUPPORTED,

  // Tab strip buttons and the last tab.
  /** Tab list button tool tip and accessible name. */
  TAB_LIST,
  /** Tab list button tool tip with the hidden tab count. */
  TAB_LIST_HIDDEN,
  /** Preferences check box: closing the last tab closes the window. */
  PREFERENCES_CLOSE_WINDOW_WITH_LAST_TAB,

  // Menu structure.
  /** Go menu. */
  GROUP_GO,
  /** Window menu. */
  GROUP_WINDOW,
  /** Search menu cascade of the search toggles. */
  MENU_SEARCH_OPTIONS,
  /** Search menu and log view cascade of the search limits. */
  MENU_SEARCH_LIMITS,
  /** View menu cascade of the fold commands. */
  MENU_FOLDING,
  /** File menu cascade closing the tabs around the active one. */
  MENU_CLOSE_MULTIPLE,

  // Clear Results.
  /** Search menu: empty the shown tab's results. */
  COMMAND_CLEAR_RESULTS,

  // Accessible names.
  /** Accessible name of the main log view. */
  ACCESSIBLE_LOG_VIEW,
  /** Accessible name of the filtered view with no search. */
  ACCESSIBLE_FILTERED_VIEW,
  /** Accessible name of the filtered view with the shown set's match count. */
  ACCESSIBLE_FILTERED_VIEW_COUNT,
  /** Accessible name of the overview bar. */
  ACCESSIBLE_OVERVIEW,
  // Clear Recent Files.
  /** Command label. */
  COMMAND_CLEAR_RECENT,
  // Decode-only encoding choice.
  /** Notice: the encoding name. */
  NOTICE_ENCODING_UNSUPPORTED,
  // Access denied in Search in Folder.
  /** File status: permissions refuse listing the folder or reading the file. */
  FOLDER_STATUS_ACCESS_DENIED,
  // Last used tab.
  /** Preferences box making a close of the shown tab show the most recently used tab. */
  PREFERENCES_CLOSE_SHOWS_LAST_USED;

  private static final String BUNDLE = "io.github.ajdailey.qlogg.app.i18n.messages";

  /** Loads the bundle on first use, after every constant exists. */
  private static final class Holder {
    static final ResourceBundle MESSAGES =
        ResourceBundle.getBundle(BUNDLE, Locale.getDefault(), UiText.class.getClassLoader());
  }

  /**
   * Returns the text.
   *
   * @return the bundle value
   */
  public String text() {
    return Holder.MESSAGES.getString(name());
  }

  /**
   * Formats the text as a pattern.
   *
   * @param args the pattern arguments
   * @return the formatted text
   */
  public String format(Object... args) {
    return String.format(Locale.ROOT, text(), args);
  }

  /**
   * Returns the bundle the constants read, for tests.
   *
   * @return the bundle
   */
  static ResourceBundle bundle() {
    return Holder.MESSAGES;
  }
}
