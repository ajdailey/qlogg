package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.columns.FieldParser;
import io.github.ajdailey.qlogg.core.columns.FieldValues;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Column terms on single lines where the prefilter must not skip the parse: a value that differs
 * from the raw text through an escape, a composed or defaulted value, and an unparsed line; and
 * random CSV and log4j records against parse-then-match.
 */
class ColumnTermsTest {
  private static final TimeContext UTC = new TimeContext(ZoneOffset.UTC, 2026, 11);
  private static final String CLEF =
      "{\"@t\":\"2026-11-01T12:00:00Z\",\"@mt\":\"Hello {User}\",\"User\":\"Bob\",\"Path\":\"a\\\\b\"}";

  private static boolean matches(
      ColumnSchema schema, String typed, boolean caseSensitive, String line)
      throws ExpressionException {
    TextEncoding encoding = TextEncoding.of(StandardCharsets.UTF_8, 0);
    RecordLines records =
        schema.format().multiLine() ? RecordLines.continuations(new MutableLineSet(), 0) : null;
    CancellationToken token = new CancellationToken();
    SearchQuery query =
        new SearchQuery(
                ExpressionParser.parse(typed, MatchMode.LITERAL, true),
                caseSensitive,
                false,
                false,
                null)
            .withColumns(new ColumnSearch(schema, UTC, records));
    LineText text = new LineText(encoding, token);
    byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
    text.set(ByteBuffer.wrap(bytes), 0, bytes.length);
    return StandardMatchers.INSTANCE.create(query, encoding, token).matches(text);
  }

  @Test
  void aComposedOrDefaultedValueIsParsedThoughTheLineLacksTheLiteral() throws Exception {
    ColumnSchema clef = ColumnSchema.fixed(ColumnFormat.CLEF);
    assertEquals(true, matches(clef, "message:\"Hello Bob\"", true, CLEF));
    assertEquals(true, matches(clef, "level:\"Information\"", true, CLEF));
    assertEquals(true, matches(clef, "properties:\"User=Bob\"", true, CLEF));
    assertEquals(true, matches(clef, "properties:\"a\\b\"", true, CLEF));
    assertEquals(false, matches(clef, "template:\"Hello Bob\"", true, CLEF));
    assertEquals(true, matches(clef, "template:\"hello {user}\"", false, CLEF));
  }

  @Test
  void anEscapedValueIsParsedThoughTheLineLacksTheLiteral() throws Exception {
    ColumnSchema csv = ColumnSchema.delimited(ColumnFormat.CSV, ',', List.of("a", "b"), true);
    assertEquals(true, matches(csv, "a:\"x\\\"y\" and b:\"z\"", true, "\"x\"\"y\",z"));
    assertEquals(false, matches(csv, "b:\"x\"", true, "\"x\"\"y\",z"));
    ColumnSchema log4j = ColumnSchema.fixed(ColumnFormat.LOG4J_XML);
    String event = "<log4j:event logger=\"a&amp;b\"><log4j:message><![CDATA[x]]>y</log4j:message>";
    assertEquals(true, matches(log4j, "logger:\"a&b\" and message:\"xy\"", true, event));
  }

  /** CSV fields and log4j content built from the bytes the escape tests read. */
  private static final String[] CSV_PIECES = {"\"", "\"\"", ",", "a", "b", "ab", "\n", "\r", " "};

  private static final String[] XML_PIECES = {
    "<![CDATA[", "]]>", "<![CDATA[ab]]>", "&amp;", "&#98;", "&#x61;", "&lt;", "&bogus;", "&", ";",
    "a", "b", "ab", ">", "<", "]]", "\n", "\r\n", "é", "<![CDATA[a\nb]]>"
  };

  private static String pieces(Random random, String[] pieces, int count) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < count; i++) {
      text.append(pieces[random.nextInt(pieces.length)]);
    }
    return text.toString();
  }

  private static String log4jEvent(Random random) {
    return "<log4j:event logger=\""
        + pieces(random, new String[] {"a", "b", "&amp;", "&#98;", "<![CDATA["}, 3)
        + "\" level=\"INFO\">\n<log4j:message>"
        + pieces(random, XML_PIECES, 1 + random.nextInt(8))
        + "</log4j:message>\n<log4j:throwable>"
        + pieces(random, XML_PIECES, random.nextInt(5))
        + "</log4j:throwable>\n</log4j:event>";
  }

  private static String csvRecord(Random random) {
    String record = pieces(random, CSV_PIECES, random.nextInt(14));
    return random.nextInt(20) == 0 ? "\ufeff" + record : record;
  }

  /** The literals a value can hold: its substrings, and noise. */
  private static List<String> literals(Random random, FieldValues values, String[] pieces) {
    List<String> literals = new ArrayList<String>();
    for (int column = 0; column < values.count(); column++) {
      String value = values.value(column).toString();
      for (int i = 0; i < 4 && !value.isEmpty(); i++) {
        int from = random.nextInt(value.length());
        int to = Math.min(value.length(), from + 1 + random.nextInt(5));
        literals.add(value.substring(from, to));
      }
    }
    for (int i = 0; i < 4; i++) {
      literals.add(pieces(random, pieces, 1 + random.nextInt(3)));
    }
    return literals;
  }

  private static boolean expected(String literal, CharSequence value, boolean caseSensitive) {
    int flags = Pattern.UNIX_LINES;
    if (!caseSensitive) {
      flags |= Pattern.CASE_INSENSITIVE;
      if (!StandardMatchers.foldsAsciiOnly(
          literal, TextEncoding.of(StandardCharsets.UTF_8, 0), false)) {
        flags |= Pattern.UNICODE_CASE;
      }
    }
    return Pattern.compile(Pattern.quote(literal), flags).matcher(value).find();
  }

  private static void assertPrefilterAgrees(ColumnSchema schema, String record, Random random) {
    TextEncoding encoding = TextEncoding.of(StandardCharsets.UTF_8, 0);
    CancellationToken token = new CancellationToken();
    FieldParser parser = schema.parser(UTC);
    parser.parse(record);
    FieldValues values = parser.values();
    String[] pieces = schema.format() == ColumnFormat.LOG4J_XML ? XML_PIECES : CSV_PIECES;
    LineText text = new LineText(encoding, token);
    byte[] bytes = record.getBytes(StandardCharsets.UTF_8);
    text.set(ByteBuffer.wrap(bytes), 0, bytes.length);
    RecordLines records = RecordLines.continuations(new MutableLineSet(), 0);
    for (String literal : literals(random, values, pieces)) {
      for (int column = 0; column < schema.columns().size(); column++) {
        if (!schema.verbatim(column)) {
          continue;
        }
        String name = Integer.toString(column + 1);
        CharSequence value = column < values.count() ? values.value(column).toString() : "";
        for (boolean caseSensitive : new boolean[] {true, false}) {
          SearchQuery query =
              new SearchQuery(
                      new SearchExpression.Term(literal, MatchMode.LITERAL, name),
                      caseSensitive,
                      false,
                      false,
                      null)
                  .withColumns(new ColumnSearch(schema, UTC, records));
          boolean actual = StandardMatchers.INSTANCE.create(query, encoding, token).matches(text);
          assertEquals(
              expected(literal, value, caseSensitive),
              actual,
              "column " + name + " literal [" + literal + "] in [" + record + "]");
        }
      }
    }
  }

  @Test
  void theEscapeTestsNeverSkipARecordWhoseValueHoldsTheLiteral() {
    Random random = new Random(23);
    ColumnSchema csv = ColumnSchema.delimited(ColumnFormat.CSV, ',', List.of("a", "b", "c"), true);
    ColumnSchema log4j = ColumnSchema.fixed(ColumnFormat.LOG4J_XML);
    for (int i = 0; i < 3000; i++) {
      assertPrefilterAgrees(csv, csvRecord(random), random);
      assertPrefilterAgrees(log4j, log4jEvent(random), random);
    }
  }

  @Test
  void quotesAndSectionsAtTheEdgesAreParsed() throws Exception {
    ColumnSchema csv = ColumnSchema.delimited(ColumnFormat.CSV, ',', List.of("a", "b"), true);
    assertEquals(true, matches(csv, "a:\"x\\\"y\"", true, "\"x\"\"y\",z"));
    assertEquals(true, matches(csv, "b:\"ab\"", true, "x,\"\"ab"));
    assertEquals(true, matches(csv, "b:\"a,b\"", true, "x,\"a,\"b"));
    assertEquals(true, matches(csv, "b:\"y,z\"", true, "x,\"y\",z"));
    assertEquals(true, matches(csv, "b:\"y,z\"", true, "x,y,\"z\""));
    assertEquals(false, matches(csv, "b:\"q\"", true, "x,\"y\",\"z\""));
    ColumnSchema log4j = ColumnSchema.fixed(ColumnFormat.LOG4J_XML);
    String split = "<log4j:event><log4j:message><![CDATA[fla]]><![CDATA[g\nword]]></log4j:message>";
    assertEquals(true, matches(log4j, "message:\"flag\nword\"", true, split));
    String mixed = "<log4j:event><log4j:message>fl<![CDATA[ag]]>word</log4j:message>";
    assertEquals(true, matches(log4j, "message:\"flagword\"", false, mixed));
    String numeric = "<log4j:event><log4j:message>&#102;lagword</log4j:message>";
    assertEquals(true, matches(log4j, "message:\"flagword\"", true, numeric));
    String plain = "<log4j:event><log4j:message><![CDATA[a]]></log4j:message>";
    assertEquals(false, matches(log4j, "message:\"flagword\"", true, plain));
  }

  @Test
  void anUnparsedLineHasEmptyValues() throws Exception {
    ColumnSchema clf = ColumnSchema.fixed(ColumnFormat.CLF);
    assertEquals(false, matches(clf, "host:\"garbage\"", true, "garbage line"));
    assertEquals(true, matches(clf, "not host:\"garbage\"", true, "garbage line"));
    assertEquals(true, matches(clf, "host:\"\"", true, "garbage line"));
    String line = "10.0.0.1 - mock [01/Nov/2026:12:00:00 +0000] \"GET /x HTTP/1.1\" 200 5";
    assertEquals(true, matches(clf, "1:\"10.0.0.1\" and status:\"200\"", true, line));
  }
}
