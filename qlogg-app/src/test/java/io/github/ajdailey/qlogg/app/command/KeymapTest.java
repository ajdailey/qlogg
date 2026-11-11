package io.github.ajdailey.qlogg.app.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class KeymapTest {
  private final Keymap keymap = Keymap.defaults();

  @Test
  void noStrokeOrSequenceIsBoundTwice() {
    Set<String> seen = new HashSet<String>();
    for (Binding binding : keymap.bindings()) {
      assertTrue(seen.add(binding.text(false)), "bound twice: " + binding.text(false));
    }
  }

  @Test
  void noSingleStrokeIsAlsoASequencePrefix() {
    for (Binding sequence : keymap.bindings()) {
      if (sequence.second() == null) {
        continue;
      }
      for (Binding single : keymap.bindings()) {
        if (single.second() == null) {
          assertFalse(single.first().equals(sequence.first()), single.text(false));
        }
      }
    }
  }

  @Test
  void everyMenuCommandHasALabelAndEveryBindingACommandInTheTable() {
    for (CommandId command : CommandId.values()) {
      assertFalse(command.title().isEmpty());
      if (command.group() == CommandGroup.LOG_VIEW) {
        assertFalse(keymap.bindingsFor(command).isEmpty(), command + " has no key");
      }
      KeyStroke accelerator = keymap.accelerator(command);
      if (accelerator != null) {
        assertTrue(command.group().menu(), command + " has an accelerator but no menu");
      }
    }
  }

  @Test
  void goToTimeHasCtrlShiftLAloneInEveryScope() {
    KeyStroke stroke = new KeyStroke(KeyStroke.PRIMARY | KeyStroke.SHIFT, 'l');
    assertEquals(stroke, keymap.accelerator(CommandId.GO_TO_TIME));
    for (Binding binding : keymap.bindings()) {
      if (binding.first().equals(stroke) || stroke.equals(binding.second())) {
        assertEquals(CommandId.GO_TO_TIME, binding.command(), binding.text(false));
      }
    }
    assertEquals(List.of(), keymap.conflicts(keymap.bindingsFor(CommandId.GO_TO_TIME).get(0)));
  }

  @Test
  void clearColorLabelsHasASecondKey() {
    int primaryShift = KeyStroke.PRIMARY | KeyStroke.SHIFT;
    List<Binding> bindings = keymap.bindingsFor(CommandId.CLEAR_COLOR_LABELS);
    assertEquals(2, bindings.size());
    assertEquals(new KeyStroke(primaryShift, '0'), bindings.get(0).first());
    assertEquals(new KeyStroke(primaryShift, 'd'), bindings.get(1).first());
    assertEquals(List.of(), keymap.conflicts(bindings.get(1)));
    assertEquals(
        "Ctrl+Shift+0, Ctrl+Shift+D", keymap.keysText(CommandId.CLEAR_COLOR_LABELS, false));
  }

  @Test
  void kloggBindingsWinCollisions() {
    assertEquals(new KeyStroke(KeyStroke.PRIMARY, 'l'), keymap.accelerator(CommandId.GO_TO_LINE));
    assertEquals("Shift+G", keymap.bindingsFor(CommandId.GO_TO_BOTTOM).get(1).text(false));
    assertEquals("Ctrl+End, Shift+G", keymap.keysText(CommandId.GO_TO_BOTTOM, false));
    assertEquals("Cmd+E Cmd+T", keymap.keysText(CommandId.TOGGLE_LINE_NUMBERS, true));
    assertEquals("$", keymap.keysText(CommandId.LINE_END, false));
    assertEquals("End", keymap.keysText(CommandId.RIGHT_EDGE, false));
    assertEquals("Home, ^, 0", keymap.keysText(CommandId.LINE_START, false));
  }

  @Test
  void aMenuShowsTheFirstSingleStroke() {
    assertEquals("F3", keymap.menuKeyText(CommandId.FIND_NEXT, false));
    assertEquals("Ctrl+End", keymap.menuKeyText(CommandId.GO_TO_BOTTOM, false));
    assertEquals("M", keymap.menuKeyText(CommandId.TOGGLE_MARK, false));
    assertEquals("", keymap.menuKeyText(CommandId.TOGGLE_LINE_NUMBERS, false));
    assertEquals("Cmd+=", keymap.menuKeyText(CommandId.ZOOM_IN, true));
  }

  @Test
  void searchKeysFollowKlogg() {
    assertEquals("F3, N, Ctrl+G", keymap.keysText(CommandId.FIND_NEXT, false));
    assertEquals(
        "Shift+F3, Shift+N, Ctrl+Shift+G", keymap.keysText(CommandId.FIND_PREVIOUS, false));
    assertEquals("Ctrl+S, Ctrl+Shift+F", keymap.keysText(CommandId.FOCUS_SEARCH, false));
    assertEquals(new KeyStroke(0, KeyStroke.F3), keymap.accelerator(CommandId.FIND_NEXT));
    KeySequencer sequencer = new KeySequencer(keymap);
    assertEquals(new KeySequencer.Run(CommandId.FIND_NEXT), sequencer.press(KeyStroke.of(0, 'n')));
    assertEquals(
        new KeySequencer.Run(CommandId.FIND_PREVIOUS), sequencer.press(KeyStroke.of(0, 'N')));
  }

  @Test
  void filteredViewMarkAndQuickFindKeys() {
    assertEquals("1", keymap.keysText(CommandId.SHOW_MARKS_AND_MATCHES, false));
    assertEquals("V", keymap.keysText(CommandId.NEXT_VISIBILITY, false));
    assertEquals("Shift+V", keymap.keysText(CommandId.PREVIOUS_VISIBILITY, false));
    assertEquals("9", keymap.keysText(CommandId.TOGGLE_KEEP_RESULTS, false));
    assertEquals("M, Ctrl+E Ctrl+M", keymap.keysText(CommandId.TOGGLE_MARK, false));
    assertEquals("], Ctrl+E Ctrl+.", keymap.keysText(CommandId.NEXT_MARK, false));
    assertEquals("*, .", keymap.keysText(CommandId.FIND_SELECTION_FORWARD, false));
    assertEquals("Shift+A", keymap.keysText(CommandId.ADD_TO_SEARCH, false));
    assertEquals("Space", keymap.keysText(CommandId.RELEASE_FOCUS, false));
    assertEquals(KeyStroke.of(KeyStroke.PRIMARY, 'f'), keymap.accelerator(CommandId.FIND));
    KeySequencer sequencer = new KeySequencer(keymap);
    assertEquals(
        new KeySequencer.Run(CommandId.QUICK_FIND_BACKWARD), sequencer.press(KeyStroke.of(0, '"')));
    assertEquals(
        new KeySequencer.Run(CommandId.RELEASE_FOCUS),
        sequencer.press(new KeyStroke(0, KeyStroke.SPACE)));
    sequencer.press(KeyStroke.of(KeyStroke.PRIMARY, 'e'));
    assertEquals(
        new KeySequencer.Run(CommandId.PREVIOUS_MARK),
        sequencer.press(KeyStroke.of(KeyStroke.PRIMARY, ',')));
  }

  @Test
  void rejectsMalformedBindings() {
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            new Binding(null, KeyStroke.of(0, 'x'), null, Binding.Scope.LOG_VIEW);
          }
        });
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            new Binding(
                CommandId.OPEN, KeyStroke.of(0, 'x'), KeyStroke.of(0, 'y'), Binding.Scope.WINDOW);
          }
        });
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            new KeyStroke(0, 'A');
          }
        });
  }

  @Test
  void sequencerRunsSingleStrokesAndSequences() {
    KeySequencer sequencer = new KeySequencer(keymap);
    assertEquals(new KeySequencer.Run(CommandId.LINE_DOWN), sequencer.press(KeyStroke.of(0, 'j')));
    assertEquals(new KeySequencer.Run(CommandId.LINE_START), sequencer.press(KeyStroke.of(0, '0')));
    assertEquals(
        new KeySequencer.Run(CommandId.RIGHT_EDGE),
        sequencer.press(new KeyStroke(0, KeyStroke.END)));
    KeyStroke ctrlE = KeyStroke.of(KeyStroke.PRIMARY, 'e');
    assertEquals(new KeySequencer.Pending(ctrlE), sequencer.press(ctrlE));
    assertEquals(ctrlE, sequencer.prefix());
    assertEquals(
        new KeySequencer.Run(CommandId.TOGGLE_LINE_NUMBERS),
        sequencer.press(KeyStroke.of(KeyStroke.PRIMARY, 't')));
    assertNull(sequencer.prefix());
  }

  @Test
  void escapeOrAnUnboundKeyCancelsAPrefix() {
    KeySequencer sequencer = new KeySequencer(keymap);
    KeyStroke ctrlE = KeyStroke.of(KeyStroke.PRIMARY, 'e');
    sequencer.press(ctrlE);
    assertEquals(new KeySequencer.Unbound(), sequencer.press(new KeyStroke(0, KeyStroke.ESCAPE)));
    sequencer.press(ctrlE);
    assertEquals(new KeySequencer.Unbound(), sequencer.press(KeyStroke.of(0, 'j')));
    assertEquals(new KeySequencer.Run(CommandId.LINE_DOWN), sequencer.press(KeyStroke.of(0, 'j')));
  }

  @Test
  void windowStrokesAreLeftToTheMenu() {
    KeySequencer sequencer = new KeySequencer(keymap);
    assertEquals(new KeySequencer.Unbound(), sequencer.press(KeyStroke.of(KeyStroke.PRIMARY, 'o')));
  }

  @Test
  void referenceGroupsAndFilters() {
    String all = keymap.reference(false, "");
    assertTrue(all.startsWith("File\n  Open\tCtrl+O\n"), all);
    assertTrue(all.contains("Log view keys\n"));
    String filtered = keymap.reference(false, "line numbers");
    assertEquals(List.of("View", "  Line Numbers\tCtrl+E Ctrl+T"), List.of(filtered.split("\n")));
  }

  // Navigation history and command palette.

  @Test
  void goBackAndForwardTakeAltArrowsOrCommandBrackets() {
    Keymap others = Keymap.defaults(false);
    assertEquals(
        new KeyStroke(KeyStroke.ALT, KeyStroke.LEFT), others.accelerator(CommandId.GO_BACK));
    assertEquals(
        new KeyStroke(KeyStroke.ALT, KeyStroke.RIGHT), others.accelerator(CommandId.GO_FORWARD));
    Keymap mac = Keymap.defaults(true);
    assertEquals(KeyStroke.of(KeyStroke.PRIMARY, '['), mac.accelerator(CommandId.GO_BACK));
    assertEquals(KeyStroke.of(KeyStroke.PRIMARY, ']'), mac.accelerator(CommandId.GO_FORWARD));
    for (Keymap map : List.of(others, mac)) {
      for (CommandId command :
          List.of(
              CommandId.GO_BACK,
              CommandId.GO_FORWARD,
              CommandId.GO_TO_RESULT,
              CommandId.COMMAND_PALETTE)) {
        for (Binding binding : map.bindingsFor(command)) {
          assertEquals(List.of(), map.conflicts(binding), binding.text(false));
        }
      }
    }
    assertEquals(
        KeyStroke.of(KeyStroke.PRIMARY, 'P'), others.accelerator(CommandId.COMMAND_PALETTE));
    assertEquals(
        new KeyStroke(0, KeyStroke.ENTER),
        others.bindingsFor(CommandId.GO_TO_RESULT).get(0).first());
  }
}
