package io.github.ajdailey.qlogg.core.model;

/**
 * A stream selection from the anchor to the caret. The selected text is {@code [start(), end())};
 * an empty selection is a caret only.
 *
 * @param anchor where the selection started
 * @param caret where it ends; its line is the current line
 */
public record Selection(TextPosition anchor, TextPosition caret) {
  /**
   * Creates an empty selection.
   *
   * @param position the caret
   * @return the selection
   */
  public static Selection caretAt(TextPosition position) {
    return new Selection(position, position);
  }

  /**
   * Returns the earlier end.
   *
   * @return anchor or caret
   */
  public TextPosition start() {
    return anchor.compareTo(caret) <= 0 ? anchor : caret;
  }

  /**
   * Returns the later end, exclusive.
   *
   * @return anchor or caret
   */
  public TextPosition end() {
    return anchor.compareTo(caret) <= 0 ? caret : anchor;
  }

  /**
   * Reports whether nothing is selected.
   *
   * @return true when anchor equals caret
   */
  public boolean isEmpty() {
    return anchor.equals(caret);
  }
}
