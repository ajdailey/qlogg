package io.github.ajdailey.qlogg.app.command;

/**
 * One key binding: a stroke, or a two-stroke sequence such as {@code Ctrl+E Ctrl+T}.
 *
 * @param command the command run
 * @param first the first stroke
 * @param second the second stroke, or null for a single stroke
 * @param scope where the binding applies
 */
public record Binding(CommandId command, KeyStroke first, KeyStroke second, Scope scope) {
  /** Where a binding applies. */
  public enum Scope {
    /**
     * Anywhere in the main window; a single stroke here is the menu item's accelerator. Two-stroke
     * window bindings are not supported.
     */
    WINDOW,
    /** Only while the log view has focus; dispatched by the view, so a text field never sees it. */
    LOG_VIEW
  }

  /** Checks the components. */
  public Binding {
    if (command == null || first == null || scope == null) {
      throw new IllegalArgumentException("command, first stroke and scope are required");
    }
    if (scope == Scope.WINDOW && second != null) {
      throw new IllegalArgumentException("A window binding is a single stroke");
    }
  }

  /**
   * Returns the binding with its strokes as a platform's keyboard produces them.
   *
   * @param mac true for macOS
   * @return this binding, or one with {@link KeyStroke#CONTROL} folded into {@link
   *     KeyStroke#PRIMARY}
   */
  public Binding forPlatform(boolean mac) {
    KeyStroke folded = first.forPlatform(mac);
    KeyStroke foldedSecond = second == null ? null : second.forPlatform(mac);
    if (folded == first && foldedSecond == second) {
      return this;
    }
    return new Binding(command, folded, foldedSecond, scope);
  }

  /**
   * Returns the keys as the platform writes them.
   *
   * @param mac true for macOS names
   * @return for example {@code Ctrl+E Ctrl+T}
   */
  public String text(boolean mac) {
    return second == null ? first.text(mac) : first.text(mac) + " " + second.text(mac);
  }
}
