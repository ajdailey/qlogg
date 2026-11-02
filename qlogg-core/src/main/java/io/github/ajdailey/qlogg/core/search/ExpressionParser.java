package io.github.ajdailey.qlogg.core.search;

/**
 * Parses the search field's text. Combining syntax: terms in double quotes ({@code \"} is a quote
 * inside a term; any other backslash stays, so regex escapes pass through), the operators {@code
 * and &}, {@code or |}, {@code xor}, {@code not !}, the aliases {@code nand}, {@code nor} and
 * {@code xnor}, and parentheses. Operator words are case-insensitive. Precedence, highest first:
 * {@code not}, {@code and nand}, {@code xor xnor}, {@code or nor}; binary operators associate left.
 * A combining text without a double quote is one term, so a plain pattern searches as typed.
 */
public final class ExpressionParser {
  /** Deepest nesting of parentheses and {@code not} accepted. */
  public static final int MAX_DEPTH = 256;

  private enum Kind {
    TERM,
    OPEN,
    CLOSE,
    AND,
    NAND,
    OR,
    NOR,
    XOR,
    XNOR,
    NOT,
    END
  }

  private final String text;
  private final MatchMode mode;
  private int position;
  private Kind kind;
  private String term;
  private int tokenColumn;
  private int depth;

  private ExpressionParser(String text, MatchMode mode) {
    this.text = text;
    this.mode = mode;
  }

  /**
   * Parses a search text.
   *
   * @param text the text as typed
   * @param mode the mode of every term
   * @param combining false makes the whole text one term
   * @return the expression
   * @throws ExpressionException when combining text does not parse
   */
  public static SearchExpression parse(String text, MatchMode mode, boolean combining)
      throws ExpressionException {
    if (!combining || text.indexOf('"') < 0) {
      return new SearchExpression.Term(text, mode);
    }
    ExpressionParser parser = new ExpressionParser(text, mode);
    parser.next();
    SearchExpression expression = parser.or();
    if (parser.kind != Kind.END) {
      throw parser.error(ExpressionException.Reason.EXPECTED_OPERATOR);
    }
    return expression;
  }

  private SearchExpression or() throws ExpressionException {
    SearchExpression left = xor();
    while (kind == Kind.OR || kind == Kind.NOR) {
      boolean negate = kind == Kind.NOR;
      next();
      SearchExpression joined = new SearchExpression.Or(left, xor());
      left = negate ? new SearchExpression.Not(joined) : joined;
    }
    return left;
  }

  private SearchExpression xor() throws ExpressionException {
    SearchExpression left = and();
    while (kind == Kind.XOR || kind == Kind.XNOR) {
      boolean negate = kind == Kind.XNOR;
      next();
      SearchExpression joined = new SearchExpression.Xor(left, and());
      left = negate ? new SearchExpression.Not(joined) : joined;
    }
    return left;
  }

  private SearchExpression and() throws ExpressionException {
    SearchExpression left = unary();
    while (kind == Kind.AND || kind == Kind.NAND) {
      boolean negate = kind == Kind.NAND;
      next();
      SearchExpression joined = new SearchExpression.And(left, unary());
      left = negate ? new SearchExpression.Not(joined) : joined;
    }
    return left;
  }

  private SearchExpression unary() throws ExpressionException {
    switch (kind) {
      case NOT -> {
        enter();
        next();
        SearchExpression operand = unary();
        depth--;
        return new SearchExpression.Not(operand);
      }
      case TERM -> {
        SearchExpression.Term result = new SearchExpression.Term(term, mode);
        next();
        return result;
      }
      case OPEN -> {
        enter();
        next();
        SearchExpression inner = or();
        if (kind != Kind.CLOSE) {
          throw error(
              kind == Kind.END
                  ? ExpressionException.Reason.EXPECTED_CLOSE
                  : ExpressionException.Reason.EXPECTED_OPERATOR);
        }
        depth--;
        next();
        return inner;
      }
      default -> throw error(ExpressionException.Reason.EXPECTED_TERM);
    }
  }

  private void enter() throws ExpressionException {
    if (++depth > MAX_DEPTH) {
      throw error(ExpressionException.Reason.TOO_DEEP);
    }
  }

  private ExpressionException error(ExpressionException.Reason reason) {
    return new ExpressionException(reason, tokenColumn);
  }

  /** Reads the next token into kind, term and tokenColumn. */
  private void next() throws ExpressionException {
    while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
      position++;
    }
    tokenColumn = position + 1;
    if (position >= text.length()) {
      kind = Kind.END;
      return;
    }
    char c = text.charAt(position);
    switch (c) {
      case '"' -> readTerm();
      case '(' -> single(Kind.OPEN);
      case ')' -> single(Kind.CLOSE);
      case '&' -> single(Kind.AND);
      case '|' -> single(Kind.OR);
      case '!' -> single(Kind.NOT);
      default -> readWord();
    }
  }

  private void single(Kind found) {
    kind = found;
    position++;
  }

  private void readTerm() throws ExpressionException {
    StringBuilder out = new StringBuilder();
    int i = position + 1;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '"') {
        kind = Kind.TERM;
        term = out.toString();
        position = i + 1;
        return;
      }
      if (c == '\\' && i + 1 < text.length()) {
        char escaped = text.charAt(i + 1);
        if (escaped == '"') {
          out.append('"');
          i += 2;
          continue;
        }
        if (escaped == '\\') {
          out.append("\\\\");
          i += 2;
          continue;
        }
      }
      out.append(c);
      i++;
    }
    throw new ExpressionException(ExpressionException.Reason.UNTERMINATED_QUOTE, tokenColumn);
  }

  private void readWord() throws ExpressionException {
    int start = position;
    while (position < text.length() && !endsWord(text.charAt(position))) {
      position++;
    }
    String word = asciiLower(text.substring(start, position));
    kind =
        switch (word) {
          case "and" -> Kind.AND;
          case "nand" -> Kind.NAND;
          case "or" -> Kind.OR;
          case "nor" -> Kind.NOR;
          case "xor" -> Kind.XOR;
          case "xnor" -> Kind.XNOR;
          case "not" -> Kind.NOT;
          default -> throw error(ExpressionException.Reason.UNKNOWN_WORD);
        };
  }

  /** Lowercases ASCII letters only, so no locale or Unicode case mapping applies. */
  private static String asciiLower(String word) {
    char[] chars = word.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      if (chars[i] >= 'A' && chars[i] <= 'Z') {
        chars[i] = (char) (chars[i] + ('a' - 'A'));
      }
    }
    return new String(chars);
  }

  private static boolean endsWord(char c) {
    return Character.isWhitespace(c) || "\"()&|!".indexOf(c) >= 0;
  }
}
