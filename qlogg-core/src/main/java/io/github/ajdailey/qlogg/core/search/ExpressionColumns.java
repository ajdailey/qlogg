package io.github.ajdailey.qlogg.core.search;

/** Column names in a parsed {@link SearchExpression}: a default column and the prefix syntax. */
public final class ExpressionColumns {
  private ExpressionColumns() {}

  /**
   * Gives every term without its own column the column {@code name}.
   *
   * @param expression the parsed expression
   * @param name a column name or 1-based number; empty returns {@code expression} unchanged
   * @return the expression
   */
  public static SearchExpression within(SearchExpression expression, String name) {
    if (name.isEmpty()) {
      return expression;
    }
    return switch (expression) {
      case SearchExpression.Term t ->
          t.column() == null ? new SearchExpression.Term(t.pattern(), t.mode(), name) : t;
      case SearchExpression.And a ->
          new SearchExpression.And(within(a.left(), name), within(a.right(), name));
      case SearchExpression.Or o ->
          new SearchExpression.Or(within(o.left(), name), within(o.right(), name));
      case SearchExpression.Xor x ->
          new SearchExpression.Xor(within(x.left(), name), within(x.right(), name));
      case SearchExpression.Not n -> new SearchExpression.Not(within(n.operand(), name));
    };
  }

  /**
   * Returns the first column a term names, in text order.
   *
   * @param expression the parsed expression
   * @return the column, or null when every term tests the whole row
   */
  public static String first(SearchExpression expression) {
    return switch (expression) {
      case SearchExpression.Term t -> t.column();
      case SearchExpression.And a -> either(first(a.left()), a.right());
      case SearchExpression.Or o -> either(first(o.left()), o.right());
      case SearchExpression.Xor x -> either(first(x.left()), x.right());
      case SearchExpression.Not n -> first(n.operand());
    };
  }

  private static String either(String left, SearchExpression right) {
    return left != null ? left : first(right);
  }

  /**
   * Writes one term with a column prefix, {@code name:"pattern"}, as {@link ExpressionParser} reads
   * it back.
   *
   * @param name the column's name
   * @param number its 1-based number, written when the name falls outside the prefix grammar
   * @param pattern the term's pattern
   * @return the text, or null when the pattern ends in a backslash or holds one before a quote,
   *     which the quoted form cannot carry
   */
  public static String prefixed(String name, int number, String pattern) {
    if (pattern.endsWith("\\") || pattern.contains("\\\"")) {
      return null;
    }
    String prefix =
        ExpressionParser.columnName(name + ':', 0, name.length()) && !digits(name)
            ? name
            : Integer.toString(number);
    return prefix + ":\"" + pattern.replace("\"", "\\\"") + '"';
  }

  private static boolean digits(String name) {
    for (int i = 0; i < name.length(); i++) {
      if (name.charAt(i) < '0' || name.charAt(i) > '9') {
        return false;
      }
    }
    return true;
  }
}
