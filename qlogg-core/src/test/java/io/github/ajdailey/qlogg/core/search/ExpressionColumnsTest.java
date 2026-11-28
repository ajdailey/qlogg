package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.ajdailey.qlogg.core.search.SearchExpression.Term;
import org.junit.jupiter.api.Test;

/** The search bar column applied to terms, the first column named, and the prefix written. */
class ExpressionColumnsTest {
  private static SearchExpression parse(String text) throws ExpressionException {
    return ExpressionParser.parse(text, MatchMode.LITERAL, true);
  }

  @Test
  void aColumnGoesToEveryTermWithoutItsOwn() throws ExpressionException {
    SearchExpression parsed = parse("\"a\" and not (message:\"b\" xor \"c\") or \"d\"");
    assertEquals(
        parse("level:\"a\" and not (message:\"b\" xor level:\"c\") or level:\"d\""),
        ExpressionColumns.within(parsed, "level"));
    assertSame(parsed, ExpressionColumns.within(parsed, ""));
    assertEquals(
        new Term("x y", MatchMode.LITERAL, "User agent"),
        ExpressionColumns.within(parse("x y"), "User agent"));
  }

  @Test
  void theFirstColumnIsInTextOrder() throws ExpressionException {
    assertNull(ExpressionColumns.first(parse("\"a\" or \"b\"")));
    assertEquals("level", ExpressionColumns.first(parse("\"a\" or not level:\"b\" and 3:\"c\"")));
    assertEquals("3", ExpressionColumns.first(parse("3:\"c\" or level:\"b\"")));
  }

  @Test
  void thePrefixParsesBackToTheTerm() throws ExpressionException {
    String[][] cases = {
      {"Level", "ERROR", "Level:\"ERROR\""},
      {"User agent", "Mock/1.0", "9:\"Mock/1.0\""},
      {"42", "x", "9:\"x\""},
      {"@mt", "say \"hi\"", "@mt:\"say \\\"hi\\\"\""},
      {"Level", "a\\\\d", "Level:\"a\\\\d\""},
    };
    for (String[] c : cases) {
      String written = ExpressionColumns.prefixed(c[0], 9, c[1]);
      assertEquals(c[2], written);
      Term term = (Term) parse(written);
      assertEquals(c[1], term.pattern());
    }
    assertNull(ExpressionColumns.prefixed("Level", 2, "ends\\"));
    assertNull(ExpressionColumns.prefixed("Level", 2, "a\\\"b"));
  }
}
