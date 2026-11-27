package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.columns.FieldParser;
import io.github.ajdailey.qlogg.core.columns.FieldValues;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.io.ReadBuffers;
import io.github.ajdailey.qlogg.core.testkit.SyntheticRecords;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The search oracle for column view: a search with {@link ColumnSearch} equals parse-then-filter
 * over the generated records, each record's text tested whole and each column term on its parsed
 * value. Every format; multi-line records with quoted line feeds and log4j events; literal and
 * regex, case, whole word and inverse; combined column and whole-row terms; values with and without
 * the escape bytes the prefilter reads; chunk sizes that cut records; restricted, scoped and
 * unrestricted; UTF-16 and a sparse index. A CSV or TSV header record is not searched; a column
 * regex that backtracks without end ends on cancel.
 */
class ColumnSearchTest {
  private static final MemoryBudget BUDGET = MemoryBudget.of(1L << 30);
  private static final TimeContext CONTEXT = new TimeContext(ZoneOffset.UTC, 2026, 11);
  private static final int RECORDS = 400;

  /** Chunk lines, chunk bytes, chunks in flight and gap. */
  private static final int[][] SIZES = {
    {1, 1, 1, 0}, {3, 300, 2, 150}, {7, 100, 3, 1000}, {50, 4096, 4, 0}, {10_000, 1 << 20, 3, 16384}
  };

  /** Case-sensitive, whole word, inverse. */
  private static final boolean[][] FLAGS = {
    {true, false, false}, {false, false, false}, {false, true, false}, {true, false, true}
  };

  private final SearchEngine[] engines = new SearchEngine[SIZES.length];
  private SearchFixture fixture;

  @BeforeEach
  void create() {
    fixture = new SearchFixture();
  }

  @AfterEach
  void close() {
    fixture.close();
  }

  private static ColumnSchema schema(ColumnFormat format) {
    return format == ColumnFormat.TIME
        ? ColumnSchema.timestamp(BuiltInFormat.ISO)
        : RecordScanTest.schema(format);
  }

  /** Message, level and one other column per format, by name or number. */
  private static String[] columns(ColumnFormat format) {
    return switch (format) {
      case CSV, TSV -> new String[] {"message", "LEVEL", "3"};
      case CLF -> new String[] {"Request", "9", "user"};
      case CLEF -> new String[] {"message", "level", "Properties"};
      case LOG4J_XML -> new String[] {"Message", "level", "throwable"};
      case TIME -> new String[] {"text", "2", "time"};
    };
  }

  private static List<String> queries(ColumnFormat format, MatchMode mode) {
    String[] c = columns(format);
    String m = c[0];
    String l = c[1];
    String o = c[2];
    List<String> queries = new ArrayList<String>();
    queries.add(m + ":\"flagword\"");
    queries.add(m + ":\"blockterm\" or " + l + ":\"info\"");
    queries.add("\"queue\" and not " + l + ":\"warn\"");
    queries.add(m + ":\"\\\"\"");
    queries.add(o + ":\"&\" or " + m + ":\"<\"");
    queries.add(o + ":\"mock\" xor \"Frame\"");
    queries.add("1:\"o\" and " + m + ":\"café\"");
    queries.add(m + ":\"\"");
    queries.add("not " + m + ":\"#1\"");
    if (mode == MatchMode.REGEX) {
      queries.add(m + ":\"^Run\"");
      queries.add(l + ":\"^(INFO|ERROR)$\"");
      queries.add(m + ":\"#\\d+7\" and \"event\"");
    }
    return queries;
  }

  /** A record's text and its values, parsed once. */
  private record Expected(long first, String text, FieldValues values) {}

  private static List<Expected> records(
      String text, ColumnSchema schema, Charset charset, List<String> lines) {
    boolean complete = text.endsWith("\n");
    List<Long> starts =
        schema.format().multiLine()
            ? RecordReference.recordStarts(lines, complete, schema, charset)
            : everyLine(lines.size());
    List<Expected> records = new ArrayList<Expected>();
    for (int i = 0; i < starts.size(); i++) {
      long first = starts.get(i);
      long end = i + 1 < starts.size() ? starts.get(i + 1) : lines.size();
      String record = RecordReference.record(lines, first, end);
      FieldParser parser = schema.parser(CONTEXT);
      parser.parse(record);
      records.add(new Expected(first, record, parser.values()));
    }
    return records;
  }

  private static List<Long> everyLine(int count) {
    List<Long> lines = new ArrayList<Long>();
    for (long line = 0; line < count; line++) {
      lines.add(line);
    }
    return lines;
  }

  private static boolean evaluate(
      SearchExpression expression,
      Expected record,
      ColumnSchema schema,
      SearchQuery query,
      TextEncoding encoding) {
    if (expression instanceof SearchExpression.Term term) {
      CharSequence text = record.text();
      if (term.column() != null) {
        int column = schema.columnIndex(term.column());
        text = column < record.values().count() ? record.values().value(column) : "";
      }
      return termMatches(term, text, query, encoding);
    }
    if (expression instanceof SearchExpression.Not not) {
      return !evaluate(not.operand(), record, schema, query, encoding);
    }
    if (expression instanceof SearchExpression.And and) {
      return evaluate(and.left(), record, schema, query, encoding)
          && evaluate(and.right(), record, schema, query, encoding);
    }
    if (expression instanceof SearchExpression.Or or) {
      return evaluate(or.left(), record, schema, query, encoding)
          || evaluate(or.right(), record, schema, query, encoding);
    }
    SearchExpression.Xor xor = (SearchExpression.Xor) expression;
    return evaluate(xor.left(), record, schema, query, encoding)
        != evaluate(xor.right(), record, schema, query, encoding);
  }

  /** A term as a regex: a literal quoted, folded as a whole-line literal folds. */
  private static boolean termMatches(
      SearchExpression.Term term, CharSequence text, SearchQuery query, TextEncoding encoding) {
    boolean literal = term.mode() == MatchMode.LITERAL;
    int flags = Pattern.UNIX_LINES;
    if (!query.caseSensitive()) {
      flags |= Pattern.CASE_INSENSITIVE;
      if (!literal || !StandardMatchers.foldsAsciiOnly(term.pattern(), encoding, false)) {
        flags |= Pattern.UNICODE_CASE;
      }
    }
    String source = literal ? Pattern.quote(term.pattern()) : term.pattern();
    if (query.wholeWord()) {
      source = StandardMatchers.wholeWord(source);
    }
    return Pattern.compile(source, flags).matcher(text).find();
  }

  /**
   * The records a query matches; with {@code held} set, the restriction is the records holding one
   * of its lines ({@link RecordStarts}), checked against the records' own bounds.
   */
  private static List<Long> expected(
      List<Expected> records,
      ColumnSchema schema,
      SearchQuery query,
      TextEncoding encoding,
      LineSet held) {
    List<Long> hits = new ArrayList<Long>();
    for (int i = 0; i < records.size(); i++) {
      Expected record = records.get(i);
      long first = record.first();
      if (held != null) {
        long next = i + 1 < records.size() ? records.get(i + 1).first() : Long.MAX_VALUE;
        long member = held.ceiling(first);
        if (member < 0 || member >= next) {
          continue;
        }
      }
      if (first == 0 && schema.header() && query.records() != null) {
        continue;
      }
      if (query.scope() != null
          && (first < query.scope().first() || first >= query.scope().end())) {
        continue;
      }
      if (held == null && query.restriction() != null && !query.restriction().contains(first)) {
        continue;
      }
      if (evaluate(query.expression(), record, schema, query, encoding) != query.inverse()) {
        hits.add(first);
      }
    }
    return hits;
  }

  private List<Long> search(SearchEngine engine, LineSource source, SearchQuery query)
      throws InterruptedException {
    MutableLineSet set = new MutableLineSet();
    SearchFixture.Recording recording = fixture.search(engine, source, query, set);
    assertEquals(OperationOutcome.COMPLETED, recording.outcome, String.valueOf(recording.failure));
    assertEquals(recording.total, recording.searched);
    assertEquals(0, recording.skipped);
    List<Long> lines = new ArrayList<Long>();
    for (long i = 0; i < set.size(); i++) {
      lines.add(set.select(i));
    }
    return lines;
  }

  /** One engine per size: each holds read buffers charged to {@code IO_BUFFERS}. */
  private SearchEngine engine(int[] size) {
    for (int i = 0; i < SIZES.length; i++) {
      if (SIZES[i] == size) {
        if (engines[i] == null) {
          engines[i] =
              fixture.engine(new SearchSettings(size[0], size[1], size[3], size[2], 0, BUDGET));
        }
        return engines[i];
      }
    }
    throw new IllegalArgumentException("not a size of SIZES");
  }

  private static List<LineSet> restrictions(long lines, Random random) {
    List<LineSet> sets = new ArrayList<LineSet>();
    sets.add(null);
    MutableLineSet some = new MutableLineSet();
    for (long line = 0; line < lines; line++) {
      if (random.nextInt(3) == 0) {
        some.add(line);
      }
    }
    sets.add(some);
    MutableLineSet clusters = new MutableLineSet();
    long line = random.nextInt(5);
    while (line < lines) {
      for (int i = 0; i < 12 && line < lines; i++) {
        clusters.add(line++);
      }
      line += random.nextInt(40);
    }
    sets.add(clusters);
    return sets;
  }

  /** Searches every query, flag set, size and restriction and compares with the reference. */
  private void assertOracle(
      ColumnFormat format, Charset charset, MemoryBudget indexBudget, int[][] sizes, long seed)
      throws Exception {
    String text = new SyntheticRecords(format, seed, true, 0.2).text(RECORDS);
    ColumnSchema schema = schema(format);
    RecordReference.Source source =
        RecordReference.index(fixture.workers, text.getBytes(charset), charset, indexBudget);
    assertEquals(indexBudget != BUDGET, source.index().sparse());
    List<String> lines = RecordReference.lines(text);
    List<Expected> records = records(text, schema, charset, lines);
    RecordLines recordLines = null;
    if (format.multiLine()) {
      RecordScan scan = new RecordScan(schema, source.encoding(), BUDGET);
      scan.scan(
          source.bytes(),
          source.index(),
          new ReadBuffers(4096, 2, BUDGET),
          new CancellationToken());
      recordLines = scan.snapshot();
    }
    ColumnSearch columns = new ColumnSearch(schema, CONTEXT, recordLines);
    Random random = new Random(seed);
    List<LineSet> restrictions = restrictions(lines.size(), random);
    // A filter tab of lines inside records: the records holding them.
    LineSet held = restrictions.get(1);
    if (recordLines != null) {
      restrictions.add(new RecordStarts(held, recordLines));
    }
    for (MatchMode mode : MatchMode.values()) {
      for (String typed : queries(format, mode)) {
        SearchExpression expression = ExpressionParser.parse(typed, mode, true);
        for (boolean[] flags : FLAGS) {
          LineRange scope =
              random.nextInt(4) == 0 ? new LineRange(lines.size() / 5, lines.size() / 2) : null;
          SearchQuery base =
              new SearchQuery(expression, flags[0], flags[1], flags[2], scope).withColumns(columns);
          for (LineSet restriction : restrictions) {
            SearchQuery query = base.restrictedTo(restriction);
            List<Long> want =
                expected(
                    records,
                    schema,
                    query,
                    source.encoding(),
                    restriction instanceof RecordStarts ? held : null);
            int[] size = sizes[random.nextInt(sizes.length)];
            RecordReference.assertSameStarts(
                want,
                search(engine(size), source, query),
                format.id()
                    + " "
                    + charset
                    + " "
                    + mode
                    + " "
                    + typed
                    + " flags "
                    + flags[0]
                    + flags[1]
                    + flags[2]
                    + " size "
                    + size[0]
                    + "/"
                    + size[1]
                    + " restricted "
                    + (restriction != null)
                    + " scope "
                    + scope);
          }
        }
      }
    }
  }

  @Test
  void columnSearchEqualsParseThenFilterForEveryFormat() throws Exception {
    long seed = 31;
    for (ColumnFormat format : ColumnFormat.values()) {
      assertOracle(format, StandardCharsets.UTF_8, BUDGET, SIZES, seed++);
    }
  }

  @Test
  void columnSearchEqualsParseThenFilterInUtf16AndOverASparseIndex() throws Exception {
    assertOracle(ColumnFormat.CSV, StandardCharsets.UTF_16LE, BUDGET, SIZES, 41);
    assertOracle(ColumnFormat.LOG4J_XML, StandardCharsets.UTF_16LE, BUDGET, SIZES, 42);
    assertOracle(
        ColumnFormat.LOG4J_XML, StandardCharsets.UTF_8, MemoryBudget.of(150_000), SIZES, 43);
  }

  @Test
  void aColumnTheSearchCannotTestFailsTheSearch() throws Exception {
    String text = new SyntheticRecords(ColumnFormat.CSV, 7, false, 0).text(50);
    RecordReference.Source source =
        RecordReference.index(
            fixture.workers, text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, BUDGET);
    ColumnSchema csv = schema(ColumnFormat.CSV);
    RecordLines records = RecordLines.continuations(new MutableLineSet(), 0);
    SearchEngine engine = engine(SIZES[SIZES.length - 1]);
    SearchExpression level = ExpressionParser.parse("nope:\"x\"", MatchMode.LITERAL, true);
    assertFails(
        engine,
        source,
        new SearchQuery(level, true, false, false, null)
            .withColumns(new ColumnSearch(csv, CONTEXT, records)),
        ColumnException.Reason.NO_SUCH_COLUMN);
    SearchExpression known = ExpressionParser.parse("level:\"INFO\"", MatchMode.LITERAL, true);
    assertFails(
        engine,
        source,
        new SearchQuery(known, true, false, false, null),
        ColumnException.Reason.NO_COLUMNS);
    assertFails(
        engine,
        source,
        new SearchQuery(known, true, false, false, null, false, known, false)
            .withColumns(new ColumnSearch(csv, CONTEXT, records)),
        ColumnException.Reason.LINES_ONLY);
    assertFails(
        engine,
        source,
        new SearchQuery(known, true, false, false, null)
            .withColumns(new ColumnSearch(csv, CONTEXT, null)),
        ColumnException.Reason.LINES_ONLY);
  }

  @Test
  void headerRecordsAreNotSearchedInColumnView() throws Exception {
    String text = "id,level\n1,level one\n2,INFO\nid,level\n3,level\n";
    RecordReference.Source source =
        RecordReference.index(
            fixture.workers, text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, BUDGET);
    ColumnSchema csv = ColumnSchema.delimited(ColumnFormat.CSV, ',', List.of("id", "level"), true);
    RecordLines records = RecordLines.continuations(new MutableLineSet(), 5);
    SearchEngine engine = engine(SIZES[1]);
    SearchQuery level = SearchQuery.of("level", MatchMode.LITERAL);
    assertEquals(List.of(0L, 1L, 3L, 4L), search(engine, source, level));
    SearchQuery shown = level.withColumns(new ColumnSearch(csv, CONTEXT, records));
    assertEquals(List.of(1L, 3L, 4L), search(engine, source, shown));
    ColumnSearch repeated =
        new ColumnSearch(csv, CONTEXT, records, new SortedLines(new long[] {3}));
    assertEquals(List.of(1L, 4L), search(engine, source, level.withColumns(repeated)));
    assertEquals(
        List.of(4L),
        search(
            engine,
            source,
            level.withColumns(repeated).restrictedTo(new SortedLines(new long[] {0, 3, 4}))));
    SearchExpression column = ExpressionParser.parse("level:\"level\"", MatchMode.LITERAL, true);
    assertEquals(
        List.of(1L, 4L),
        search(
            engine,
            source,
            new SearchQuery(column, true, false, false, null).withColumns(repeated)));
    ColumnSchema numbered =
        ColumnSchema.delimited(ColumnFormat.CSV, ',', List.of("id", "level"), false);
    assertEquals(
        List.of(0L, 1L, 3L, 4L),
        search(engine, source, level.withColumns(new ColumnSearch(numbered, CONTEXT, records))));
  }

  @Test
  void cancelEndsACatastrophicColumnRegexWithinABound() throws Exception {
    String text = "id,value\n1," + "a".repeat(60) + "\n";
    RecordReference.Source source =
        RecordReference.index(
            fixture.workers, text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, BUDGET);
    ColumnSchema csv = ColumnSchema.delimited(ColumnFormat.CSV, ',', List.of("id", "value"), true);
    final CountDownLatch matching = new CountDownLatch(1);
    MatcherFactory gated =
        new MatcherFactory() {
          @Override
          public LineMatcher create(
              SearchQuery query, TextEncoding encoding, CancellationToken token) {
            final LineMatcher inner = StandardMatchers.INSTANCE.create(query, encoding, token);
            return new LineMatcher() {
              @Override
              public boolean matches(LineText line) {
                matching.countDown();
                return inner.matches(line);
              }
            };
          }
        };
    SearchEngine engine =
        new SearchEngine(fixture.workers, gated, SearchSettings.defaults(2, fixture.budget));
    SearchExpression evil = ExpressionParser.parse("value:\"(.*a){25}x\"", MatchMode.REGEX, true);
    SearchQuery query =
        new SearchQuery(evil, true, false, false, null)
            .withColumns(
                new ColumnSearch(csv, CONTEXT, RecordLines.continuations(new MutableLineSet(), 2)));
    SearchFixture.Recording recording = new SearchFixture.Recording();
    OperationHandle handle = engine.search(source, query, new MutableLineSet(), recording);
    assertTrue(matching.await(SearchFixture.BOUND.toMillis(), TimeUnit.MILLISECONDS));
    long canceled = System.nanoTime();
    handle.cancel();
    assertTrue(handle.await(5000));
    assertTrue(System.nanoTime() - canceled < 5_000_000_000L);
    assertEquals(OperationOutcome.CANCELED, recording.outcome);
  }

  private void assertFails(
      SearchEngine engine, LineSource source, SearchQuery query, ColumnException.Reason reason)
      throws InterruptedException {
    SearchFixture.Recording recording = fixture.search(engine, source, query);
    assertEquals(OperationOutcome.FAILED, recording.outcome);
    ColumnException failure = assertInstanceOf(ColumnException.class, recording.failure);
    assertEquals(reason, failure.reason());
    assertTrue(failure.getDescription().contains(failure.column()), failure.getDescription());
  }
}
