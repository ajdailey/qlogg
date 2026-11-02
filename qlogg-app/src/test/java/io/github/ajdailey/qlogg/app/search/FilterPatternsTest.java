package io.github.ajdailey.qlogg.app.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.core.search.ExpressionException;
import io.github.ajdailey.qlogg.core.search.ExpressionParser;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.SearchExpression;
import java.util.List;
import org.junit.jupiter.api.Test;

class FilterPatternsTest {
  private static final PredefinedFilter ERRORS =
      new PredefinedFilter("Errors", "ERROR|FATAL", MatchMode.REGEX);
  private static final PredefinedFilter DOTS =
      new PredefinedFilter("Dots", "a.b", MatchMode.LITERAL);
  private static final PredefinedFilter QUOTE =
      new PredefinedFilter("Quote", "say \"hi\"", MatchMode.LITERAL);

  @Test
  void composeJoinsAlternativesOrQuotedTerms() {
    assertEquals("ERROR|FATAL|a\\.b", FilterPatterns.compose(List.of(ERRORS, DOTS), false));
    assertEquals(
        "\"ERROR|FATAL\" or \"a\\.b\"", FilterPatterns.compose(List.of(ERRORS, DOTS), true));
    assertEquals("", FilterPatterns.compose(List.of(), false));
  }

  @Test
  void presentFollowsTheTextAsComposedOrEdited() {
    List<PredefinedFilter> all = List.of(ERRORS, DOTS, QUOTE);
    assertArrayEquals(
        new boolean[] {true, true, false}, FilterPatterns.present("ERROR|FATAL|a\\.b", all, false));
    assertArrayEquals(
        new boolean[] {false, true, false}, FilterPatterns.present("xa\\.b|a\\.b", all, false));
    assertArrayEquals(
        new boolean[] {false, false, false}, FilterPatterns.present("ERROR|FATALx", all, false));
    assertArrayEquals(
        new boolean[] {true, false, true},
        FilterPatterns.present("(\"ERROR|FATAL\") and not \"say \\\"hi\\\"\"", all, true));
  }

  @Test
  void aQuotedTermParsesBackToThePattern() throws ExpressionException {
    SearchExpression parsed =
        ExpressionParser.parse(FilterPatterns.render(QUOTE, true), MatchMode.REGEX, true);
    assertEquals(new SearchExpression.Term("say \"hi\"", MatchMode.REGEX), parsed);
  }
}
