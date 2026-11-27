package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Column terms on single lines where the prefilter must not skip the parse: a value that differs
 * from the raw text through an escape, a composed or defaulted value, and an unparsed line.
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
