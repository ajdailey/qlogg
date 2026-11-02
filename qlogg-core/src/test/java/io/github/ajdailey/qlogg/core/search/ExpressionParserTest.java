package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qlogg.core.search.SearchExpression.And;
import io.github.ajdailey.qlogg.core.search.SearchExpression.Not;
import io.github.ajdailey.qlogg.core.search.SearchExpression.Or;
import io.github.ajdailey.qlogg.core.search.SearchExpression.Term;
import io.github.ajdailey.qlogg.core.search.SearchExpression.Xor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ExpressionParserTest {
  private static final Term A = term("a");
  private static final Term B = term("b");
  private static final Term C = term("c");

  private static Term term(String pattern) {
    return new Term(pattern, MatchMode.REGEX);
  }

  private static SearchExpression parse(String text) throws ExpressionException {
    return ExpressionParser.parse(text, MatchMode.REGEX, true);
  }

  @Test
  void precedenceIsNotThenAndThenXorThenOr() throws ExpressionException {
    assertEquals(new Or(new And(A, B), C), parse("\"a\" and \"b\" or \"c\""));
    assertEquals(new Or(A, new And(B, C)), parse("\"a\" or \"b\" and \"c\""));
    assertEquals(new Or(A, new Xor(B, C)), parse("\"a\" or \"b\" xor \"c\""));
    assertEquals(new Xor(A, new And(B, C)), parse("\"a\" xor \"b\" and \"c\""));
    assertEquals(new And(new Not(A), B), parse("not \"a\" and \"b\""));
    assertEquals(new And(new Not(new Or(A, B)), C), parse("!(\"a\" | \"b\") & \"c\""));
    assertEquals(new Or(new Or(A, B), C), parse("\"a\" or \"b\" or \"c\""));
    assertEquals(new Not(new Not(A)), parse("not ! \"a\""));
  }

  @Test
  void aliasesNegateTheirOperator() throws ExpressionException {
    assertEquals(new Not(new And(A, B)), parse("\"a\" nand \"b\""));
    assertEquals(new Not(new Or(A, B)), parse("\"a\" nor \"b\""));
    assertEquals(new Not(new Xor(A, B)), parse("\"a\" xnor \"b\""));
    assertEquals(new Or(A, new Not(new And(B, C))), parse("\"a\" or \"b\" NAND \"c\""));
    assertEquals(new And(A, B), parse("\"a\"AND\"b\""));
  }

  @Test
  void termsKeepRegexEscapes() throws ExpressionException {
    assertEquals(term("x\"y"), parse("\"x\\\"y\""));
    assertEquals(term("\\d+ (and) or"), parse("\"\\d+ (and) or\""));
    assertEquals(term("a\\\\"), parse("\"a\\\\\""));
    assertEquals(term(""), parse("\"\""));
  }

  @Test
  void plainTextIsOneTerm() throws ExpressionException {
    assertEquals(term("error and not found"), parse("error and not found"));
    assertEquals(
        new Term("\"a\" and \"b\"", MatchMode.LITERAL),
        ExpressionParser.parse("\"a\" and \"b\"", MatchMode.LITERAL, false));
    assertEquals(
        new And(new Term("a", MatchMode.LITERAL), new Term("b", MatchMode.LITERAL)),
        ExpressionParser.parse("\"a\" and \"b\"", MatchMode.LITERAL, true));
  }

  @Test
  void errorsNameTheColumn() {
    assertError(ExpressionException.Reason.EXPECTED_TERM, 8, "\"a\" and");
    assertError(ExpressionException.Reason.EXPECTED_TERM, 1, "and \"a\"");
    assertError(ExpressionException.Reason.UNTERMINATED_QUOTE, 9, "\"a\" and \"b");
    assertError(ExpressionException.Reason.EXPECTED_CLOSE, 5, "(\"a\"");
    assertError(ExpressionException.Reason.EXPECTED_OPERATOR, 5, "\"a\" \"b\"");
    assertError(ExpressionException.Reason.EXPECTED_OPERATOR, 4, "\"a\")");
    assertError(ExpressionException.Reason.EXPECTED_OPERATOR, 6, "(\"a\" \"b\")");
    assertError(ExpressionException.Reason.UNKNOWN_WORD, 5, "\"a\" plus \"b\"");
    assertError(ExpressionException.Reason.EXPECTED_TERM, 2, "()\"a\"");
    assertError(
        ExpressionException.Reason.TOO_DEEP,
        ExpressionParser.MAX_DEPTH + 1,
        "(".repeat(ExpressionParser.MAX_DEPTH + 1) + "\"a\"");
  }

  @Test
  void deepNestingWithinTheLimitParses() throws ExpressionException {
    int depth = ExpressionParser.MAX_DEPTH;
    assertEquals(A, parse("(".repeat(depth) + "\"a\"" + ")".repeat(depth)));
  }

  private static void assertError(ExpressionException.Reason reason, int column, String text) {
    ExpressionException e =
        assertThrows(
            ExpressionException.class,
            new Executable() {
              @Override
              public void execute() throws ExpressionException {
                parse(text);
              }
            });
    assertEquals(reason, e.reason(), text);
    assertEquals(column, e.column(), text);
  }
}
