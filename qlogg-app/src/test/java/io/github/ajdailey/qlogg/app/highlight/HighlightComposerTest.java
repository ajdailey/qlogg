package io.github.ajdailey.qlogg.app.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.highlight.HighlightComposer.LastSearch;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.Highlighter;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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

  private static final TextEncoding UTF_8 = TextEncoding.of(StandardCharsets.UTF_8, 0);

  private static LastSearch search(
      String text,
      MatchMode mode,
      boolean caseSensitive,
      boolean wholeWord,
      boolean combining,
      boolean inverse) {
    return new LastSearch(text, mode, caseSensitive, wholeWord, combining, inverse, UTF_8, false);
  }

  private static LastSearch literal(String text) {
    return search(text, MatchMode.LITERAL, true, false, false, false);
  }

  private static LastSearch wholeWord(String text) {
    return search(text, MatchMode.LITERAL, true, true, false, false);
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
    HighlightRule regex = match(search("l.ne", MatchMode.REGEX, false, true, false, false));
    assertEquals("(?<!\\w)(?:l.ne)(?!\\w)", regex.pattern());
    assertFalse(regex.caseSensitive());
    HighlightRule quoted = match(search("\\Qa.b", MatchMode.REGEX, true, true, false, false));
    assertEquals("(?<!\\w)(?:\\Qa.b\\E)(?!\\w)", quoted.pattern());
  }

  /**
   * Regression: a case-folded literal search folds ASCII letters only, but its match highlight ran
   * with {@code UNICODE_CASE}, so a line with U+212A KELVIN SIGN showed the match color for {@code
   * k} without being a result.
   */
  @Test
  void aCaseFoldedLiteralHighlightFoldsAsciiOnlyAsTheSearchDoes() {
    for (boolean word : new boolean[] {false, true}) {
      HighlightRule rule = match(search("k", MatchMode.LITERAL, false, word, false, false));
      assertTrue(Highlighter.compile(rule).matcher("line K").find());
      assertFalse(Highlighter.compile(rule).matcher("line \u212a").find());
    }
    HighlightRule regex = match(search("k", MatchMode.REGEX, false, false, false, false));
    assertTrue(Highlighter.compile(regex).matcher("line \u212a").find());
    HighlightRule accented = match(search("\u00e9", MatchMode.LITERAL, false, false, false, false));
    assertTrue(Highlighter.compile(accented).matcher("line \u00c9").find());
  }

  /**
   * A case-folded literal folds ASCII letters only where the search ran it as a byte search, and
   * with {@code UNICODE_CASE} where the search ran it as a regex: UTF-16, UTF-32, a multi-byte
   * charset, or Hide ANSI Colors on.
   */
  @Test
  void aCaseFoldedLiteralHighlightFoldsAsTheSearchDidInEveryCharset() {
    String[] charsets = {"windows-1252", "UTF-8", "UTF-16LE", "UTF-32BE", "Shift_JIS"};
    for (String name : charsets) {
      TextEncoding encoding = TextEncoding.of(Charset.forName(name), 0);
      boolean bytewise = encoding.unitWidth() == 1 && !name.equals("Shift_JIS");
      for (boolean hideAnsi : new boolean[] {false, true}) {
        for (boolean word : new boolean[] {false, true}) {
          HighlightRule rule =
              match(
                  new LastSearch(
                      "k", MatchMode.LITERAL, false, word, false, false, encoding, hideAnsi));
          String at = name + " hideAnsi " + hideAnsi + " word " + word;
          assertTrue(Highlighter.compile(rule).matcher("line K").find(), at);
          assertEquals(
              !bytewise || hideAnsi, Highlighter.compile(rule).matcher("line \u212a").find(), at);
        }
      }
    }
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
    assertNull(match(search("hit", MatchMode.LITERAL, true, false, false, true)));
    assertNull(match(search("\"hit\" or \"line\"", MatchMode.LITERAL, true, false, true, false)));
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
