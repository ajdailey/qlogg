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
  /** Status field: the modification time. */
  STATUS_MODIFIED,
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
  /** Edit menu cascade holding the search commands. */
  MENU_SEARCH,
  /** Command label. */
  COMMAND_COPY_FILE_NAME,
  /** Command label. */
  COMMAND_DOCUMENTATION,
  /** Notice: Help > Documentation in a build without the user guide. */
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
  // Options dialog, sessions, recent files, favorites, encoding and zoom.
  /** Encoding menu. */
  GROUP_ENCODING,
  /** Favorites menu. */
  GROUP_FAVORITES,
  /** File menu cascade listing the recent files. */
  MENU_OPEN_RECENT,
  /** View submenu of the filtered-view visibility modes. */
  MENU_VISIBILITY,
  /** View submenu of the light, dark and system themes. */
  MENU_THEME,
  /** Command label. */
  COMMAND_CLEAN_RECENT,
  /** Disabled Open Recent item while the list is empty. */
  RECENT_NONE,
  /** Command label. */
  COMMAND_OPTIONS,
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
  /** Options dialog tab. */
  OPTIONS_TAB_GENERAL,
  /** Options dialog tab. */
  OPTIONS_TAB_VIEW,
  /** Options dialog tab. */
  OPTIONS_TAB_FILE,
  /** Options dialog tab. */
  OPTIONS_TAB_SHORTCUTS,
  /** Options dialog tab. */
  OPTIONS_TAB_ADVANCED,
  /** Options check box. */
  OPTIONS_LOAD_SESSION,
  /** Options field label. */
  OPTIONS_RECENT_COUNT,
  /** Options field label. */
  OPTIONS_HISTORY_SIZE,
  /** Options check box. */
  OPTIONS_RUN_ON_ADD,
  /** Options check box. */
  OPTIONS_QUICK_INCREMENTAL,
  /** Options check box. */
  OPTIONS_QUICK_REGEX,
  /** Options check box. */
  OPTIONS_FOLLOW_ON_LOAD,
  /** Options check box. */
  OPTIONS_FOLLOW_ON_OVERSCROLL,
  /** Options field label. */
  OPTIONS_FONT_FAMILY,
  /** Font choice for the platform's monospace font. */
  OPTIONS_FONT_DEFAULT,
  /** Options field label. */
  OPTIONS_FONT_SIZE,
  /** Options check box. */
  OPTIONS_FONT_BOLD,
  /** Options check box. */
  OPTIONS_FONT_ANTIALIAS,
  /** Options field label. */
  OPTIONS_ENCODING,
  /** Options check box. */
  OPTIONS_WATCH,
  /** Options field label. */
  OPTIONS_POLL,
  /** Options field label. */
  OPTIONS_MAX_RESULTS,
  /** Options note under fields read at startup. */
  OPTIONS_NEXT_START,
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
  // User guide and the Options Folding tab.
  /** User guide window title. */
  GUIDE_TITLE,
  /** Notice: the user guide could not be written to the cache; the reason. */
  NOTICE_GUIDE_FAILED,
  /** Notice: neither a browser widget nor an application showed the guide; the file. */
  NOTICE_GUIDE_AT,
  /** Options tab. */
  OPTIONS_TAB_FOLDING,
  /** Options Folding label. */
  OPTIONS_FOLD_START,
  /** Options Folding label. */
  OPTIONS_FOLD_CONTINUATION,
  /** Options Folding note on the pattern syntax. */
  OPTIONS_FOLD_NOTE,
  /** Button. */
  OPTIONS_FOLD_DEFAULTS,
  /** Options Folding error: the pattern and the parser's description. */
  OPTIONS_FOLD_INVALID,
  /** Options Folding error: no pattern at all. */
  OPTIONS_FOLD_EMPTY,
  /** Options Folding error: the most continuation patterns kept. */
  OPTIONS_FOLD_TOO_MANY,

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
  RESULTS_RANGE;

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
