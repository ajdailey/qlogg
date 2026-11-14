package io.github.ajdailey.qlogg.app.command;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The commands each menu-bar menu lists, in {@link CommandId} declaration order, and the layout of
 * the log view, tab, path field and search field context menus. Where the system supplies an
 * application menu (macOS), About, Preferences and Exit sit there as About, Preferences and Quit
 * and are left out of Help, Tools and File.
 */
public final class MenuTable {
  /** Commands the macOS application menu runs instead of the menu bar. */
  public static final Set<CommandId> APP_MENU_COMMANDS =
      EnumSet.of(CommandId.ABOUT, CommandId.PREFERENCES, CommandId.QUIT);

  private MenuTable() {}

  /**
   * Returns the commands a menu lists.
   *
   * @param group a group whose {@link CommandGroup#menu()} is true
   * @param appMenu true when the system's application menu holds About, Preferences and Quit
   * @return the commands in menu order
   */
  public static List<CommandId> commands(CommandGroup group, boolean appMenu) {
    List<CommandId> commands = new ArrayList<CommandId>();
    for (CommandId command : CommandId.values()) {
      if (command.group() == group && !(appMenu && APP_MENU_COMMANDS.contains(command))) {
        commands.add(command);
      }
    }
    return commands;
  }

  /**
   * Returns the listed cascade just before a command: View > Timestamps > Format, filled from the
   * timestamp formats.
   *
   * @param command a command
   * @return the cascade's label, or null
   */
  public static UiText cascadeBefore(CommandId command) {
    return command == CommandId.TIMESTAMP_FORMATS ? UiText.MENU_TIME_FORMAT : null;
  }

  /**
   * Returns the listed cascade just after a command: Window > Opened Files, filled from the open
   * documents, or Highlighters > Color Label, filled from the color labels.
   *
   * @param command a command
   * @return the cascade's label, or null
   */
  public static UiText cascadeAfter(CommandId command) {
    return switch (command) {
      case SWITCH_FILE -> UiText.MENU_OPENED_FILES;
      case CLEAR_COLOR_LABELS -> UiText.MENU_COLOR_LABEL;
      default -> null;
    };
  }

  // Time spread.

  /**
   * Returns the cascade a command's item sits in inside its {@link CommandId#submenu()}: View >
   * Timestamps > Time Spread.
   *
   * @param command a command
   * @return the cascade's label, or null when the item sits in the submenu itself
   */
  public static UiText nestedCascade(CommandId command) {
    return switch (command) {
      case TIME_SPREAD_OFF, TIME_SPREAD_ACTIVITY, TIME_SPREAD_PAUSES -> UiText.MENU_TIME_SPREAD;
      default -> null;
    };
  }

  // Radio items.

  /**
   * Commands shown as radio items, each a {@link CommandId#check()} command: View > Theme, View >
   * Filtered View's modes, Encoding > Auto and Time Spread.
   */
  public static final Set<CommandId> RADIO =
      EnumSet.of(
          CommandId.THEME_LIGHT,
          CommandId.THEME_DARK,
          CommandId.THEME_SYSTEM,
          CommandId.SHOW_MARKS_AND_MATCHES,
          CommandId.SHOW_MARKS,
          CommandId.SHOW_MATCHES,
          CommandId.ENCODING_AUTO,
          CommandId.TIME_SPREAD_OFF,
          CommandId.TIME_SPREAD_ACTIVITY,
          CommandId.TIME_SPREAD_PAUSES);

  /**
   * Reports whether a command's item is a radio item.
   *
   * @param command a command
   * @return true for one of a group where exactly one is chosen
   */
  public static boolean radio(CommandId command) {
    return RADIO.contains(command);
  }

  // Context menus.

  /**
   * The log view's context menu commands in order; null is a separator. Consecutive commands with a
   * {@link #logViewCascade} form one cascade. {@link #LOG_VIEW_CASCADES} follow the last command.
   */
  public static final List<CommandId> LOG_VIEW_MENU =
      Collections.unmodifiableList(
          Arrays.asList(
              CommandId.COPY,
              CommandId.COPY_LINE,
              CommandId.COPY_WITH_LINE_NUMBERS,
              null,
              CommandId.TOGGLE_MARK,
              CommandId.EDIT_MARK_COMMENT,
              null,
              CommandId.FIND_SELECTION_FORWARD,
              CommandId.FIND_SELECTION_BACKWARD,
              null,
              CommandId.ADD_TO_SEARCH,
              CommandId.EXCLUDE_FROM_SEARCH,
              CommandId.REPLACE_SEARCH,
              CommandId.SET_SEARCH_START,
              CommandId.SET_SEARCH_END,
              CommandId.CLEAR_SEARCH_LIMITS,
              null,
              CommandId.SET_SELECTION_START,
              CommandId.SET_SELECTION_END,
              null,
              CommandId.SAVE_SELECTION,
              CommandId.SEND_TO_SCRATCHPAD,
              CommandId.REPLACE_SCRATCHPAD,
              null,
              CommandId.TOGGLE_FOLD,
              CommandId.WRAP_LINE,
              CommandId.SCROLL_TABS_TO_TIME));

  /**
   * The cascades the log view's context menu ends with, filled when shown; null is a separator.
   * Time Sync holds a check item per other tab and Leave Time Sync, Highlighters None and the sets,
   * Color Label None and the labels.
   */
  public static final List<UiText> LOG_VIEW_CASCADES =
      Collections.unmodifiableList(
          Arrays.asList(
              UiText.MENU_TIME_SYNC, null, UiText.GROUP_HIGHLIGHT, UiText.MENU_COLOR_LABEL));

  /**
   * The tab's context menu in order; null is a separator. {@link UiText#MENU_TIME_SYNC} is the Time
   * Sync cascade.
   */
  public static final List<UiText> TAB_MENU =
      Collections.unmodifiableList(
          Arrays.asList(
              UiText.TAB_CLOSE,
              UiText.TAB_CLOSE_OTHERS,
              UiText.TAB_CLOSE_LEFT,
              UiText.TAB_CLOSE_RIGHT,
              UiText.TAB_CLOSE_ALL,
              null,
              UiText.TAB_COPY_PATH,
              UiText.TAB_COPY_FILE_NAME,
              UiText.TAB_OPEN_FOLDER,
              null,
              UiText.TAB_RENAME,
              UiText.TAB_RESET_NAME,
              null,
              UiText.MENU_TIME_SYNC,
              UiText.TAB_MOVE_TO_NEW_WINDOW));

  /**
   * The path field's context menu in order; null is a separator. The first three run {@link
   * CommandId#COPY_PATH}, {@link CommandId#COPY_FILE_NAME} and {@link CommandId#OPEN_FOLDER}; Copy
   * and Select All act on the field's text.
   */
  public static final List<UiText> PATH_MENU =
      Collections.unmodifiableList(
          Arrays.asList(
              UiText.TAB_COPY_PATH,
              UiText.TAB_COPY_FILE_NAME,
              UiText.TAB_OPEN_FOLDER,
              null,
              UiText.PATH_COPY,
              null,
              UiText.PATH_SELECT_ALL));

  /** The search field's context menu in order. */
  public static final List<CommandId> SEARCH_FIELD_MENU =
      List.of(CommandId.SAVE_AS_FILTER, CommandId.EDIT_HISTORY, CommandId.CLEAR_HISTORY);

  /**
   * Returns the cascade a log view context menu command sits in.
   *
   * @param command a command of {@link #LOG_VIEW_MENU}
   * @return Search Limits for the search limit commands, or null
   */
  public static UiText logViewCascade(CommandId command) {
    return switch (command) {
      case SET_SEARCH_START, SET_SEARCH_END, CLEAR_SEARCH_LIMITS -> UiText.MENU_SEARCH_LIMITS;
      default -> null;
    };
  }
}
