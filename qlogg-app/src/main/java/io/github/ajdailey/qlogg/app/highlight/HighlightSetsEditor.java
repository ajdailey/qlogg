package io.github.ajdailey.qlogg.app.highlight;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.model.CommentTemplate;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.Highlighter;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.PatternSyntaxException;

/**
 * The editable copy of the highlighter sets that Configure Highlighters holds: sets and their rules
 * by index, at most {@link HighlightSettings#MAX_SETS} sets and {@link HighlightSet#MAX_RULES}
 * rules per set. Each edit returns the index to select next; an index out of range changes nothing.
 * Removing a set also drops it from the active ids. Not thread-safe.
 */
public final class HighlightSetsEditor {
  /** Background of a new rule. */
  public static final Rgb NEW_RULE_BACKGROUND = Rgb.of(0xffe680);

  private final List<HighlightSet> sets;
  private final List<String> active;

  /**
   * Creates an editor over a copy of the settings' sets and active ids.
   *
   * @param settings the settings to edit
   */
  public HighlightSetsEditor(HighlightSettings settings) {
    sets = new ArrayList<HighlightSet>(settings.sets());
    active = new ArrayList<String>(settings.active());
  }

  /**
   * Returns the sets.
   *
   * @return an unmodifiable view, in menu order
   */
  public List<HighlightSet> sets() {
    return Collections.unmodifiableList(sets);
  }

  /**
   * Returns a set's rules.
   *
   * @param set the set's index
   * @return the rules; empty when the index is out of range
   */
  public List<HighlightRule> rules(int set) {
    return hasSet(set) ? sets.get(set).rules() : List.<HighlightRule>of();
  }

  /**
   * Appends a set named {@link UiText#HIGHLIGHTERS_NEW_SET}.
   *
   * @return its index; -1 at {@link HighlightSettings#MAX_SETS}
   */
  public int addSet() {
    if (sets.size() >= HighlightSettings.MAX_SETS) {
      return -1;
    }
    sets.add(HighlightSet.create(UiText.HIGHLIGHTERS_NEW_SET.text()));
    return sets.size() - 1;
  }

  /**
   * Removes a set and its active id.
   *
   * @param set the set's index
   * @return the index now at that place, else the last set's; -1 when no set is left or the index
   *     is out of range
   */
  public int removeSet(int set) {
    if (!hasSet(set)) {
      return -1;
    }
    active.remove(sets.remove(set).id());
    return Math.min(set, sets.size() - 1);
  }

  /**
   * Moves a set one place.
   *
   * @param set the set's index
   * @param delta -1 up, 1 down
   * @return the set's new index; unchanged at either end
   */
  public int moveSet(int set, int delta) {
    int to = set + delta;
    if (!hasSet(set) || to < 0 || to >= sets.size()) {
      return set;
    }
    sets.add(to, sets.remove(set));
    return to;
  }

  /**
   * Renames a set; the name is stripped.
   *
   * @param set the set's index
   * @param name the name
   */
  public void renameSet(int set, String name) {
    if (hasSet(set)) {
      sets.set(set, sets.get(set).withName(name.strip()));
    }
  }

  /**
   * Appends a match-only regex rule with the pattern {@link UiText#HIGHLIGHTERS_NEW_RULE} and
   * {@link #NEW_RULE_BACKGROUND}.
   *
   * @param set the set's index
   * @return the rule's index; -1 at {@link HighlightSet#MAX_RULES} or when the set index is out of
   *     range
   */
  public int addRule(int set) {
    if (!hasSet(set) || sets.get(set).rules().size() >= HighlightSet.MAX_RULES) {
      return -1;
    }
    List<HighlightRule> rules = new ArrayList<HighlightRule>(sets.get(set).rules());
    rules.add(
        HighlightRule.matchOnly(
            UiText.HIGHLIGHTERS_NEW_RULE.text(), MatchMode.REGEX, null, NEW_RULE_BACKGROUND));
    replaceRules(set, rules);
    return rules.size() - 1;
  }

  /**
   * Removes a rule.
   *
   * @param set the set's index
   * @param rule the rule's index
   * @return the index now at that place, else the last rule's; -1 when no rule is left or an index
   *     is out of range
   */
  public int removeRule(int set, int rule) {
    if (!hasRule(set, rule)) {
      return -1;
    }
    List<HighlightRule> rules = new ArrayList<HighlightRule>(sets.get(set).rules());
    rules.remove(rule);
    replaceRules(set, rules);
    return Math.min(rule, rules.size() - 1);
  }

  /**
   * Moves a rule one place; a higher rule wins.
   *
   * @param set the set's index
   * @param rule the rule's index
   * @param delta -1 up, 1 down
   * @return the rule's new index; unchanged at either end
   */
  public int moveRule(int set, int rule, int delta) {
    int to = rule + delta;
    if (!hasRule(set, rule) || to < 0 || to >= sets.get(set).rules().size()) {
      return rule;
    }
    List<HighlightRule> rules = new ArrayList<HighlightRule>(sets.get(set).rules());
    rules.add(to, rules.remove(rule));
    replaceRules(set, rules);
    return to;
  }

  /**
   * Replaces a rule, valid or not.
   *
   * @param set the set's index
   * @param index the rule's index
   * @param rule the edited rule
   * @return {@link #validate} of the rule
   */
  public String setRule(int set, int index, HighlightRule rule) {
    if (hasRule(set, index)) {
      List<HighlightRule> rules = new ArrayList<HighlightRule>(sets.get(set).rules());
      rules.set(index, rule);
      replaceRules(set, rules);
    }
    return validate(rule);
  }

  /**
   * Checks that a rule's pattern and its comment template's regexes compile.
   *
   * @param rule the rule
   * @return null when both compile, else {@link UiText#RULE_INVALID} with the pattern error or
   *     {@link UiText#RULE_TEMPLATE_INVALID} with the template error
   */
  public static String validate(HighlightRule rule) {
    try {
      Highlighter.compile(rule);
    } catch (PatternSyntaxException e) {
      return UiText.RULE_INVALID.format(e.getDescription());
    }
    String template = CommentTemplate.parse(rule.trigger().commentTemplate()).invalid();
    return template == null ? null : UiText.RULE_TEMPLATE_INVALID.format(template);
  }

  /**
   * Appends the imported sets whose ids are new, up to {@link HighlightSettings#MAX_SETS}.
   *
   * @param imported the sets read
   * @return the number of sets added
   */
  public int importSets(List<HighlightSet> imported) {
    int before = sets.size();
    List<HighlightSet> merged =
        HighlightSettings.DEFAULTS.withSets(sets).withImported(imported).sets();
    sets.clear();
    sets.addAll(merged);
    return sets.size() - before;
  }

  /**
   * Returns the edited settings.
   *
   * @param labels {@link ColorLabel#COUNT} labels
   * @param match the match highlight
   * @return the settings with the edited sets and the remaining active ids
   */
  public HighlightSettings result(List<ColorLabel> labels, MatchHighlight match) {
    return new HighlightSettings(sets, active, labels, match);
  }

  private boolean hasSet(int set) {
    return set >= 0 && set < sets.size();
  }

  private boolean hasRule(int set, int rule) {
    return hasSet(set) && rule >= 0 && rule < sets.get(set).rules().size();
  }

  private void replaceRules(int set, List<HighlightRule> rules) {
    sets.set(set, sets.get(set).withRules(rules));
  }
}
