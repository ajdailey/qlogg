package io.github.ajdailey.qlogg.app.command;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The commands each menu-bar menu lists, in {@link CommandId} declaration order. Where the system
 * supplies an application menu (macOS), About, Options and Exit sit there as About, Preferences and
 * Quit and are left out of Help, Tools and File.
 */
public final class MenuTable {
  /** Commands the macOS application menu runs instead of the menu bar. */
  public static final Set<CommandId> APP_MENU_COMMANDS =
      EnumSet.of(CommandId.ABOUT, CommandId.OPTIONS, CommandId.QUIT);

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
    return radio(command) ? UiText.MENU_TIME_SPREAD : null;
  }

  /**
   * Reports whether a command's item is a radio item.
   *
   * @param command a command
   * @return true for one of a group where exactly one is chosen
   */
  public static boolean radio(CommandId command) {
    return switch (command) {
      case TIME_SPREAD_OFF, TIME_SPREAD_ACTIVITY, TIME_SPREAD_PAUSES -> true;
      default -> false;
    };
  }
}
