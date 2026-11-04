package io.github.ajdailey.qlogg.app.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.highlight.HighlightComposer.LastSearch;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link HighlightComposer} with no document: the cases of the controller-level {@code
 * HighlightCompositionTest}, plus labels last in their palette's colors and a disabled match
 * highlight.
 */
class HighlightComposerTest {
  private static final LastSearch NONE = LastSearch.NONE;
  private static final Rgb RED = Rgb.of(0xff0000);
  private static final Rgb BLUE = Rgb.of(0x0000ff);
  private static final HighlightSet FIRST =
      new HighlightSet(
          "mock-first",
          "First",
          List.of(
              HighlightRule.matchOnly("a1", MatchMode.LITERAL, null, RED),
              HighlightRule.matchOnly("a2", MatchMode.LITERAL, null, RED)));
  private static final HighlightSet SECOND =
      new HighlightSet(
          "mock-second",
          "Second",
          List.of(HighlightRule.matchOnly("b1", MatchMode.REGEX, BLUE, null)));
  private static final HighlightSettings BOTH =
      HighlightSettings.DEFAULTS
          .withSets(List.of(FIRST, SECOND))
          .withActive(List.of(SECOND.id(), FIRST.id()));

  private static LastSearch literal(String text) {
    return new LastSearch(text, MatchMode.LITERAL, true, false, false, false);
  }

  private static LastSearch wholeWord(String text) {
    return new LastSearch(text, MatchMode.LITERAL, true, true, false, false);
  }

  private static List<HighlightRule> compose(
      HighlightSettings settings, String autoSet, LastSearch search) {
    return HighlightComposer.compose(settings, autoSet, search, LabelState.EMPTY, Palette.LIGHT);
  }

  private static List<String> patterns(List<HighlightRule> rules) {
    String[] patterns = new String[rules.size()];
    for (int i = 0; i < patterns.length; i++) {
      patterns[i] = rules.get(i).pattern();
    }
    return List.of(patterns);
  }

  private static HighlightRule match(LastSearch search) {
    return HighlightComposer.matchRule(MatchHighlight.DEFAULTS, search, RED);
  }

  @Test
  void setsApplyLastSetAndLastRuleFirstThenTheMatch() {
    assertEquals(List.of("b1", "a2", "a1"), patterns(compose(BOTH, null, NONE)));
    List<HighlightRule> rules = compose(BOTH, null, literal("hit"));
    assertEquals(List.of("b1", "a2", "a1", "hit"), patterns(rules));
    HighlightRule match = rules.get(3);
    assertEquals(MatchMode.LITERAL, match.mode());
    assertTrue(match.caseSensitive());
    assertFalse(match.wholeLine());
    assertNull(match.foreground());
    assertEquals(Palette.LIGHT.match(), match.background());
    assertEquals(255, match.alpha());
    assertTrue(match.enabled());
  }

  @Test
  void wholeWordWrapsTheQuotedOrRegexPattern() {
    HighlightRule literal = match(wholeWord("a.b"));
    assertEquals("(?<!\\w)(?:\\Qa.b\\E)(?!\\w)", literal.pattern());
    assertEquals(MatchMode.REGEX, literal.mode());
    HighlightRule regex = match(new LastSearch("l.ne", MatchMode.REGEX, false, true, false, false));
    assertEquals("(?<!\\w)(?:l.ne)(?!\\w)", regex.pattern());
    assertFalse(regex.caseSensitive());
  }

  @Test
  void theMatchRuleKeepsColorAndVarianceFromItsSettings() {
    HighlightRule rule =
        HighlightComposer.matchRule(new MatchHighlight(true, BLUE, 20), literal("hit"), RED);
    assertEquals(BLUE, rule.background());
    assertEquals(20, rule.variance());
    assertEquals(RED, match(literal("hit")).background());
    List<HighlightRule> dark =
        HighlightComposer.compose(
            HighlightSettings.DEFAULTS, null, literal("hit"), LabelState.EMPTY, Palette.DARK);
    assertEquals(Palette.DARK.match(), dark.get(0).background());
  }

  @Test
  void aPatternPastTheCapHasNoMatchRule() {
    String longest = "q".repeat(HighlightRule.MAX_PATTERN_CHARS);
    assertEquals(longest, match(literal(longest)).pattern());
    // Whole word wraps the same text past the cap.
    assertNull(match(wholeWord(longest)));
    assertNull(match(literal(longest + "q")));
  }

  @Test
  void combinedAndInverseSearchesHaveNoMatchRule() {
    assertNull(match(new LastSearch("hit", MatchMode.LITERAL, true, false, false, true)));
    assertNull(
        match(new LastSearch("\"hit\" or \"line\"", MatchMode.LITERAL, true, false, true, false)));
    assertEquals("hit", match(literal("hit")).pattern());
  }

  @Test
  void noSearchOrADisabledMatchHighlightHasNoMatchRule() {
    assertNull(match(literal("")));
    assertNull(match(NONE));
    assertNull(
        HighlightComposer.matchRule(
            MatchHighlight.DEFAULTS.withEnabled(false), literal("hit"), RED));
  }

  @Test
  void anAutomaticSetReplacesTheActiveSetsWhileItsIdExists() {
    HighlightSettings first = BOTH.withActive(List.of(FIRST.id()));
    assertEquals(List.of("b1"), patterns(compose(first, SECOND.id(), NONE)));
    assertEquals(List.of("a2", "a1"), patterns(compose(first, "mock-missing", NONE)));
    assertEquals(List.of("a2", "a1"), patterns(compose(first, null, NONE)));
    HighlightSettings none = BOTH.withActive(List.<String>of());
    assertEquals(List.of("b1"), patterns(compose(none, SECOND.id(), NONE)));
  }

  @Test
  void labelsApplyLastInThePalettesColors() {
    LabelState labels = LabelState.EMPTY.apply(0, "flagword");
    List<HighlightRule> light =
        HighlightComposer.compose(BOTH, null, literal("hit"), labels, Palette.LIGHT);
    assertEquals(List.of("b1", "a2", "a1", "hit", "flagword"), patterns(light));
    HighlightRule label = light.get(4);
    assertEquals(MatchMode.LITERAL, label.mode());
    assertEquals(BOTH.labels().get(0).background(), label.background());
    List<HighlightRule> dark = HighlightComposer.compose(BOTH, null, NONE, labels, Palette.DARK);
    assertEquals(
        ColorLabel.forPalette(BOTH.labels(), true).get(0).background(),
        dark.get(dark.size() - 1).background());
  }
}
