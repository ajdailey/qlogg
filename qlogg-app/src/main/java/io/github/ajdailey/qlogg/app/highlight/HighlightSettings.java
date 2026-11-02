package io.github.ajdailey.qlogg.app.highlight;

import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The highlighter sets, which of them are active, the color labels and the match highlight.
 * Immutable.
 *
 * <p>Active sets combine in set-list order and each set's rules keep their order; where rules
 * overlap, the rule higher in the combined list wins, so a set higher in the list wins over one
 * below it.
 *
 * @param sets the sets, in menu order, at most {@link #MAX_SETS}, with distinct ids
 * @param active ids of the active sets; ids without a set are dropped
 * @param labels {@link ColorLabel#COUNT} labels
 * @param match the match highlight
 */
public record HighlightSettings(
    List<HighlightSet> sets, List<String> active, List<ColorLabel> labels, MatchHighlight match) {
  /** Most sets kept. */
  public static final int MAX_SETS = 256;

  /** No sets, the default labels and match highlight. */
  public static final HighlightSettings DEFAULTS =
      new HighlightSettings(
          List.<HighlightSet>of(),
          List.<String>of(),
          ColorLabel.defaults(),
          MatchHighlight.DEFAULTS);

  /** Checks the components, drops unknown active ids and copies the lists. */
  public HighlightSettings {
    if (sets == null || active == null || labels == null || match == null) {
      throw new IllegalArgumentException("sets, active, labels and match are required");
    }
    if (sets.size() > MAX_SETS) {
      throw new IllegalArgumentException("too many sets");
    }
    if (labels.size() != ColorLabel.COUNT) {
      throw new IllegalArgumentException("labels must hold " + ColorLabel.COUNT);
    }
    List<String> ids = new ArrayList<String>();
    for (HighlightSet set : sets) {
      if (ids.contains(set.id())) {
        throw new IllegalArgumentException("duplicate set id");
      }
      ids.add(set.id());
    }
    List<String> kept = new ArrayList<String>();
    for (String id : active) {
      if (ids.contains(id) && !kept.contains(id)) {
        kept.add(id);
      }
    }
    sets = List.copyOf(sets);
    active = Collections.unmodifiableList(kept);
    labels = List.copyOf(labels);
  }

  /**
   * Returns the ids of the sets.
   *
   * @return ids in menu order
   */
  public List<String> ids() {
    List<String> ids = new ArrayList<String>();
    for (HighlightSet set : sets) {
      ids.add(set.id());
    }
    return ids;
  }

  /**
   * Reports whether a set is active.
   *
   * @param id the set's id
   * @return true when active
   */
  public boolean isActive(String id) {
    return active.contains(id);
  }

  /**
   * Returns the rules of the active sets in apply order: the bottom rule of the last active set
   * first, the top rule of the first active set last, so it wins.
   *
   * @return the rules
   */
  public List<HighlightRule> rulesInApplyOrder() {
    List<HighlightRule> rules = new ArrayList<HighlightRule>();
    for (int s = sets.size() - 1; s >= 0; s--) {
      HighlightSet set = sets.get(s);
      if (!active.contains(set.id())) {
        continue;
      }
      for (int r = set.rules().size() - 1; r >= 0; r--) {
        rules.add(set.rules().get(r));
      }
    }
    return rules;
  }

  /**
   * Returns a copy with other sets; active ids without a set are dropped.
   *
   * @param replacement the sets
   * @return the copy
   */
  public HighlightSettings withSets(List<HighlightSet> replacement) {
    return new HighlightSettings(replacement, active, labels, match);
  }

  /**
   * Returns a copy with other active sets.
   *
   * @param ids the active ids, in any order
   * @return the copy
   */
  public HighlightSettings withActive(List<String> ids) {
    return new HighlightSettings(sets, ids, labels, match);
  }

  /**
   * Returns a copy with a set switched on or off.
   *
   * @param id the set's id
   * @return the copy
   */
  public HighlightSettings toggle(String id) {
    List<String> ids = new ArrayList<String>(active);
    if (!ids.remove(id)) {
      ids.add(id);
    }
    return withActive(ids);
  }

  /**
   * Returns a copy with other labels.
   *
   * @param replacement {@link ColorLabel#COUNT} labels
   * @return the copy
   */
  public HighlightSettings withLabels(List<ColorLabel> replacement) {
    return new HighlightSettings(sets, active, replacement, match);
  }

  /**
   * Returns a copy with another match highlight.
   *
   * @param replacement the match highlight
   * @return the copy
   */
  public HighlightSettings withMatch(MatchHighlight replacement) {
    return new HighlightSettings(sets, active, labels, replacement);
  }

  /**
   * Returns a copy with the sets of another list whose ids are new appended, as an import does.
   *
   * @param imported the sets read
   * @return the copy; sets past {@link #MAX_SETS} are not added
   */
  public HighlightSettings withImported(List<HighlightSet> imported) {
    List<HighlightSet> merged = new ArrayList<HighlightSet>(sets);
    for (HighlightSet set : imported) {
      boolean known = false;
      for (HighlightSet existing : merged) {
        known |= existing.id().equals(set.id());
      }
      if (!known && merged.size() < MAX_SETS) {
        merged.add(set);
      }
    }
    return withSets(merged);
  }
}
