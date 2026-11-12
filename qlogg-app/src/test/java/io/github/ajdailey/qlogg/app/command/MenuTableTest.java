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

/**
 * Menu contents with and without the macOS application menu, radio items, and mnemonics per menu:
 * every menu-bar title, item and cascade, and every fixed item of the log view, tab, path field and
 * search field context menus, has one, unique in its menu. The built menus are walked in {@code
 * LogViewTest}.
 */
class MenuTableTest {
  @Test
  void theApplicationMenuTakesAboutPreferencesAndExit() {
    assertTrue(MenuTable.commands(CommandGroup.TOOLS, false).contains(CommandId.PREFERENCES));
    assertEquals("Pr&eferences...", CommandId.PREFERENCES.label());
    assertTrue(MenuTable.commands(CommandGroup.HELP, false).contains(CommandId.ABOUT));
    assertTrue(MenuTable.commands(CommandGroup.FILE, false).contains(CommandId.QUIT));
    assertFalse(MenuTable.commands(CommandGroup.TOOLS, true).contains(CommandId.PREFERENCES));
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
    assertNull(MenuTable.nestedCascade(CommandId.TIME_OFFSET));
    assertNull(MenuTable.nestedCascade(CommandId.THEME_LIGHT));
  }

  @Test
  void radioItemsAreCheckCommandsInACascadeOrEncoding() {
    for (CommandId command : MenuTable.RADIO) {
      assertTrue(command.check(), command.name());
      assertTrue(
          command.submenu() != null || command.group() == CommandGroup.ENCODING, command.name());
    }
    assertTrue(MenuTable.radio(CommandId.THEME_DARK));
    assertTrue(MenuTable.radio(CommandId.SHOW_MATCHES));
    assertTrue(MenuTable.radio(CommandId.ENCODING_AUTO));
    assertFalse(MenuTable.radio(CommandId.FOLLOW));
  }

  @Test
  void contextMenusListEachCommandOnce() {
    List<CommandId> seen = new ArrayList<CommandId>();
    for (CommandId command : MenuTable.LOG_VIEW_MENU) {
      if (command != null) {
        assertFalse(seen.contains(command), command.name());
        seen.add(command);
      }
    }
    assertEquals(UiText.MENU_SEARCH_LIMITS, MenuTable.logViewCascade(CommandId.SET_SEARCH_END));
    assertNull(MenuTable.logViewCascade(CommandId.COPY));
    assertTrue(MenuTable.TAB_MENU.contains(UiText.MENU_TIME_SYNC));
  }

  @Test
  void everyMenuItemHasAMnemonic() {
    List<String> missing = new ArrayList<String>();
    for (Map.Entry<String, List<String>> menu : menus().entrySet()) {
      for (String label : menu.getValue()) {
        if (label.indexOf('&') < 0) {
          missing.add(menu.getKey() + " > " + label);
        }
      }
    }
    assertEquals(List.of(), missing);
  }

  @Test
  void mnemonicsAreUniqueInEveryMenu() {
    List<String> repeated = new ArrayList<String>();
    for (Map.Entry<String, List<String>> menu : menus().entrySet()) {
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

  /** Labels per menu: the menu bar's titles, then each group's menu and its cascades. */
  private static Map<String, List<String>> menus() {
    Map<String, List<String>> menus = new LinkedHashMap<String, List<String>>();
    List<String> bar = new ArrayList<String>();
    menus.put("menu bar", bar);
    for (CommandGroup group : CommandGroup.values()) {
      if (group.menu()) {
        bar.add(group.label());
        menus.putAll(menus(group));
      }
    }
    menus.get(CommandGroup.ENCODING.label()).add(UiText.MENU_ENCODING_OTHER.text());
    menus.putAll(contextMenus());
    return menus;
  }

  /**
   * Labels per context menu: the log view menu with its cascades' fixed items, the tab menu, the
   * path field menu and the search field menu.
   */
  private static Map<String, List<String>> contextMenus() {
    Map<String, List<String>> menus = new LinkedHashMap<String, List<String>>();
    List<String> log = new ArrayList<String>();
    menus.put("log view", log);
    for (CommandId command : MenuTable.LOG_VIEW_MENU) {
      if (command == null) {
        continue;
      }
      UiText cascade = MenuTable.logViewCascade(command);
      if (cascade == null) {
        log.add(command.label());
        continue;
      }
      String key = "log view > " + cascade.text();
      if (!menus.containsKey(key)) {
        log.add(cascade.text());
        menus.put(key, new ArrayList<String>());
      }
      menus.get(key).add(command.label());
    }
    for (UiText cascade : MenuTable.LOG_VIEW_CASCADES) {
      if (cascade != null) {
        log.add(cascade.text());
      }
    }
    menus.put("log view > Time Sync", List.of(UiText.TIME_SYNC_LEAVE.text()));
    menus.put("log view > Highlighters", List.of(CommandId.NO_HIGHLIGHTERS.label()));
    menus.put("log view > Color Label", List.of(UiText.MENU_NONE.text()));
    List<String> tab = new ArrayList<String>();
    for (UiText label : MenuTable.TAB_MENU) {
      if (label != null) {
        tab.add(label.text());
      }
    }
    menus.put("tab", tab);
    menus.put("tab > Time Sync", List.of(UiText.TIME_SYNC_LEAVE.text()));
    List<String> path = new ArrayList<String>();
    for (UiText label : MenuTable.PATH_MENU) {
      if (label != null) {
        path.add(label.text());
      }
    }
    menus.put("path field", path);
    List<String> field = new ArrayList<String>();
    for (CommandId command : MenuTable.SEARCH_FIELD_MENU) {
      field.add(command.label());
    }
    menus.put("search field", field);
    return menus;
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
