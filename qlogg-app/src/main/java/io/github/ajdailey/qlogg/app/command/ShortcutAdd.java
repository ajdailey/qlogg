package io.github.ajdailey.qlogg.app.command;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The shortcut editor's Add: the captured keys appended to a command's bindings, or the message
 * that says why nothing changes. Immutable.
 *
 * @param bindings the command's bindings with the keys added, or null when nothing changes
 * @param message the message to show, empty on success
 */
public record ShortcutAdd(List<Binding> bindings, String message) {
  /**
   * Adds captured keys to a command.
   *
   * @param command the selected command, or null
   * @param captured the captured strokes, at most two
   * @param current the keymap with the editor's overrides
   * @param defaults the default keymap, which picks the new binding's scope
   * @param mac true to name keys as macOS does
   * @return the outcome; {@link #bindings} null when the command is missing, nothing is captured,
   *     another command holds the keys, the command already has them, or it has {@link
   *     ShortcutOverrides#MAX_PER_COMMAND} bindings
   */
  public static ShortcutAdd of(
      CommandId command, List<KeyStroke> captured, Keymap current, Keymap defaults, boolean mac) {
    if (command == null) {
      return refused(UiText.SHORTCUTS_NO_COMMAND.text());
    }
    if (captured.isEmpty()) {
      return refused(UiText.SHORTCUTS_NO_KEYS.text());
    }
    KeyStroke first = captured.get(0);
    KeyStroke second = captured.size() > 1 ? captured.get(1) : null;
    Binding candidate =
        new Binding(
            command, first, second, ShortcutOverrides.scopeFor(command, first, second, defaults));
    String keys = candidate.text(mac);
    List<Binding> conflicts = current.conflicts(candidate);
    if (!conflicts.isEmpty()) {
      return refused(UiText.SHORTCUTS_CONFLICT.format(keys, conflicts.get(0).command().title()));
    }
    List<Binding> list = new ArrayList<Binding>(current.bindingsFor(command));
    for (Binding binding : list) {
      if (binding.first().equals(first)
          && (second == null ? binding.second() == null : second.equals(binding.second()))) {
        return refused(UiText.SHORTCUTS_ALREADY_BOUND.format(keys, command.title()));
      }
    }
    if (list.size() >= ShortcutOverrides.MAX_PER_COMMAND) {
      return refused(
          UiText.SHORTCUTS_FULL.format(command.title(), ShortcutOverrides.MAX_PER_COMMAND));
    }
    list.add(candidate);
    return new ShortcutAdd(Collections.unmodifiableList(list), "");
  }

  private static ShortcutAdd refused(String message) {
    return new ShortcutAdd(null, message);
  }
}
