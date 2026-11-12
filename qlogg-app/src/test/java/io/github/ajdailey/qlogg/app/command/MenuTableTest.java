package io.github.ajdailey.qlogg.app.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Menu contents with and without the macOS application menu, and mnemonics per menu. */
class MenuTableTest {
  @Test
  void theApplicationMenuTakesAboutOptionsAndExit() {
    assertTrue(MenuTable.commands(CommandGroup.TOOLS, false).contains(CommandId.OPTIONS));
    assertTrue(MenuTable.commands(CommandGroup.HELP, false).contains(CommandId.ABOUT));
    assertTrue(MenuTable.commands(CommandGroup.FILE, false).contains(CommandId.QUIT));
    assertFalse(MenuTable.commands(CommandGroup.TOOLS, true).contains(CommandId.OPTIONS));
    assertFalse(MenuTable.commands(CommandGroup.HELP, true).contains(CommandId.ABOUT));
    assertFalse(MenuTable.commands(CommandGroup.FILE, true).contains(CommandId.QUIT));
    List<CommandId> tools = MenuTable.commands(CommandGroup.TOOLS, true);
    assertEquals(CommandId.SCRATCHPAD, tools.get(tools.size() - 1));
    assertEquals(
        MenuTable.commands(CommandGroup.VIEW, false), MenuTable.commands(CommandGroup.VIEW, true));
  }

  @Test
  void listedCascadesSitBesideTheirCommands() {
    assertEquals(UiText.MENU_TIME_FORMAT, MenuTable.cascadeBefore(CommandId.TIMESTAMP_FORMATS));
    assertEquals(UiText.MENU_OPENED_FILES, MenuTable.cascadeAfter(CommandId.SWITCH_FILE));
    assertEquals(UiText.MENU_COLOR_LABEL, MenuTable.cascadeAfter(CommandId.CLEAR_COLOR_LABELS));
    assertNull(MenuTable.cascadeBefore(CommandId.OPEN));
    assertNull(MenuTable.cascadeAfter(CommandId.OPEN));
  }

  @Test
  void timeSpreadIsARadioCascadeInsideTimestamps() {
    CommandId[] modes = {
      CommandId.TIME_SPREAD_OFF, CommandId.TIME_SPREAD_ACTIVITY, CommandId.TIME_SPREAD_PAUSES
    };
    for (CommandId mode : modes) {
      assertTrue(MenuTable.radio(mode));
      assertEquals(UiText.MENU_TIME_SPREAD, MenuTable.nestedCascade(mode));
      assertEquals(UiText.MENU_TIMESTAMPS.text(), mode.submenu());
    }
    assertFalse(MenuTable.radio(CommandId.THEME_LIGHT));
    assertNull(MenuTable.nestedCascade(CommandId.TIME_OFFSET));
  }

  @Test
  void mnemonicsAreUniqueInEveryMenu() {
    Map<String, List<String>> menus = new LinkedHashMap<String, List<String>>();
    List<String> bar = new ArrayList<String>();
    menus.put("menu bar", bar);
    for (CommandGroup group : CommandGroup.values()) {
      if (group.menu()) {
        bar.add(group.label());
        menus.putAll(menus(group));
      }
    }
    List<String> repeated = new ArrayList<String>();
    for (Map.Entry<String, List<String>> menu : menus.entrySet()) {
      List<Character> seen = new ArrayList<Character>();
      for (String label : menu.getValue()) {
        int at = label.indexOf('&');
        if (at < 0) {
          continue;
        }
        char key = label.toLowerCase(Locale.ROOT).charAt(at + 1);
        if (seen.contains(key)) {
          repeated.add(menu.getKey() + ": '" + key + "' again in " + label);
        }
        seen.add(key);
      }
    }
    assertEquals(List.of(), repeated);
  }

  /** Labels per menu: the group's own menu, then each cascade in it. */
  private static Map<String, List<String>> menus(CommandGroup group) {
    Map<String, List<String>> menus = new LinkedHashMap<String, List<String>>();
    List<String> top = new ArrayList<String>();
    menus.put(group.label(), top);
    for (CommandId command : MenuTable.commands(group, false)) {
      String submenu = command.submenu();
      List<String> target = top;
      if (submenu != null) {
        if (!menus.containsKey(submenu)) {
          top.add(submenu);
          menus.put(submenu, new ArrayList<String>());
        }
        target = menus.get(submenu);
      }
      UiText before = MenuTable.cascadeBefore(command);
      if (before != null) {
        target.add(before.text());
      }
      UiText nested = MenuTable.nestedCascade(command);
      if (nested != null) {
        if (!menus.containsKey(nested.text())) {
          target.add(nested.text());
          menus.put(nested.text(), new ArrayList<String>());
        }
        target = menus.get(nested.text());
      }
      target.add(command.label());
      UiText after = MenuTable.cascadeAfter(command);
      if (after != null) {
        target.add(after.text());
      }
    }
    return menus;
  }
}
