package io.github.ajdailey.qlogg.app.highlight;

import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The texts each color label paints in one document, and the label {@code Ctrl+D} used last. Labels
 * paint their texts as case-sensitive literal match-only rules. A text has at most one label.
 * Immutable; each operation returns the next state.
 *
 * <p>Cost ({@link #retainedBytes}): 2 bytes per char of the texts plus {@link #BYTES_PER_TEXT} per
 * text. At most {@link #MAX_TEXTS} texts are kept.
 *
 * @param texts per label, {@link ColorLabel#COUNT} lists of distinct texts
 * @param current the label {@code Ctrl+D} used last, or -1
 */
public record LabelState(List<List<String>> texts, int current) {
  /** No texts. */
  public static final LabelState EMPTY = new LabelState(emptyLists(), -1);

  /** Most texts over all labels. */
  public static final int MAX_TEXTS = 256;

  /** Bytes charged per text beyond its chars. */
  public static final int BYTES_PER_TEXT = 56;

  /** Checks the components and copies the lists. */
  public LabelState {
    if (texts == null || texts.size() != ColorLabel.COUNT) {
      throw new IllegalArgumentException("texts must hold " + ColorLabel.COUNT + " lists");
    }
    List<List<String>> copy = new ArrayList<List<String>>();
    for (List<String> list : texts) {
      copy.add(List.copyOf(list));
    }
    texts = Collections.unmodifiableList(copy);
  }

  private static List<List<String>> emptyLists() {
    List<List<String>> lists = new ArrayList<List<String>>();
    for (int i = 0; i < ColorLabel.COUNT; i++) {
      lists.add(List.<String>of());
    }
    return lists;
  }

  /**
   * Returns the label of a text.
   *
   * @param text the text
   * @return 0 to 8, or -1 when it has none
   */
  public int labelOf(String text) {
    for (int i = 0; i < texts.size(); i++) {
      if (texts.get(i).contains(text)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Counts the texts over all labels.
   *
   * @return texts
   */
  public int size() {
    int n = 0;
    for (List<String> list : texts) {
      n += list.size();
    }
    return n;
  }

  /**
   * Returns the heap the texts hold, by the formula in the class description.
   *
   * @return bytes
   */
  public long retainedBytes() {
    long bytes = 0;
    for (List<String> list : texts) {
      for (String text : list) {
        bytes += BYTES_PER_TEXT + 2L * text.length();
      }
    }
    return bytes;
  }

  /**
   * Applies a numbered label: a text with another label or none moves to it; a text that already
   * has it loses it.
   *
   * @param label 0 to 8
   * @param text the selected text, not empty
   * @return the next state; this state when {@link #MAX_TEXTS} texts are kept already
   */
  public LabelState apply(int label, String text) {
    int had = labelOf(text);
    List<List<String>> next = without(text);
    if (had != label) {
      if (had < 0 && size() >= MAX_TEXTS) {
        return this;
      }
      next.get(label).add(text);
    }
    return new LabelState(next, current);
  }

  /**
   * Applies the next label of the cycle: the first cycle label after the one used last, or the
   * first after the end. A text that has any label loses it instead, and the cycle still advances.
   *
   * @param text the selected text, not empty
   * @param labels the label settings, which name the cycle
   * @return the next state; this state when no label is in the cycle
   */
  public LabelState next(String text, List<ColorLabel> labels) {
    int chosen = -1;
    for (int i = 0; i < labels.size() && chosen < 0; i++) {
      if (labels.get(i).cycle() && i > current) {
        chosen = i;
      }
    }
    for (int i = 0; i < labels.size() && chosen < 0; i++) {
      if (labels.get(i).cycle()) {
        chosen = i;
      }
    }
    if (chosen < 0) {
      return this;
    }
    int had = labelOf(text);
    List<List<String>> next = without(text);
    if (had < 0) {
      if (size() >= MAX_TEXTS) {
        return this;
      }
      next.get(chosen).add(text);
    }
    return new LabelState(next, chosen);
  }

  /**
   * Removes a text's label.
   *
   * @param text the text
   * @return the next state
   */
  public LabelState remove(String text) {
    return new LabelState(without(text), current);
  }

  /**
   * Returns the rules that paint the texts, in apply order: label 1 first, so label 9 wins where
   * texts overlap.
   *
   * @param labels the label settings
   * @return one rule per text
   */
  public List<HighlightRule> rules(List<ColorLabel> labels) {
    List<HighlightRule> rules = new ArrayList<HighlightRule>();
    for (int i = 0; i < texts.size() && i < labels.size(); i++) {
      ColorLabel label = labels.get(i);
      for (String text : texts.get(i)) {
        rules.add(
            HighlightRule.matchOnly(
                text, MatchMode.LITERAL, label.foreground(), label.background()));
      }
    }
    return rules;
  }

  private List<List<String>> without(String text) {
    List<List<String>> next = new ArrayList<List<String>>();
    for (List<String> list : texts) {
      List<String> kept = new ArrayList<String>(list);
      kept.remove(text);
      next.add(kept);
    }
    return next;
  }
}
