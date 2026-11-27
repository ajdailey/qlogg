package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.columns.FieldParser;
import io.github.ajdailey.qlogg.core.columns.FieldValues;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The column terms of one matcher and the parse they share: each line or record is parsed at most
 * once, by the first column term that needs its values. A term tests its column's value, empty for
 * a line or record that does not parse, with a {@link Pattern}: a literal quoted, case folded as a
 * whole-line literal folds it ({@link StandardMatchers#foldsAsciiOnly} folds ASCII letters only),
 * whole word through {@link StandardMatchers#wholeWord}.
 *
 * <p>Prefilter: on a {@link ColumnSchema#verbatim} column, a term whose every match holds a literal
 * (the literal itself, or a case-sensitive regex's {@link StandardMatchers#requiredPrefix}) skips
 * the parse of a line whose bytes hold neither that literal nor any of the format's {@link
 * ColumnSchema#escapes}, since the value then cannot hold it either. Not thread-safe.
 */
final class ColumnTerms {
  private final SearchQuery query;
  private final TextEncoding encoding;
  private FieldParser parser;
  private StandardMatchers.Node[] escapes;
  private boolean parsed;
  private int escaped;

  ColumnTerms(SearchQuery query, TextEncoding encoding) {
    this.query = query;
    this.encoding = encoding;
  }

  /** Starts the next line or record: its parse and escape test have not run. */
  void next() {
    parsed = false;
    escaped = -1;
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
    final StandardMatchers.Node required = required(term, column);
    return new StandardMatchers.Node() {
      @Override
      public boolean test(LineText line) {
        if (required != null && !required.test(line) && !escaped(line)) {
          return false;
        }
        FieldValues values = values(line);
        CharSequence value = column < values.count() ? values.value(column) : "";
        return matcher.reset(value).find();
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
  private StandardMatchers.Node required(SearchExpression.Term term, int column) {
    ColumnSchema schema = query.columns().schema();
    if (!schema.verbatim(column) || !escapesSearchable(schema)) {
      return null;
    }
    if (term.mode() == MatchMode.LITERAL) {
      return StandardMatchers.contains(
          term.pattern(), query.caseSensitive(), encoding, query.hideAnsi());
    }
    return StandardMatchers.prefilter(term.pattern(), query, encoding, 1);
  }

  /** Builds the escape searches once; false when an escape has no byte form. */
  private boolean escapesSearchable(ColumnSchema schema) {
    if (escapes == null) {
      List<String> texts = schema.escapes();
      StandardMatchers.Node[] built = new StandardMatchers.Node[texts.size()];
      for (int i = 0; i < built.length; i++) {
        built[i] = StandardMatchers.contains(texts.get(i), true, encoding, query.hideAnsi());
        if (built[i] == null) {
          return false;
        }
      }
      escapes = built;
    }
    return true;
  }

  private boolean escaped(LineText line) {
    if (escaped < 0) {
      escaped = 0;
      for (StandardMatchers.Node escape : escapes) {
        if (escape.test(line)) {
          escaped = 1;
          break;
        }
      }
    }
    return escaped == 1;
  }

  private FieldValues values(LineText line) {
    if (!parsed) {
      parser.parse(line.chars());
      parsed = true;
    }
    return parser.values();
  }
}
