package io.github.ajdailey.qlogg.core.model;

import java.util.List;
import java.util.UUID;

/**
 * A named, ordered list of highlighters. Where rules overlap on a line, the rule higher in the list
 * wins. Immutable.
 *
 * @param id identifies the set across export and import; import adds only sets with a new id
 * @param name the label shown
 * @param rules the rules, top first
 */
public record HighlightSet(String id, String name, List<HighlightRule> rules) {
  /** Most rules in a set. */
  public static final int MAX_RULES = 1000;

  /** Longest id or name. */
  public static final int MAX_NAME_CHARS = 256;

  /** Checks the components and copies the list. */
  public HighlightSet {
    if (id == null || name == null || rules == null) {
      throw new IllegalArgumentException("id, name and rules are required");
    }
    if (id.isBlank() || id.length() > MAX_NAME_CHARS || name.length() > MAX_NAME_CHARS) {
      throw new IllegalArgumentException("id or name is blank or too long");
    }
    if (rules.size() > MAX_RULES) {
      throw new IllegalArgumentException("too many rules");
    }
    rules = List.copyOf(rules);
  }

  /**
   * Creates an empty set with a random id.
   *
   * @param name the label
   * @return the set
   */
  public static HighlightSet create(String name) {
    return new HighlightSet(UUID.randomUUID().toString(), name, List.of());
  }

  /**
   * Returns a copy with other rules.
   *
   * @param replacement the rules, top first
   * @return the copy
   */
  public HighlightSet withRules(List<HighlightRule> replacement) {
    return new HighlightSet(id, name, replacement);
  }

  /**
   * Returns a copy with another name.
   *
   * @param label the name
   * @return the copy
   */
  public HighlightSet withName(String label) {
    return new HighlightSet(id, label, rules);
  }
}
