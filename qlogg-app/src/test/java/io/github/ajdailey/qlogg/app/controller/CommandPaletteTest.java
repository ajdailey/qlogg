package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandGroup;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.MenuTable;
import io.github.ajdailey.qlogg.app.config.Preferences;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandPaletteTest {
  @TempDir Path temp;

  private ControllerFixture fixture;
  private CommandPalette palette;

  @BeforeEach
  void open() {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    palette = fixture.application.commandPalette();
  }

  @AfterEach
  void close() throws InterruptedException {
    fixture.close();
  }

  private static CommandPalette.Entry find(List<CommandPalette.Entry> entries, CommandId command) {
    for (CommandPalette.Entry entry : entries) {
      if (entry.command() == command) {
        return entry;
      }
    }
    return null;
  }

  @Test
  void aBlankQueryListsEveryMenuCommandInMenuOrder() {
    List<CommandPalette.Entry> entries = palette.filter(" ");
    int count = 0;
    for (CommandGroup group : CommandGroup.values()) {
      if (group.menu()) {
        count += MenuTable.commands(group, false).size();
      }
    }
    assertEquals(count - 1, entries.size());
    assertEquals(CommandId.values()[0], entries.get(0).command());
    assertEquals(null, find(entries, CommandId.COMMAND_PALETTE));
    assertEquals(null, find(entries, CommandId.LINE_UP));
    assertEquals("Search > Search Options", find(entries, CommandId.TOGGLE_CASE).menu());
    assertEquals(
        "View > Timestamps > Time Spread", find(entries, CommandId.TIME_SPREAD_PAUSES).menu());
  }

  @Test
  void theQueryMatchesTitleCharsInOrderAndRanksWordStartsFirst() {
    List<CommandPalette.Entry> entries = palette.filter("gtl");
    assertEquals(CommandId.GO_TO_LINE, entries.get(0).command());
    for (CommandPalette.Entry entry : entries) {
      assertTrue(
          CommandPalette.score("gtl", entry.title()) >= 0
              || CommandPalette.score("gtl", entry.menu() + " " + entry.title()) >= 0);
    }
    assertEquals(CommandId.TOGGLE_CASE, palette.filter("search match case").get(0).command());
    assertTrue(palette.filter("zzzz").isEmpty());
    assertEquals(-1, CommandPalette.score("ba", "ab"));
  }

  @Test
  void aChosenCommandRunsAndADisabledOneDoesNot() {
    CommandPalette.Entry add = find(palette.filter("favorite"), CommandId.ADD_FAVORITE);
    assertFalse(add.enabled());
    int before = fixture.view.notices.size();
    palette.run(CommandId.ADD_FAVORITE);
    assertEquals(before, fixture.view.notices.size());
    assertEquals(List.of(), fixture.application.preferences().options().favorites());
    assertTrue(find(palette.filter("preferences"), CommandId.PREFERENCES).enabled());
    palette.run(CommandId.PREFERENCES);
    assertEquals(1, fixture.view.preferencesShown);
    fixture.application.execute(CommandId.COMMAND_PALETTE);
    assertEquals(1, fixture.view.paletteShown);
  }
}
