package io.github.ajdailey.qlogg.core.columns;

import static io.github.ajdailey.qlogg.core.columns.ColumnFixture.UTC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.ajdailey.qlogg.core.testkit.SyntheticLog;
import io.github.ajdailey.qlogg.core.text.EncodingDetector;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ColumnDetectorTest {
  private static final String CLF_LINE =
      "10.0.0.%d - - [01/Mar/2026:05:00:00 +0000] \"GET /mock HTTP/1.1\" 200 12\n";
  private static final String CLEF_LINE = "{\"@t\":\"2026-03-01T05:00:00.00%dZ\",\"@mt\":\"m\"}\n";

  @Test
  void eachContentRule() {
    assertEquals(
        ColumnFormat.LOG4J_XML,
        format("<?xml version=\"1.0\"?>\n  <log4j:event logger=\"m\" timestamp=\"1\">\n", "a.log"));
    assertEquals(ColumnFormat.CLEF, format(repeat(CLEF_LINE, 3), "a.log"));
    assertEquals(ColumnFormat.CLF, format(repeat(CLF_LINE, 3), "access_log"));
    assertEquals(ColumnFormat.CSV, format("a,b\n1,2\n", "data.CSV"));
    assertEquals(ColumnFormat.TSV, format("a\tb\n1\t2\n", "data.tab"));
    assertEquals(ColumnFormat.TSV, format("a\tb\n1\t2\n", "data.tsv"));
    assertNull(format("a,b\n1,2\n", "data.log"), "CSV needs the extension");
    assertNull(format("a\n1\n", "data.csv"), "one field");
  }

  @Test
  void thresholds() {
    assertNull(format(repeat(CLF_LINE, 2), "a.log"), "two lines");
    assertNull(format(repeat(CLEF_LINE, 2), "a.log"), "two lines");
    assertEquals(ColumnFormat.CLF, format(repeat(CLF_LINE, 8) + "x\nx\n", "a.log"), "80%");
    assertNull(format(repeat(CLF_LINE, 7) + "x\nx\n", "a.log"), "77%");
    assertEquals(ColumnFormat.CLEF, format(repeat(CLEF_LINE, 4) + "x\n", "a.log"), "80%");
    assertNull(format(repeat("{\"@mt\":\"m\"}\n", 5), "a.log"), "no @t");
    StringBuilder csv = new StringBuilder("a,b,c\n");
    for (int i = 0; i < 8; i++) {
      csv.append("1,2,3\n");
    }
    assertEquals(ColumnFormat.CSV, format(csv + "1,2\n", "a.csv"), "9 of 10");
    assertNull(format(csv + "1,2\n1,2\n", "a.csv"), "9 of 11");
  }

  @Test
  void textLogsMatchNoRule() {
    Random random = new Random(1);
    StringBuilder synthetic = new StringBuilder();
    StringBuilder syslog = new StringBuilder();
    StringBuilder json = new StringBuilder();
    for (int i = 0; i < 300; i++) {
      synthetic.append(SyntheticLog.line(random, i)).append('\n');
      if (i % 7 == 0) {
        synthetic.append("\tat mock.Frame.run(Frame.java:").append(i).append(")\n");
      }
      syslog.append("Mar  1 05:00:0").append(i % 10).append(" host-a mock[12]: entry, ");
      syslog.append(i).append(" \"quoted\" [x]\n");
      json.append("{\"timestamp\":\"2026-03-01T05:00:00Z\",\"level\":\"INFO\"}\n");
    }
    for (String name : new String[] {"app.log", "app", "app.csv", "app.tsv"}) {
      assertNull(format(synthetic.toString(), name), name);
    }
    assertNull(format(syslog.toString(), "app.log"));
    assertNull(format(json.toString(), "app.log"));
  }

  @Test
  void csvDelimiterAndHeader() {
    ColumnSchema semicolon = detect("name;\"x;y\"\n1;2\n3;4\n", "a.csv", StandardCharsets.UTF_8);
    assertEquals(';', semicolon.delimiter());
    assertEquals(List.of("name", "x;y"), names(semicolon));
    assertEquals(true, semicolon.header());
    ColumnSchema bar = detect("a|b|c,d\n1|2|3\n", "a.csv", StandardCharsets.UTF_8);
    assertEquals('|', bar.delimiter());
    assertEquals(3, bar.columns().size());
    for (String first :
        new String[] {"a,,c", "a,b,a", "a,B,b", "a,-1.5,c", "a,2026-03-01 05:00:00,c"}) {
      ColumnSchema schema = detect(first + "\n1,2,3\n", "a.csv", StandardCharsets.UTF_8);
      assertEquals(false, schema.header(), first);
      assertEquals(List.of("1", "2", "3"), names(schema), first);
    }
    ColumnSchema quotedLineFeed =
        detect("id,\"multi\nline\"\n1,\"a\nb\"\n2,c\n", "a.csv", StandardCharsets.UTF_8);
    assertEquals(List.of("id", "multi\nline"), names(quotedLineFeed));
  }

  @Test
  void utf16HeadsAndAByteOrderMark() {
    String text = "name,value\n1,2\n3,4\n";
    for (Charset charset : new Charset[] {StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE}) {
      byte[] body = text.getBytes(charset);
      byte[] bom = "\ufeff".getBytes(charset);
      ByteBuffer head = ByteBuffer.allocate(bom.length + body.length).put(bom).put(body).flip();
      TextEncoding encoding = TextEncoding.of(charset, bom.length);
      ColumnSchema schema = ColumnDetector.detect(head, encoding, "a.csv", UTC);
      assertEquals(List.of("name", "value"), names(schema), charset.name());
      assertEquals(0, head.position(), "head not moved");
    }
  }

  @Test
  void aCutHeadLeavesOutItsLastLine() {
    StringBuilder text = new StringBuilder();
    while (text.length() < EncodingDetector.SAMPLE_BYTES - 40) {
      text.append("1,2,3\n");
    }
    text.append("1,2,3,4,5,6,7,8,9,10,11,12,13,14,15");
    text.setLength(EncodingDetector.SAMPLE_BYTES);
    assertEquals(3, detect(text.toString(), "a.csv", StandardCharsets.US_ASCII).columns().size());
  }

  @Test
  void aChosenFormatBuildsItsSchema() {
    ByteBuffer head = ByteBuffer.wrap("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
    TextEncoding utf8 = TextEncoding.of(StandardCharsets.UTF_8, 0);
    ColumnSchema forced =
        ColumnDetector.schema(ColumnFormat.CSV, head, utf8, Boolean.FALSE, null, UTC);
    assertEquals(List.of("1", "2"), names(forced));
    assertEquals(false, forced.header());
    assertEquals(
        ColumnFormat.CLF,
        ColumnDetector.schema(ColumnFormat.CLF, head, utf8, null, null, UTC).format());
    assertNull(ColumnDetector.schema(ColumnFormat.TIME, head, utf8, null, null, UTC));
    assertEquals(
        BuiltInFormat.ISO,
        ColumnDetector.schema(ColumnFormat.TIME, head, utf8, null, BuiltInFormat.ISO, UTC)
            .timeFormat());
    ColumnSchema empty =
        ColumnDetector.schema(ColumnFormat.TSV, ByteBuffer.allocate(0), utf8, null, null, UTC);
    assertEquals(1, empty.columns().size());
  }

  private static ColumnFormat format(String text, String name) {
    ColumnSchema schema = detect(text, name, StandardCharsets.UTF_8);
    return schema == null ? null : schema.format();
  }

  private static ColumnSchema detect(String text, String name, Charset charset) {
    ByteBuffer head = ByteBuffer.wrap(text.getBytes(charset));
    return ColumnDetector.detect(head, TextEncoding.of(charset, 0), name, UTC);
  }

  private static List<String> names(ColumnSchema schema) {
    List<String> names = new ArrayList<String>();
    for (Column column : schema.columns()) {
      names.add(column.name());
    }
    return names;
  }

  private static String repeat(String format, int count) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < count; i++) {
      text.append(String.format(format, i));
    }
    return text.toString();
  }
}
