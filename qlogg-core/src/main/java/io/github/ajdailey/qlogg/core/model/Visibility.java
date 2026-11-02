package io.github.ajdailey.qlogg.core.model;

/** Which lines the filtered view lists, in the order {@code V} cycles through them. */
public enum Visibility {
  /** Marked lines and matching lines, key {@code 1}. */
  MARKS_AND_MATCHES,
  /** Marked lines only, key {@code 2}. */
  MARKS,
  /** Matching lines only, key {@code 3}. */
  MATCHES;

  /**
   * Returns the mode after this one, wrapping to the first.
   *
   * @return the next mode
   */
  public Visibility next() {
    Visibility[] all = values();
    return all[(ordinal() + 1) % all.length];
  }

  /**
   * Returns the mode before this one, wrapping to the last.
   *
   * @return the previous mode
   */
  public Visibility previous() {
    Visibility[] all = values();
    return all[(ordinal() + all.length - 1) % all.length];
  }
}
