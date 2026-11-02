package io.github.ajdailey.qlogg.core.search;

/** A search pattern, or patterns combined with boolean operators. Immutable. */
public sealed interface SearchExpression
    permits SearchExpression.Term,
        SearchExpression.And,
        SearchExpression.Or,
        SearchExpression.Xor,
        SearchExpression.Not {
  /**
   * One pattern.
   *
   * @param pattern the text or regular expression
   * @param mode how the pattern is read
   */
  record Term(String pattern, MatchMode mode) implements SearchExpression {
    /** Checks the components. */
    public Term {
      if (pattern == null || mode == null) {
        throw new IllegalArgumentException("pattern and mode are required");
      }
    }
  }

  /**
   * Both operands match.
   *
   * @param left evaluated first
   * @param right evaluated only when left matches
   */
  record And(SearchExpression left, SearchExpression right) implements SearchExpression {}

  /**
   * Either operand matches.
   *
   * @param left evaluated first
   * @param right evaluated only when left does not match
   */
  record Or(SearchExpression left, SearchExpression right) implements SearchExpression {}

  /**
   * Exactly one operand matches.
   *
   * @param left the left operand
   * @param right the right operand
   */
  record Xor(SearchExpression left, SearchExpression right) implements SearchExpression {}

  /**
   * The operand does not match.
   *
   * @param operand the operand
   */
  record Not(SearchExpression operand) implements SearchExpression {}
}
