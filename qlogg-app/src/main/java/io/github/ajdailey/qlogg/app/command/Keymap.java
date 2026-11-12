package io.github.ajdailey.qlogg.app.command;

import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The one table of key bindings. Menu accelerators, the log view's key handler and Help > Keyboard
 * Shortcuts all read it. The defaults are klogg's bindings, then jEdit's for features klogg lacks;
 * on a collision klogg's binding is kept ({@code Ctrl+L} is go to line). Immutable.
 */
public final class Keymap {
  private static final int P = KeyStroke.PRIMARY;
  private static final int S = KeyStroke.SHIFT;

  private final List<Binding> bindings;
  private final boolean mac;

  /**
   * Creates a keymap. Outside macOS each binding's {@link KeyStroke#CONTROL} becomes {@link
   * KeyStroke#PRIMARY}, and a binding that then repeats an earlier one is dropped.
   *
   * @param bindings the bindings, in display order
   * @param mac true for macOS bindings
   */
  public Keymap(List<Binding> bindings, boolean mac) {
    List<Binding> kept = new ArrayList<Binding>(bindings.size());
    for (Binding binding : bindings) {
      Binding folded = binding.forPlatform(mac);
      if (folded == binding || !kept.contains(folded)) {
        kept.add(folded);
      }
    }
    this.bindings = Collections.unmodifiableList(kept);
    this.mac = mac;
  }

  /**
   * Returns the default bindings of the running platform.
   *
   * @return the keymap
   */
  public static Keymap defaults() {
    return defaults(OsTraits.current().macKeymap());
  }

  /**
   * Returns the default bindings. Go Back and Go Forward are {@code Alt+Left} and {@code
   * Alt+Right}, or {@code Control+Command+Left} and {@code Right} on macOS, where {@code
   * Option+Left} and {@code Right} move by word in a text field.
   *
   * @param mac true for the macOS bindings
   * @return the keymap
   */
  public static Keymap defaults(boolean mac) {
    List<Binding> b = new ArrayList<Binding>();
    window(b, CommandId.OPEN, P, 'o');
    view(b, CommandId.OPEN_CLIPBOARD, P, 'v');
    window(b, CommandId.RELOAD, 0, KeyStroke.F5);
    window(b, CommandId.CLOSE, P, 'w');
    window(b, CommandId.QUIT, P, 'q');
    view(b, CommandId.COPY, P, 'c');
    view(b, CommandId.SELECT_ALL, P, 'a');
    window(b, CommandId.FIND, P, 'f');
    view(b, CommandId.FOLLOW, 0, 'f');
    window(b, CommandId.FOLLOW, 0, KeyStroke.F10);
    window(b, CommandId.SWITCH_FILE, P, 'O');
    window(b, CommandId.NEXT_TAB, P, KeyStroke.PAGE_DOWN);
    window(b, CommandId.PREVIOUS_TAB, P, KeyStroke.PAGE_UP);
    sequence(b, CommandId.TOGGLE_LINE_NUMBERS, 't');
    view(b, CommandId.SHOW_MARKS_AND_MATCHES, 0, '1');
    view(b, CommandId.SHOW_MARKS, 0, '2');
    view(b, CommandId.SHOW_MATCHES, 0, '3');
    view(b, CommandId.NEXT_VISIBILITY, 0, 'v');
    view(b, CommandId.PREVIOUS_VISIBILITY, 0, 'V');
    view(b, CommandId.GROW_MAIN_VIEW, 0, '+');
    view(b, CommandId.SHRINK_MAIN_VIEW, 0, '-');
    window(b, CommandId.FOCUS_SEARCH, P, 's');
    view(b, CommandId.FOCUS_SEARCH, P, 'F');
    window(b, CommandId.FIND_NEXT, 0, KeyStroke.F3);
    view(b, CommandId.FIND_NEXT, 0, 'n');
    view(b, CommandId.FIND_NEXT, P, 'g');
    window(b, CommandId.FIND_PREVIOUS, S, KeyStroke.F3);
    view(b, CommandId.FIND_PREVIOUS, 0, 'N');
    view(b, CommandId.FIND_PREVIOUS, P, 'G');
    view(b, CommandId.TOGGLE_CASE, 0, '4');
    view(b, CommandId.TOGGLE_REGEX, 0, '5');
    view(b, CommandId.TOGGLE_INVERSE, 0, '6');
    view(b, CommandId.TOGGLE_COMBINING, 0, '7');
    view(b, CommandId.TOGGLE_AUTO_REFRESH, 0, '8');
    view(b, CommandId.TOGGLE_KEEP_RESULTS, 0, '9');
    view(b, CommandId.ADD_TO_SEARCH, 0, 'A');
    view(b, CommandId.EXCLUDE_FROM_SEARCH, 0, 'E');
    view(b, CommandId.REPLACE_SEARCH, 0, 'R');
    window(b, CommandId.GO_TO_LINE, P, 'l');
    // Timestamps.
    window(b, CommandId.GO_TO_TIME, P, 'L');
    view(b, CommandId.GO_TO_TOP, P, KeyStroke.HOME);
    view(b, CommandId.GO_TO_BOTTOM, P, KeyStroke.END);
    view(b, CommandId.GO_TO_BOTTOM, 0, 'G');
    view(b, CommandId.TOGGLE_MARK, 0, 'm');
    sequence(b, CommandId.TOGGLE_MARK, 'm');
    view(b, CommandId.NEXT_MARK, 0, ']');
    sequence(b, CommandId.NEXT_MARK, '.');
    view(b, CommandId.PREVIOUS_MARK, 0, '[');
    sequence(b, CommandId.PREVIOUS_MARK, ',');
    // Mark comments.
    view(b, CommandId.EDIT_MARK_COMMENT, 0, 'M');
    b.add(
        new Binding(
            CommandId.KEYBOARD_SHORTCUTS,
            new KeyStroke(0, KeyStroke.F1),
            null,
            Binding.Scope.WINDOW));
    view(b, CommandId.LINE_UP, 0, KeyStroke.UP);
    view(b, CommandId.LINE_UP, 0, 'k');
    view(b, CommandId.LINE_DOWN, 0, KeyStroke.DOWN);
    view(b, CommandId.LINE_DOWN, 0, 'j');
    view(b, CommandId.EXTEND_UP, S, KeyStroke.UP);
    view(b, CommandId.EXTEND_DOWN, S, KeyStroke.DOWN);
    view(b, CommandId.SCROLL_UP, P, KeyStroke.UP);
    view(b, CommandId.SCROLL_DOWN, P, KeyStroke.DOWN);
    view(b, CommandId.PAGE_UP, 0, KeyStroke.PAGE_UP);
    view(b, CommandId.PAGE_DOWN, 0, KeyStroke.PAGE_DOWN);
    view(b, CommandId.SCROLL_LEFT, 0, KeyStroke.LEFT);
    view(b, CommandId.SCROLL_LEFT, 0, 'h');
    view(b, CommandId.SCROLL_RIGHT, 0, KeyStroke.RIGHT);
    view(b, CommandId.SCROLL_RIGHT, 0, 'l');
    view(b, CommandId.LINE_START, 0, KeyStroke.HOME);
    view(b, CommandId.LINE_START, 0, '^');
    view(b, CommandId.LINE_START, 0, '0');
    view(b, CommandId.LINE_END, 0, '$');
    view(b, CommandId.RIGHT_EDGE, 0, KeyStroke.END);
    view(b, CommandId.QUICK_FIND_FORWARD, 0, '\'');
    view(b, CommandId.QUICK_FIND_BACKWARD, 0, '"');
    view(b, CommandId.FIND_SELECTION_FORWARD, 0, '*');
    view(b, CommandId.FIND_SELECTION_FORWARD, 0, '.');
    view(b, CommandId.FIND_SELECTION_BACKWARD, 0, '/');
    view(b, CommandId.FIND_SELECTION_BACKWARD, 0, ',');
    view(b, CommandId.RELEASE_FOCUS, 0, KeyStroke.SPACE);
    view(b, CommandId.STOP, 0, KeyStroke.ESCAPE);
    view(b, CommandId.NEXT_COLOR_LABEL, P, 'd');
    view(b, CommandId.CLEAR_COLOR_LABELS, P | S, '0');
    // Windows can take Ctrl+Shift+0 as an input-language hotkey.
    view(b, CommandId.CLEAR_COLOR_LABELS, P, 'D');
    for (int i = 0; i < 9; i++) {
      view(b, CommandId.values()[CommandId.COLOR_LABEL_1.ordinal() + i], P | S, (char) ('1' + i));
    }
    // Wrap, folding, block selection, scratchpad and search across open files.
    view(b, CommandId.TOGGLE_WRAP, 0, 'w');
    view(b, CommandId.TOGGLE_FOLD, KeyStroke.ALT, KeyStroke.BACKSPACE);
    sequence(b, CommandId.COLLAPSE_ALL_FOLDS, 'c');
    sequence(b, CommandId.EXPAND_ALL_FOLDS, 'x');
    view(b, CommandId.EXTEND_BLOCK_UP, KeyStroke.ALT | S, KeyStroke.UP);
    view(b, CommandId.EXTEND_BLOCK_DOWN, KeyStroke.ALT | S, KeyStroke.DOWN);
    view(b, CommandId.EXTEND_BLOCK_LEFT, KeyStroke.ALT | S, KeyStroke.LEFT);
    view(b, CommandId.EXTEND_BLOCK_RIGHT, KeyStroke.ALT | S, KeyStroke.RIGHT);
    view(b, CommandId.SEND_TO_SCRATCHPAD, P, 'z');
    view(b, CommandId.REPLACE_SCRATCHPAD, P, 'Z');
    window(b, CommandId.SEARCH_ALL_FILES, P, 'S');
    // Font zoom.
    window(b, CommandId.ZOOM_IN, P, '=');
    window(b, CommandId.ZOOM_IN, P, KeyStroke.KEYPAD_ADD);
    window(b, CommandId.ZOOM_OUT, P, '-');
    window(b, CommandId.ZOOM_OUT, P, KeyStroke.KEYPAD_SUBTRACT);
    window(b, CommandId.ZOOM_RESET, P, '0');
    // Context lines and range filter.
    view(b, CommandId.SELECT_RANGE, P, 'r');
    // Navigation history and command palette.
    int back = mac ? KeyStroke.CONTROL | P : KeyStroke.ALT;
    window(b, CommandId.GO_BACK, back, KeyStroke.LEFT);
    window(b, CommandId.GO_FORWARD, back, KeyStroke.RIGHT);
    view(b, CommandId.GO_TO_RESULT, 0, KeyStroke.ENTER);
    window(b, CommandId.COMMAND_PALETTE, P, 'P');
    // Reopen Closed Tab.
    window(b, CommandId.REOPEN_CLOSED, P, 'T');
    return new Keymap(b, mac);
  }

  /** Adds a jEdit two-stroke binding: {@code Ctrl+E}, then {@code Ctrl+} the char. */
  private static void sequence(List<Binding> b, CommandId command, char c) {
    b.add(new Binding(command, KeyStroke.of(P, 'e'), KeyStroke.of(P, c), Binding.Scope.LOG_VIEW));
  }

  private static void window(List<Binding> b, CommandId command, int modifiers, char c) {
    b.add(new Binding(command, KeyStroke.of(modifiers, c), null, Binding.Scope.WINDOW));
  }

  private static void window(List<Binding> b, CommandId command, int modifiers, int key) {
    b.add(new Binding(command, new KeyStroke(modifiers, key), null, Binding.Scope.WINDOW));
  }

  private static void view(List<Binding> b, CommandId command, int modifiers, char c) {
    b.add(new Binding(command, KeyStroke.of(modifiers, c), null, Binding.Scope.LOG_VIEW));
  }

  private static void view(List<Binding> b, CommandId command, int modifiers, int key) {
    b.add(new Binding(command, new KeyStroke(modifiers, key), null, Binding.Scope.LOG_VIEW));
  }

  /**
   * Returns every binding.
   *
   * @return the bindings, in display order
   */
  public List<Binding> bindings() {
    return bindings;
  }

  /**
   * Returns a command's bindings.
   *
   * @param command the command
   * @return its bindings, possibly none
   */
  public List<Binding> bindingsFor(CommandId command) {
    List<Binding> found = new ArrayList<Binding>();
    for (Binding binding : bindings) {
      if (binding.command() == command) {
        found.add(binding);
      }
    }
    return found;
  }

  /**
   * Returns the stroke a menu item sets as its accelerator: the command's first window binding.
   *
   * @param command the command
   * @return the stroke, or null when the command has no window binding
   */
  public KeyStroke accelerator(CommandId command) {
    for (Binding binding : bindings) {
      if (binding.command() == command && binding.scope() == Binding.Scope.WINDOW) {
        return binding.first();
      }
    }
    return null;
  }

  /**
   * Returns a command's keys as the platform writes them, joined with {@code ", "}.
   *
   * @param command the command
   * @param mac true for macOS names
   * @return the keys, empty when the command has none
   */
  public String keysText(CommandId command, boolean mac) {
    StringBuilder text = new StringBuilder();
    for (Binding binding : bindings) {
      if (binding.command() == command) {
        if (text.length() > 0) {
          text.append(", ");
        }
        text.append(binding.text(mac));
      }
    }
    return text.toString();
  }

  /**
   * Returns the key a menu item shows after its tab: the command's first single-stroke binding. GTK
   * parses that text as one accelerator, so a list of keys shows a key of another command and a
   * two-stroke sequence shows its second stroke alone.
   *
   * @param command the command
   * @param mac true for macOS names
   * @return the key, empty when the command has no single-stroke binding
   */
  public String menuKeyText(CommandId command, boolean mac) {
    for (Binding binding : bindings) {
      if (binding.command() == command && binding.second() == null) {
        return binding.text(mac);
      }
    }
    return "";
  }

  /**
   * Returns the shortcut reference as plain text: one line per command with keys, grouped by menu.
   *
   * @param mac true for macOS names
   * @param filter keeps a line whose group, command or keys contain it, ignoring case; empty keeps
   *     every line
   * @return the text
   */
  public String reference(boolean mac, String filter) {
    String needle = filter.toLowerCase(Locale.ROOT);
    StringBuilder text = new StringBuilder();
    for (CommandGroup group : CommandGroup.values()) {
      boolean header = false;
      for (CommandId command : CommandId.values()) {
        String keys = keysText(command, mac);
        if (command.group() != group || keys.isEmpty()) {
          continue;
        }
        String row = group.title() + " " + command.title() + " " + keys;
        if (!row.toLowerCase(Locale.ROOT).contains(needle)) {
          continue;
        }
        if (!header) {
          text.append(group.title()).append('\n');
          header = true;
        }
        text.append("  ").append(command.title()).append('\t').append(keys).append('\n');
      }
    }
    return text.toString();
  }

  // Shortcut overrides.

  /**
   * Returns this keymap with each overridden command's bindings replaced. A command's new bindings
   * take the place of its first binding here, or follow the last binding when it had none.
   *
   * @param overrides the bindings per command; an empty list leaves the command unbound
   * @return the keymap
   */
  public Keymap withOverrides(ShortcutOverrides overrides) {
    if (overrides.isEmpty()) {
      return this;
    }
    List<Binding> result = new ArrayList<Binding>();
    List<CommandId> placed = new ArrayList<CommandId>();
    for (Binding binding : bindings) {
      List<Binding> replaced = overrides.bindings(binding.command());
      if (replaced == null) {
        result.add(binding);
      } else if (!placed.contains(binding.command())) {
        placed.add(binding.command());
        result.addAll(replaced);
      }
    }
    for (CommandId command : CommandId.values()) {
      List<Binding> replaced = overrides.bindings(command);
      if (replaced != null && !placed.contains(command)) {
        result.addAll(replaced);
      }
    }
    return new Keymap(result, mac);
  }

  /**
   * Returns the bindings a new binding collides with: the same keys in any scope, because a window
   * stroke also reaches the log view, and a single stroke that is also the first stroke of a
   * sequence.
   *
   * @param candidate the new binding
   * @return the colliding bindings of other commands, possibly none
   */
  public List<Binding> conflicts(Binding candidate) {
    List<Binding> found = new ArrayList<Binding>();
    for (Binding binding : bindings) {
      if (binding.command() == candidate.command()) {
        continue;
      }
      boolean sameKeys =
          binding.first().equals(candidate.first())
              && (binding.second() == null
                  ? candidate.second() == null
                  : binding.second().equals(candidate.second()));
      boolean prefix =
          binding.first().equals(candidate.first())
              && (binding.second() == null) != (candidate.second() == null);
      if (sameKeys || prefix) {
        found.add(binding);
      }
    }
    return found;
  }

  /**
   * Returns the command a window stroke runs when it is not the command's menu accelerator, such as
   * a second window binding.
   *
   * @param stroke the stroke
   * @return the command, or null when the stroke is an accelerator or unbound in the window
   */
  public CommandId windowCommand(KeyStroke stroke) {
    for (Binding binding : bindings) {
      if (binding.scope() == Binding.Scope.WINDOW && binding.first().equals(stroke)) {
        return stroke.equals(accelerator(binding.command())) ? null : binding.command();
      }
    }
    return null;
  }
}
