package io.github.ajdailey.qlogg.app.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link ShortcutAdd}: the added binding, and a message for each Add that changes nothing. */
class ShortcutAddTest {
  private final Keymap defaults = Keymap.defaults(OsTraits.of("Linux"));

  @Test
  void freeKeysAreAppended() {
    ShortcutAdd add = add(CommandId.OPEN, "Ctrl+Alt+K", defaults);
    assertEquals("", add.message());
    List<Binding> bindings = add.bindings();
    assertEquals(defaults.bindingsFor(CommandId.OPEN).size() + 1, bindings.size());
    assertEquals("Ctrl+Alt+K", bindings.get(bindings.size() - 1).text(false));
  }

  @Test
  void anotherCommandsKeysNameThatCommand() {
    ShortcutAdd add = add(CommandId.OPEN, "Ctrl+Shift+O", defaults);
    assertNull(add.bindings());
    assertEquals(
        UiText.SHORTCUTS_CONFLICT.format("Ctrl+Shift+O", CommandId.SWITCH_FILE.title()),
        add.message());
  }

  @Test
  void noSelectedCommandSaysSo() {
    ShortcutAdd add = add(null, "Ctrl+Alt+K", defaults);
    assertNull(add.bindings());
    assertEquals(UiText.SHORTCUTS_NO_COMMAND.text(), add.message());
  }

  @Test
  void anEmptyKeyFieldSaysSo() {
    ShortcutAdd add =
        ShortcutAdd.of(CommandId.OPEN, new ArrayList<KeyStroke>(), defaults, defaults, false);
    assertNull(add.bindings());
    assertEquals(UiText.SHORTCUTS_NO_KEYS.text(), add.message());
  }

  @Test
  void theCommandsOwnKeysSaySo() {
    ShortcutAdd add = add(CommandId.OPEN, "Ctrl+O", defaults);
    assertNull(add.bindings());
    assertEquals(
        UiText.SHORTCUTS_ALREADY_BOUND.format("Ctrl+O", CommandId.OPEN.title()), add.message());
  }

  @Test
  void aFullCommandSaysSo() {
    List<Binding> full = new ArrayList<Binding>();
    for (int i = 0; i < ShortcutOverrides.MAX_PER_COMMAND; i++) {
      full.add(
          new Binding(
              CommandId.OPEN, KeyStroke.parse("Ctrl+Alt+F" + (i + 1)), null, Binding.Scope.WINDOW));
    }
    Keymap current = defaults.withOverrides(ShortcutOverrides.NONE.with(CommandId.OPEN, full));
    ShortcutAdd add = add(CommandId.OPEN, "Ctrl+Alt+K", current);
    assertNull(add.bindings());
    assertEquals(
        UiText.SHORTCUTS_FULL.format(CommandId.OPEN.title(), ShortcutOverrides.MAX_PER_COMMAND),
        add.message());
  }

  private ShortcutAdd add(CommandId command, String keys, Keymap current) {
    List<KeyStroke> captured = new ArrayList<KeyStroke>();
    captured.add(KeyStroke.parse(keys));
    return ShortcutAdd.of(command, captured, current, defaults, false);
  }
}
