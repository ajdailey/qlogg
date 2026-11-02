package io.github.ajdailey.qlogg.app.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Key stroke text, shortcut overrides over the default keymap, and the zoom keys. */
class ShortcutOverridesTest {
  private final Keymap defaults = Keymap.defaults();

  @Test
  void everyDefaultBindingParsesBackFromItsText() {
    for (Binding binding : defaults.bindings()) {
      assertEquals(binding.first(), KeyStroke.parse(binding.first().text(false)));
      if (binding.second() != null) {
        assertEquals(binding.second(), KeyStroke.parse(binding.second().text(false)));
      }
    }
    assertEquals(new KeyStroke(KeyStroke.PRIMARY, '+'), KeyStroke.parse("Ctrl++"));
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            KeyStroke.parse("Ctrl+Nope");
          }
        });
  }

  @Test
  void zoomKeysAreWindowBindingsAndTheKeypadKeysRunWithoutAMenuAccelerator() {
    assertEquals(KeyStroke.parse("Ctrl+="), defaults.accelerator(CommandId.ZOOM_IN));
    assertEquals(KeyStroke.parse("Ctrl+-"), defaults.accelerator(CommandId.ZOOM_OUT));
    assertEquals(KeyStroke.parse("Ctrl+0"), defaults.accelerator(CommandId.ZOOM_RESET));
    assertEquals("Ctrl+=, Ctrl+Num+", defaults.keysText(CommandId.ZOOM_IN, false));
    assertEquals(CommandId.ZOOM_IN, defaults.windowCommand(KeyStroke.parse("Ctrl+Num+")));
    assertEquals(CommandId.ZOOM_OUT, defaults.windowCommand(KeyStroke.parse("Ctrl+Num-")));
    assertNull(defaults.windowCommand(KeyStroke.parse("Ctrl+=")));
    assertNull(defaults.windowCommand(KeyStroke.parse("Ctrl+0")));
  }

  @Test
  void overridesReplaceACommandsBindingsInPlace() {
    Binding k = new Binding(CommandId.OPEN, KeyStroke.parse("Ctrl+K"), null, Binding.Scope.WINDOW);
    ShortcutOverrides overrides =
        ShortcutOverrides.NONE.with(CommandId.OPEN, List.of(k)).with(CommandId.RELOAD, List.of());
    Keymap keymap = defaults.withOverrides(overrides);
    assertEquals(KeyStroke.parse("Ctrl+K"), keymap.accelerator(CommandId.OPEN));
    assertEquals(0, keymap.bindings().indexOf(k));
    assertTrue(keymap.bindingsFor(CommandId.RELOAD).isEmpty());
    assertEquals(defaults.bindingsFor(CommandId.FIND), keymap.bindingsFor(CommandId.FIND));
    assertEquals(
        defaults.bindings(),
        defaults
            .withOverrides(overrides.without(CommandId.OPEN).without(CommandId.RELOAD))
            .bindings());
  }

  @Test
  void conflictsNameTheCommandHoldingTheKeys() {
    Binding taken =
        new Binding(CommandId.RELOAD, KeyStroke.parse("Ctrl+O"), null, Binding.Scope.WINDOW);
    assertEquals(CommandId.OPEN, defaults.conflicts(taken).get(0).command());
    Binding prefix =
        new Binding(CommandId.RELOAD, KeyStroke.parse("Ctrl+E"), null, Binding.Scope.LOG_VIEW);
    assertTrue(defaults.conflicts(prefix).size() > 1);
    Binding free =
        new Binding(CommandId.RELOAD, KeyStroke.parse("Ctrl+Alt+R"), null, Binding.Scope.WINDOW);
    assertTrue(defaults.conflicts(free).isEmpty());
  }

  @Test
  void scopeFollowsTheKeys() {
    assertEquals(
        Binding.Scope.WINDOW,
        ShortcutOverrides.scopeFor(CommandId.RELOAD, KeyStroke.parse("Ctrl+R"), null, defaults));
    assertEquals(
        Binding.Scope.LOG_VIEW,
        ShortcutOverrides.scopeFor(CommandId.RELOAD, KeyStroke.parse("R"), null, defaults));
    assertEquals(
        Binding.Scope.LOG_VIEW,
        ShortcutOverrides.scopeFor(CommandId.COPY, KeyStroke.parse("Ctrl+C"), null, defaults));
    assertEquals(
        Binding.Scope.LOG_VIEW,
        ShortcutOverrides.scopeFor(
            CommandId.RELOAD, KeyStroke.parse("Ctrl+E"), KeyStroke.parse("Ctrl+R"), defaults));
  }

  @Test
  void overridesRoundTripThroughProperties() {
    ShortcutOverrides overrides =
        ShortcutOverrides.NONE
            .with(
                CommandId.FOLLOW,
                List.of(
                    new Binding(
                        CommandId.FOLLOW,
                        KeyStroke.parse("Ctrl+E"),
                        KeyStroke.parse("Ctrl+F"),
                        Binding.Scope.LOG_VIEW),
                    new Binding(
                        CommandId.FOLLOW, KeyStroke.parse("F9"), null, Binding.Scope.WINDOW)))
            .with(CommandId.STOP, List.of());
    Properties properties = new Properties();
    overrides.store(properties);
    assertEquals("view Ctrl+E Ctrl+F", properties.getProperty("shortcut.FOLLOW.0"));
    assertEquals(overrides, ShortcutOverrides.load(properties));
  }
}
