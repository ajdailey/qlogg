package io.github.ajdailey.qlogg.app.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Key bindings the user set in place of a command's defaults. Stored in {@code qlogg.conf} as
 * {@code shortcut.<COMMAND>.count} and {@code shortcut.<COMMAND>.<n>} = {@code window} or {@code
 * view}, a space, then the keys as {@link Binding#text} writes them with {@code mac} false. A count
 * of 0 leaves the command unbound. Immutable.
 */
public final class ShortcutOverrides {
  /** No overrides. */
  public static final ShortcutOverrides NONE =
      new ShortcutOverrides(new EnumMap<CommandId, List<Binding>>(CommandId.class));

  /** Most bindings kept for one command. */
  public static final int MAX_PER_COMMAND = 8;

  static final String PREFIX = "shortcut.";
  private static final String COUNT = ".count";
  private static final String WINDOW = "window";
  private static final String VIEW = "view";

  private final Map<CommandId, List<Binding>> overrides;

  private ShortcutOverrides(Map<CommandId, List<Binding>> overrides) {
    this.overrides = overrides;
  }

  /**
   * Reports whether no command is overridden.
   *
   * @return true with no override
   */
  public boolean isEmpty() {
    return overrides.isEmpty();
  }

  /**
   * Returns a command's bindings.
   *
   * @param command the command
   * @return the bindings that replace its defaults, or null when it keeps them
   */
  public List<Binding> bindings(CommandId command) {
    return overrides.get(command);
  }

  /**
   * Returns a copy with a command's bindings replaced.
   *
   * @param command the command
   * @param replaced its bindings, each for this command; at most {@link #MAX_PER_COMMAND}
   * @return the copy
   */
  public ShortcutOverrides with(CommandId command, List<Binding> replaced) {
    for (Binding binding : replaced) {
      if (binding.command() != command) {
        throw new IllegalArgumentException("binding for another command");
      }
    }
    Map<CommandId, List<Binding>> copy = new EnumMap<CommandId, List<Binding>>(CommandId.class);
    copy.putAll(overrides);
    copy.put(command, Collections.unmodifiableList(new ArrayList<Binding>(limit(replaced))));
    return new ShortcutOverrides(copy);
  }

  /**
   * Returns a copy where a command keeps its default bindings.
   *
   * @param command the command
   * @return the copy
   */
  public ShortcutOverrides without(CommandId command) {
    Map<CommandId, List<Binding>> copy = new EnumMap<CommandId, List<Binding>>(CommandId.class);
    copy.putAll(overrides);
    copy.remove(command);
    return copy.isEmpty() ? NONE : new ShortcutOverrides(copy);
  }

  private static List<Binding> limit(List<Binding> list) {
    return list.size() > MAX_PER_COMMAND ? list.subList(0, MAX_PER_COMMAND) : list;
  }

  /**
   * Returns where a new binding applies: a sequence, a log view command or a plain key applies in
   * the log view; a stroke the command has by default keeps that binding's scope; any other stroke
   * with Ctrl or Alt, or a function key, applies in the window.
   *
   * @param command the command
   * @param first the first stroke
   * @param second the second stroke, or null
   * @param defaults the default keymap
   * @return the scope
   */
  public static Binding.Scope scopeFor(
      CommandId command, KeyStroke first, KeyStroke second, Keymap defaults) {
    if (second != null || !command.group().menu()) {
      return Binding.Scope.LOG_VIEW;
    }
    for (Binding binding : defaults.bindingsFor(command)) {
      if (binding.second() == null && binding.first().equals(first)) {
        return binding.scope();
      }
    }
    boolean modified = (first.modifiers() & (KeyStroke.PRIMARY | KeyStroke.ALT)) != 0;
    boolean function = first.key() >= KeyStroke.F1 && first.key() < KeyStroke.F1 + 12;
    return modified || function ? Binding.Scope.WINDOW : Binding.Scope.LOG_VIEW;
  }

  /**
   * Reads the overrides; an entry that does not parse is skipped.
   *
   * @param properties the file's properties
   * @return the overrides
   */
  public static ShortcutOverrides load(Properties properties) {
    Map<CommandId, List<Binding>> read = new EnumMap<CommandId, List<Binding>>(CommandId.class);
    for (CommandId command : CommandId.values()) {
      String count = properties.getProperty(PREFIX + command.name() + COUNT);
      if (count == null) {
        continue;
      }
      int n;
      try {
        n = Math.min(MAX_PER_COMMAND, Integer.parseInt(count.strip()));
      } catch (NumberFormatException e) {
        continue;
      }
      List<Binding> list = new ArrayList<Binding>();
      for (int i = 0; i < n; i++) {
        Binding binding = parse(command, properties.getProperty(PREFIX + command.name() + "." + i));
        if (binding != null) {
          list.add(binding);
        }
      }
      read.put(command, Collections.unmodifiableList(list));
    }
    return read.isEmpty() ? NONE : new ShortcutOverrides(read);
  }

  /** Returns the binding, or null when the text does not parse. */
  static Binding parse(CommandId command, String text) {
    if (text == null) {
      return null;
    }
    int space = text.indexOf(' ');
    if (space < 0) {
      return null;
    }
    String scope = text.substring(0, space);
    String keys = text.substring(space + 1);
    Binding.Scope parsed;
    if (WINDOW.equals(scope)) {
      parsed = Binding.Scope.WINDOW;
    } else if (VIEW.equals(scope)) {
      parsed = Binding.Scope.LOG_VIEW;
    } else {
      return null;
    }
    try {
      int split = keys.indexOf(' ');
      if (split < 0) {
        return new Binding(command, KeyStroke.parse(keys), null, parsed);
      }
      return new Binding(
          command,
          KeyStroke.parse(keys.substring(0, split)),
          KeyStroke.parse(keys.substring(split + 1)),
          parsed);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Writes the overrides.
   *
   * @param properties receives the keys
   */
  public void store(Properties properties) {
    for (Map.Entry<CommandId, List<Binding>> entry : overrides.entrySet()) {
      String key = PREFIX + entry.getKey().name();
      List<Binding> list = entry.getValue();
      properties.setProperty(key + COUNT, Integer.toString(list.size()));
      for (int i = 0; i < list.size(); i++) {
        Binding binding = list.get(i);
        String scope = binding.scope() == Binding.Scope.WINDOW ? WINDOW : VIEW;
        properties.setProperty(key + "." + i, scope + " " + binding.text(false));
      }
    }
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ShortcutOverrides that && overrides.equals(that.overrides);
  }

  @Override
  public int hashCode() {
    return overrides.hashCode();
  }
}
