package io.github.ajdailey.qlogg.core.model;

/**
 * What a highlighter rule does to a line appended to an open document, besides coloring it.
 * Immutable.
 *
 * @param stopFollow turns follow off for the document
 * @param setMark marks the line
 * @param commentTemplate the comment a set mark takes, expanded by {@link CommentTemplate}; empty
 *     for none, at most {@link #MAX_TEMPLATE_CHARS}
 * @param quiet the line does not count toward the tab's data dot
 */
public record RuleTrigger(
    boolean stopFollow, boolean setMark, String commentTemplate, boolean quiet) {
  /** No action. */
  public static final RuleTrigger NONE = new RuleTrigger(false, false, "", false);

  /** Longest comment template. */
  public static final int MAX_TEMPLATE_CHARS = 1000;

  /** Checks the components. */
  public RuleTrigger {
    if (commentTemplate == null) {
      throw new IllegalArgumentException("commentTemplate is required");
    }
    if (commentTemplate.length() > MAX_TEMPLATE_CHARS) {
      throw new IllegalArgumentException("comment template is too long");
    }
  }

  /**
   * Reports whether the rule takes any action.
   *
   * @return true when an action is on
   */
  public boolean any() {
    return stopFollow || setMark || quiet;
  }
}
