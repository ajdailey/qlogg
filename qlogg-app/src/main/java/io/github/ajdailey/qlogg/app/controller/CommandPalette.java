package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.command.CommandGroup;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.MenuTable;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import java.util.ArrayList;
import java.util.List;

/**
 * View > Command Palette: every menu command with its menu path, filtered by a typed query and run
 * through {@link ApplicationController#execute}. A query matches when its non-blank chars appear in
 * order in the command's title, ignoring case, else in its menu path and title; rows sort by {@link
 * #score}, then in menu order. A disabled command is listed with {@code enabled} false and does not
 * run. Confined to the UI thread.
 */
public final class CommandPalette {
  /**
   * One row.
   *
   * @param command the command
   * @param title the command's title
   * @param menu the menu path, for example {@code Search > Search Options}
   * @param enabled true when {@link CommandStates#enabled} allows the command now
   */
  public record Entry(CommandId command, String title, String menu, boolean enabled) {}

  private final ApplicationController application;
  private final CommandStates states;

  CommandPalette(ApplicationController application) {
    this.application = application;
    this.states = new CommandStates(application);
  }

  /**
   * Returns the rows a query matches, best first.
   *
   * @param query the typed text; blank lists every command in menu order
   * @return the rows
   */
  public List<Entry> filter(String query) {
    List<Entry> entries = new ArrayList<Entry>();
    List<Integer> scores = new ArrayList<Integer>();
    for (CommandGroup group : CommandGroup.values()) {
      if (!group.menu()) {
        continue;
      }
      for (CommandId command : MenuTable.commands(group, false)) {
        if (command == CommandId.COMMAND_PALETTE) {
          continue;
        }
        String menu = menuPath(command);
        int score = score(query, command.title());
        if (score < 0) {
          score = score(query, menu + " " + command.title());
        }
        if (score < 0) {
          continue;
        }
        int at = 0;
        while (at < scores.size() && scores.get(at) >= score) {
          at++;
        }
        scores.add(at, score);
        entries.add(at, new Entry(command, command.title(), menu, states.enabled(command)));
      }
    }
    return entries;
  }

  /**
   * Runs a command when it is enabled.
   *
   * @param command the chosen row's command
   */
  public void run(CommandId command) {
    if (states.enabled(command)) {
      application.execute(command);
    }
  }

  /**
   * Returns the menus a command sits in, outermost first, joined with {@code " > "}.
   *
   * @param command a menu command
   * @return for example {@code View > Timestamps > Time Spread}
   */
  public static String menuPath(CommandId command) {
    StringBuilder path = new StringBuilder(command.group().title());
    if (command.submenu() != null) {
      path.append(" > ").append(command.submenu().replace("&", ""));
    }
    UiText nested = MenuTable.nestedCascade(command);
    if (nested != null) {
      path.append(" > ").append(nested.text().replace("&", ""));
    }
    return path.toString();
  }

  /**
   * Scores a match, taking each query char at its first place after the previous one. A char scores
   * 1, 3 more right after the previous match and 5 more at the start of a word.
   *
   * @param query the query; blanks are skipped
   * @param text the text
   * @return the score, 0 for a blank query, or -1 when the text does not match
   */
  static int score(String query, String text) {
    int score = 0;
    int at = 0;
    int previous = -2;
    for (int i = 0; i < query.length(); i++) {
      char wanted = Character.toLowerCase(query.charAt(i));
      if (Character.isWhitespace(wanted)) {
        continue;
      }
      while (at < text.length() && Character.toLowerCase(text.charAt(at)) != wanted) {
        at++;
      }
      if (at == text.length()) {
        return -1;
      }
      score++;
      if (at == previous + 1) {
        score += 3;
      }
      if (at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1))) {
        score += 5;
      }
      previous = at;
      at++;
    }
    return score;
  }
}
