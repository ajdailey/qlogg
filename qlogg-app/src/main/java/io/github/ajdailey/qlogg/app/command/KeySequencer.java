package io.github.ajdailey.qlogg.app.command;

/**
 * Resolves key presses in the log view to commands, including two-stroke sequences such as {@code
 * Ctrl+E Ctrl+T}. A stroke that starts a sequence sets a pending prefix; the next stroke completes
 * the sequence or, when unbound after that prefix (including {@code Esc}), cancels it.
 * Single-stroke window bindings are left to the menu accelerators. Not thread-safe: the UI thread
 * owns an instance.
 */
public final class KeySequencer {
  /** The result of one key press. */
  public sealed interface Result permits Run, Pending, Unbound {}

  /**
   * Run a command.
   *
   * @param command the command
   */
  public record Run(CommandId command) implements Result {}

  /**
   * A prefix is pending.
   *
   * @param prefix the first stroke
   */
  public record Pending(KeyStroke prefix) implements Result {}

  /** No binding; a pending prefix was canceled. */
  public record Unbound() implements Result {}

  private static final Unbound UNBOUND = new Unbound();

  private final Keymap keymap;
  private KeyStroke prefix;

  /**
   * Creates a sequencer.
   *
   * @param keymap the bindings
   */
  public KeySequencer(Keymap keymap) {
    this.keymap = keymap;
  }

  /**
   * Returns the pending prefix.
   *
   * @return the first stroke of a sequence, or null
   */
  public KeyStroke prefix() {
    return prefix;
  }

  /** Drops a pending prefix. */
  public void reset() {
    prefix = null;
  }

  /**
   * Resolves a key press.
   *
   * @param stroke the press; modifier keys alone are not passed in
   * @return what to do
   */
  public Result press(KeyStroke stroke) {
    if (prefix != null) {
      KeyStroke first = prefix;
      prefix = null;
      for (Binding binding : keymap.bindings()) {
        if (first.equals(binding.first()) && stroke.equals(binding.second())) {
          return new Run(binding.command());
        }
      }
      return UNBOUND;
    }
    boolean starts = false;
    for (Binding binding : keymap.bindings()) {
      if (!stroke.equals(binding.first())) {
        continue;
      }
      if (binding.second() != null) {
        starts = true;
      } else if (binding.scope() == Binding.Scope.LOG_VIEW) {
        return new Run(binding.command());
      }
    }
    if (starts) {
      prefix = stroke;
      return new Pending(stroke);
    }
    return UNBOUND;
  }
}
