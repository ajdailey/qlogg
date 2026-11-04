package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.highlight.HighlightSettings;
import io.github.ajdailey.qlogg.app.highlight.MatchHighlight;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.Highlighter;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rules {@link DocumentController#refreshHighlights} composes, recorded against today's output:
 * set order, the match rule for whole word and at the pattern cap, its absence for combined and
 * inverse searches, and an automatic set replacing the active sets.
 */
class HighlightCompositionTest {
  private static final SearchOptions LITERAL =
      new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, false, false);
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

  @TempDir Path temp;

  private ControllerFixture fixture;
  private DocumentController document;

  @BeforeEach
  void open() throws Exception {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    document = fixture.open(fixture.lines("h.log", 40));
  }

  @AfterEach
  void close() throws InterruptedException {
    fixture.close();
  }

  private List<String> patterns() {
    List<HighlightRule> rules = document.highlighter().rules();
    String[] patterns = new String[rules.size()];
    for (int i = 0; i < patterns.length; i++) {
      patterns[i] = rules.get(i).pattern();
    }
    return List.of(patterns);
  }

  /** Runs a search to its end; the match rule follows the state the search reports. */
  private void search(String text, SearchOptions options) throws InterruptedException {
    document.search().run(text, options);
    fixture.runUntilEnded(document.search());
  }

  private HighlightRule matchRule() {
    List<HighlightRule> rules = document.highlighter().rules();
    return rules.isEmpty() ? null : rules.get(rules.size() - 1);
  }

  @Test
  void setsApplyLastSetAndLastRuleFirstThenTheMatch() throws Exception {
    fixture.application.setHighlightSettings(
        HighlightSettings.DEFAULTS
            .withSets(List.of(FIRST, SECOND))
            .withActive(List.of(SECOND.id(), FIRST.id())));
    assertEquals(List.of("b1", "a2", "a1"), patterns());
    search("hit", LITERAL);
    assertEquals(List.of("b1", "a2", "a1", "hit"), patterns());
    HighlightRule match = matchRule();
    assertEquals(MatchMode.LITERAL, match.mode());
    assertTrue(match.caseSensitive());
    assertFalse(match.wholeLine());
    assertNull(match.foreground());
    assertEquals(Palette.LIGHT.match(), match.background());
    assertEquals(255, match.alpha());
    assertSame(document.highlighter(), document.filtered().highlighter());
  }

  @Test
  void wholeWordWrapsTheQuotedOrRegexPattern() throws Exception {
    search("a.b", LITERAL.withWholeWord(true));
    assertEquals("(?<!\\w)(?:\\Qa.b\\E)(?!\\w)", matchRule().pattern());
    assertEquals(MatchMode.REGEX, matchRule().mode());
    search("l.ne", LITERAL.withWholeWord(true).withMode(MatchMode.REGEX).withCaseSensitive(false));
    assertEquals("(?<!\\w)(?:l.ne)(?!\\w)", matchRule().pattern());
    assertFalse(matchRule().caseSensitive());
  }

  @Test
  void theMatchRuleKeepsColorAndVarianceFromItsSettings() throws Exception {
    HighlightSettings settings = fixture.application.highlightSettings();
    fixture.application.setHighlightSettings(
        settings.withMatch(new MatchHighlight(true, BLUE, 20)));
    search("hit", LITERAL);
    assertEquals(BLUE, matchRule().background());
    assertEquals(20, matchRule().variance());
  }

  @Test
  void aPatternPastTheCapHasNoMatchRule() throws Exception {
    String longest = "q".repeat(HighlightRule.MAX_PATTERN_CHARS);
    search(longest, LITERAL);
    assertEquals(SearchViewState.Phase.DONE, document.search().state().phase());
    assertEquals(longest, matchRule().pattern());
    // Whole word wraps the same text past the cap.
    search(longest, LITERAL.withWholeWord(true));
    assertSame(Highlighter.NONE, document.highlighter());
    search(longest + "q", LITERAL);
    assertSame(Highlighter.NONE, document.highlighter());
  }

  @Test
  void combinedAndInverseSearchesHaveNoMatchRule() throws Exception {
    search("hit", LITERAL.withInverse(true));
    assertSame(Highlighter.NONE, document.highlighter());
    search("\"hit\" or \"line\"", LITERAL.withCombining(true));
    assertEquals(SearchViewState.Phase.DONE, document.search().state().phase());
    assertSame(Highlighter.NONE, document.highlighter());
    search("hit", LITERAL);
    assertEquals(List.of("hit"), patterns());
  }

  @Test
  void anAutomaticSetReplacesTheActiveSetsUntilCleared() throws Exception {
    fixture.application.setHighlightSettings(
        HighlightSettings.DEFAULTS
            .withSets(List.of(FIRST, SECOND))
            .withActive(List.of(FIRST.id())));
    document.setAutoHighlightSet(SECOND.id());
    assertEquals(List.of("b1"), patterns());
    document.setAutoHighlightSet("mock-missing");
    assertEquals(List.of("a2", "a1"), patterns());
    document.setAutoHighlightSet(null);
    assertEquals(List.of("a2", "a1"), patterns());
    fixture.application.setHighlightSettings(
        fixture.application.highlightSettings().withActive(List.of()));
    document.setAutoHighlightSet(SECOND.id());
    assertEquals(List.of("b1"), patterns());
  }
}
