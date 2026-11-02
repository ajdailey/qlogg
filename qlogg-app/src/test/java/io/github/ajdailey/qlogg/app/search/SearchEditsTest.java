package io.github.ajdailey.qlogg.app.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.core.search.ExpressionException;
import io.github.ajdailey.qlogg.core.search.ExpressionParser;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import org.junit.jupiter.api.Test;

class SearchEditsTest {
  private static final MatchMode REGEX = MatchMode.REGEX;
  private static final MatchMode LITERAL = MatchMode.LITERAL;

  private static void parses(SearchEdits.Edit edit, MatchMode mode) throws ExpressionException {
    ExpressionParser.parse(edit.text(), mode, edit.combining());
  }

  @Test
  void replaceEscapesForTheMode() {
    assertEquals(
        new SearchEdits.Edit("a\\.b\\(", false), SearchEdits.replace("a.b(", REGEX, false));
    assertEquals(new SearchEdits.Edit("a.b(", false), SearchEdits.replace("a.b(", LITERAL, false));
    assertEquals(
        new SearchEdits.Edit("\"say \\\"x\\\"\"", true),
        SearchEdits.replace("say \"x\"", LITERAL, true));
  }

  @Test
  void addBuildsAnAlternative() throws ExpressionException {
    assertEquals(
        new SearchEdits.Edit("err|a\\.b", false), SearchEdits.add("err", "a.b", REGEX, false));
    SearchEdits.Edit literal = SearchEdits.add("err", "a.b", LITERAL, false);
    assertEquals(new SearchEdits.Edit("\"err\" or \"a.b\"", true), literal);
    parses(literal, LITERAL);
    SearchEdits.Edit plain = SearchEdits.add("err", "x", REGEX, true);
    assertEquals("\"err\" or \"x\"", plain.text());
    parses(plain, REGEX);
    assertEquals(
        "\"a\" and \"b\" or \"x\"", SearchEdits.add("\"a\" and \"b\"", "x", REGEX, true).text());
    assertEquals(new SearchEdits.Edit("x", false), SearchEdits.add("", "x", REGEX, false));
  }

  @Test
  void excludeTurnsCombiningOn() throws ExpressionException {
    SearchEdits.Edit edit = SearchEdits.exclude("err|warn", "debug", REGEX, false);
    assertEquals(new SearchEdits.Edit("\"err|warn\" and not \"debug\"", true), edit);
    parses(edit, REGEX);
    assertEquals(
        "(\"a\" or \"b\") and not \"c\"",
        SearchEdits.exclude("\"a\" or \"b\"", "c", REGEX, true).text());
    SearchEdits.Edit empty = SearchEdits.exclude("", "c", REGEX, false);
    assertEquals(new SearchEdits.Edit("not \"c\"", true), empty);
    parses(empty, REGEX);
  }
}
