package io.github.ajdailey.qlogg.app.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class HighlightSetsEditorTest {
  private static final Rgb RED = Rgb.of(0xff0000);

  private static HighlightSet set(String id, String... patterns) {
    List<HighlightRule> rules = new ArrayList<HighlightRule>();
    for (String pattern : patterns) {
      rules.add(HighlightRule.matchOnly(pattern, MatchMode.LITERAL, null, RED));
    }
    return new HighlightSet(id, id, rules);
  }

  private static HighlightSettings settings(HighlightSet... sets) {
    List<String> ids = new ArrayList<String>();
    for (HighlightSet set : sets) {
      ids.add(set.id());
    }
    return HighlightSettings.DEFAULTS.withSets(List.of(sets)).withActive(ids);
  }

  private static List<String> ids(HighlightSetsEditor editor) {
    List<String> ids = new ArrayList<String>();
    for (HighlightSet set : editor.sets()) {
      ids.add(set.id());
    }
    return ids;
  }

  private static List<String> patterns(HighlightSetsEditor editor, int set) {
    List<String> patterns = new ArrayList<String>();
    for (HighlightRule rule : editor.rules(set)) {
      patterns.add(rule.pattern());
    }
    return patterns;
  }

  private static HighlightSettings result(HighlightSetsEditor editor) {
    return editor.result(ColorLabel.defaults(), MatchHighlight.DEFAULTS);
  }

  @Test
  void editsACopyOfTheSettings() {
    final HighlightSettings original = settings(set("mock-a", "x"));
    final HighlightSetsEditor editor = new HighlightSetsEditor(original);
    editor.addSet();
    editor.addRule(0);
    assertEquals(1, original.sets().size());
    assertEquals(1, original.sets().get(0).rules().size());
    assertThrows(
        UnsupportedOperationException.class,
        new Executable() {
          @Override
          public void execute() {
            editor.sets().clear();
          }
        });
  }

  @Test
  void addSetAppendsANamedSetUpToTheLimit() {
    HighlightSetsEditor editor = new HighlightSetsEditor(HighlightSettings.DEFAULTS);
    assertEquals(0, editor.addSet());
    assertEquals(UiText.HIGHLIGHTERS_NEW_SET.text(), editor.sets().get(0).name());
    for (int i = 1; i < HighlightSettings.MAX_SETS; i++) {
      assertEquals(i, editor.addSet());
    }
    assertEquals(-1, editor.addSet());
    assertEquals(HighlightSettings.MAX_SETS, editor.sets().size());
  }

  @Test
  void removeSetSelectsTheNextOrTheNewLastAndDropsItsActiveId() {
    HighlightSetsEditor editor =
        new HighlightSetsEditor(settings(set("mock-a"), set("mock-b"), set("mock-c")));
    assertEquals(0, editor.removeSet(0));
    assertEquals(List.of("mock-b", "mock-c"), ids(editor));
    assertEquals(0, editor.removeSet(1));
    assertEquals(List.of("mock-b"), ids(editor));
    assertEquals(List.of("mock-b"), result(editor).active());
    assertEquals(-1, editor.removeSet(1));
    assertEquals(-1, editor.removeSet(-1));
    assertEquals(-1, editor.removeSet(0));
    assertTrue(editor.sets().isEmpty());
    assertTrue(result(editor).active().isEmpty());
  }

  @Test
  void moveSetStopsAtBothEnds() {
    HighlightSetsEditor editor =
        new HighlightSetsEditor(settings(set("mock-a"), set("mock-b"), set("mock-c")));
    assertEquals(0, editor.moveSet(0, -1));
    assertEquals(2, editor.moveSet(2, 1));
    assertEquals(List.of("mock-a", "mock-b", "mock-c"), ids(editor));
    assertEquals(1, editor.moveSet(0, 1));
    assertEquals(List.of("mock-b", "mock-a", "mock-c"), ids(editor));
    assertEquals(2, editor.moveSet(1, 1));
    assertEquals(List.of("mock-b", "mock-c", "mock-a"), ids(editor));
    assertEquals(1, editor.moveSet(2, -1));
    assertEquals(List.of("mock-b", "mock-a", "mock-c"), ids(editor));
    assertEquals(-1, editor.moveSet(-1, 1));
  }

  @Test
  void renameStripsTheName() {
    HighlightSetsEditor editor = new HighlightSetsEditor(settings(set("mock-a")));
    editor.renameSet(0, "  Errors ");
    assertEquals("Errors", editor.sets().get(0).name());
    editor.renameSet(1, "ignored");
    assertEquals(1, editor.sets().size());
  }

  @Test
  void addRuleAppendsTheDefaultRuleUpToTheLimit() {
    HighlightSetsEditor editor = new HighlightSetsEditor(settings(set("mock-a", "x")));
    assertEquals(1, editor.addRule(0));
    HighlightRule added = editor.rules(0).get(1);
    assertEquals(UiText.HIGHLIGHTERS_NEW_RULE.text(), added.pattern());
    assertEquals(MatchMode.REGEX, added.mode());
    assertEquals(HighlightSetsEditor.NEW_RULE_BACKGROUND, added.background());
    assertEquals(HighlightSetsEditor.NEW_RULE_FOREGROUND, added.foreground());
    for (int i = 2; i < HighlightSet.MAX_RULES; i++) {
      assertEquals(i, editor.addRule(0));
    }
    assertEquals(-1, editor.addRule(0));
    assertEquals(HighlightSet.MAX_RULES, editor.rules(0).size());
    assertEquals(-1, editor.addRule(1));
    assertEquals(-1, editor.addRule(-1));
  }

  @Test
  void removeRuleSelectsTheNextOrTheNewLast() {
    HighlightSetsEditor editor = new HighlightSetsEditor(settings(set("mock-a", "x", "y", "z")));
    assertEquals(0, editor.removeRule(0, 0));
    assertEquals(List.of("y", "z"), patterns(editor, 0));
    assertEquals(0, editor.removeRule(0, 1));
    assertEquals(List.of("y"), patterns(editor, 0));
    assertEquals(-1, editor.removeRule(0, 1));
    assertEquals(-1, editor.removeRule(1, 0));
    assertEquals(-1, editor.removeRule(0, 0));
    assertTrue(editor.rules(0).isEmpty());
  }

  @Test
  void moveRuleStopsAtBothEnds() {
    HighlightSetsEditor editor = new HighlightSetsEditor(settings(set("mock-a", "x", "y", "z")));
    assertEquals(0, editor.moveRule(0, 0, -1));
    assertEquals(2, editor.moveRule(0, 2, 1));
    assertEquals(List.of("x", "y", "z"), patterns(editor, 0));
    assertEquals(1, editor.moveRule(0, 0, 1));
    assertEquals(List.of("y", "x", "z"), patterns(editor, 0));
    assertEquals(2, editor.moveRule(0, 1, 1));
    assertEquals(List.of("y", "z", "x"), patterns(editor, 0));
    assertEquals(0, editor.moveRule(0, 1, -1));
    assertEquals(List.of("z", "y", "x"), patterns(editor, 0));
    assertEquals(3, editor.moveRule(0, 3, -1));
    assertEquals(List.of("z", "y", "x"), patterns(editor, 0));
  }

  @Test
  void setRuleKeepsAnInvalidRuleAndReturnsTheMessage() {
    HighlightSetsEditor editor = new HighlightSetsEditor(settings(set("mock-a", "x")));
    HighlightRule bad = HighlightRule.matchOnly("(open", MatchMode.REGEX, null, RED);
    String message = editor.setRule(0, 0, bad);
    assertTrue(message.startsWith(UiText.RULE_INVALID.format("")), message);
    assertTrue(message.length() > UiText.RULE_INVALID.format("").length(), message);
    assertEquals(bad, editor.rules(0).get(0));
    HighlightRule literal = HighlightRule.matchOnly("(open", MatchMode.LITERAL, null, RED);
    assertNull(editor.setRule(0, 0, literal));
    assertEquals(literal, editor.rules(0).get(0));
    assertNull(HighlightSetsEditor.validate(literal));
  }

  @Test
  void importAddsOnlyNewIdsAndCountsThem() {
    HighlightSetsEditor editor = new HighlightSetsEditor(settings(set("mock-a", "x")));
    int added = editor.importSets(List.of(set("mock-a", "other"), set("mock-b"), set("mock-c")));
    assertEquals(2, added);
    assertEquals(List.of("mock-a", "mock-b", "mock-c"), ids(editor));
    assertEquals(List.of("x"), patterns(editor, 0));
    assertEquals(0, editor.importSets(List.of(set("mock-b"))));
  }

  @Test
  void importStopsAtTheSetLimit() {
    HighlightSetsEditor editor = new HighlightSetsEditor(HighlightSettings.DEFAULTS);
    for (int i = 1; i < HighlightSettings.MAX_SETS; i++) {
      editor.addSet();
    }
    assertEquals(1, editor.importSets(List.of(set("mock-a"), set("mock-b"))));
    assertEquals(HighlightSettings.MAX_SETS, editor.sets().size());
  }

  @Test
  void resultCarriesTheEditsLabelsAndMatch() {
    HighlightSetsEditor editor = new HighlightSetsEditor(settings(set("mock-a"), set("mock-b")));
    editor.moveSet(1, -1);
    List<ColorLabel> labels = new ArrayList<ColorLabel>(ColorLabel.defaults());
    labels.set(0, new ColorLabel("Mock", null, RED, false));
    MatchHighlight match = new MatchHighlight(false, RED, 5);
    HighlightSettings result = editor.result(labels, match);
    assertEquals(List.of("mock-b", "mock-a"), result.ids());
    assertEquals(List.of("mock-a", "mock-b"), result.active());
    assertEquals("Mock", result.labels().get(0).name());
    assertEquals(match, result.match());
  }
}
