package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.columns.FieldParser;
import io.github.ajdailey.qlogg.core.columns.FieldValues;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The column terms of one matcher and the parse they share: each line or record is parsed at most
 * once, by the first column term that needs its values. A term tests its column's value, empty for
 * a line or record that does not parse, with a {@link Pattern}: a literal quoted, case folded as a
 * whole-line literal folds it ({@link StandardMatchers#foldsAsciiOnly} folds ASCII letters only),
 * whole word through {@link StandardMatchers#wholeWord}. The pattern reads the value through {@link
 * LineText#checked}, so a cancel stops a regex that backtracks without end.
 *
 * <p>Prefilter: on a {@link ColumnSchema#verbatim} column, a term whose every match holds a literal
 * (the literal itself, or a case-sensitive regex's {@link StandardMatchers#requiredPrefix}) skips
 * the parse of a line for which {@link RecordEscapes#mayHold} is false. Not thread-safe.
 */
final class ColumnTerms {
  private final SearchQuery query;
  private final TextEncoding encoding;
  private FieldParser parser;
  private RecordEscapes escapes;
  private boolean parsed;

  ColumnTerms(SearchQuery query, TextEncoding encoding) {
    this.query = query;
    this.encoding = encoding;
  }

  /** Starts the next line or record: its parse and escape test have not run. */
  void next() {
    parsed = false;
    if (escapes != null) {
      escapes.next();
    }
  }

  /**
   * Compiles a term with a column.
   *
   * @throws ColumnException when the query's columns cannot test the column
   */
  StandardMatchers.Node term(SearchExpression.Term term) {
    final int column = column(term.column());
    String pattern = term.pattern();
    boolean literal = term.mode() == MatchMode.LITERAL;
    boolean cased = query.caseSensitive();
    int flags = Pattern.UNIX_LINES;
    if (!cased) {
      flags |= Pattern.CASE_INSENSITIVE;
      if (!literal || !StandardMatchers.foldsAsciiOnly(pattern, encoding, query.hideAnsi())) {
        flags |= Pattern.UNICODE_CASE;
      }
    }
    String source = literal ? Pattern.quote(pattern) : pattern;
    if (query.wholeWord()) {
      source = StandardMatchers.wholeWord(source);
    }
    final Matcher matcher = Pattern.compile(source, flags).matcher("");
    final StandardMatchers.ByteLiteral required = required(term, column);
    return new StandardMatchers.Node() {
      @Override
      public boolean test(LineText line) {
        if (required != null && !escapes.mayHold(line, required)) {
          return false;
        }
        FieldValues values = values(line);
        CharSequence value = column < values.count() ? values.value(column) : "";
        return matcher.reset(line.checked(value)).find();
      }
    };
  }

  /** Resolves a term's column; creates the parser on the first one. */
  private int column(String name) {
    ColumnSearch search = query.columns();
    if (search == null) {
      throw new ColumnException(ColumnException.Reason.NO_COLUMNS, name);
    }
    ColumnSchema schema = search.schema();
    int column = schema.columnIndex(name);
    if (column < 0) {
      throw new ColumnException(ColumnException.Reason.NO_SUCH_COLUMN, name);
    }
    if (schema.format().multiLine() && search.records() == null) {
      throw new ColumnException(ColumnException.Reason.LINES_ONLY, name);
    }
    if (parser == null) {
      parser = schema.parser(search.context());
    }
    return column;
  }

  /** Returns the byte search every line whose value can match passes, or null. */
  private StandardMatchers.ByteLiteral required(SearchExpression.Term term, int column) {
    ColumnSchema schema = query.columns().schema();
    if (!schema.verbatim(column)) {
      return null;
    }
    if (escapes == null) {
      escapes = RecordEscapes.of(schema, encoding);
      if (escapes == null) {
        return null;
      }
    }
    if (term.mode() == MatchMode.LITERAL) {
      return StandardMatchers.contains(
          term.pattern(), query.caseSensitive(), encoding, query.hideAnsi());
    }
    return StandardMatchers.prefilter(term.pattern(), query, encoding, 1);
  }

  private FieldValues values(LineText line) {
    if (!parsed) {
      parser.parse(line.charBuffer());
      parsed = true;
    }
    return parser.values();
  }
}
