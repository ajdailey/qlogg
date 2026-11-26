package io.github.ajdailey.qlogg.core.columns;

import static io.github.ajdailey.qlogg.core.columns.ColumnFixture.UTC;
import static io.github.ajdailey.qlogg.core.columns.ColumnFixture.parsed;
import static io.github.ajdailey.qlogg.core.columns.ColumnFixture.random;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DelimitedParserTest {
  private static final String ALPHABET = "ab 7,;|\t\"\n\r\u00e9\u4e2d";

  @Test
  void generatedRecordsGiveTheirFields() {
    Random random = new Random(11);
    char[] delimiters = {',', ';', '|', '\t'};
    for (int r = 0; r < 2000; r++) {
      char delimiter = delimiters[random.nextInt(delimiters.length)];
      int columns = 1 + random.nextInt(8);
      int fieldCount = 1 + random.nextInt(10);
      List<String> fields = new ArrayList<String>();
      StringBuilder record = new StringBuilder();
      for (int f = 0; f < fieldCount; f++) {
        String field = random(random, ALPHABET, random.nextInt(7));
        fields.add(field);
        if (f > 0) {
          record.append(delimiter);
        }
        record.append(encode(field, delimiter, random.nextInt(4) == 0));
      }
      List<String> expected = new ArrayList<String>();
      for (int c = 0; c < columns; c++) {
        if (c == columns - 1 && fieldCount > columns) {
          expected.add(String.join(String.valueOf(delimiter), fields.subList(c, fieldCount)));
        } else {
          expected.add(c < fieldCount ? fields.get(c) : "");
        }
      }
      DelimitedParser parser = new DelimitedParser(delimiter, columns);
      assertEquals(expected, parsed(parser, record), record.toString());
    }
  }

  @Test
  void quotesOpenOnlyAtAFieldStart() {
    DelimitedParser parser = new DelimitedParser(',', 3);
    assertEquals(List.of("a", "b\"c", "d"), parsed(parser, "a,b\"c,d"));
    assertEquals(List.of("ax", "b", ""), parsed(parser, "\"a\"x,b"));
    assertEquals(List.of("say \"hi\"", "", ""), parsed(parser, "\"say \"\"hi\"\"\""));
    assertEquals(List.of("open,b", "", ""), parsed(parser, "\"open,b"));
    assertEquals(List.of("", "", ""), parsed(parser, ""));
    assertEquals(List.of("", "", ""), parsed(parser, ",,"));
    DelimitedParser tsv = new DelimitedParser('\t', 2);
    assertEquals(List.of("6\" pipe", "x"), parsed(tsv, "6\" pipe\tx"));
  }

  @Test
  void quotedLineBreaksAndTheByteOrderMark() {
    DelimitedParser parser = new DelimitedParser(',', 2);
    assertEquals(List.of("a\r\nb", "c"), parsed(parser, "\"a\r\nb\",c"));
    assertEquals(List.of("a\nb", "c"), parsed(parser, "\"a\nb\",c"));
    assertEquals(List.of("name", "value"), parsed(parser, "\ufeffname,value"));
  }

  @Test
  void fieldsPastTheLimitJoinTheLastColumn() {
    StringBuilder record = new StringBuilder();
    for (int i = 0; i < ColumnSchema.MAX_FIELDS + 5; i++) {
      record.append(i == 0 ? "" : ",").append(i);
    }
    List<String> names = new ArrayList<String>();
    for (int i = 0; i < ColumnSchema.MAX_FIELDS + 5; i++) {
      names.add("c" + i);
    }
    ColumnSchema schema = ColumnSchema.delimited(ColumnFormat.CSV, ',', names, true);
    assertEquals(ColumnSchema.MAX_FIELDS, schema.columns().size());
    List<String> values = parsed(schema.parser(UTC), record);
    assertEquals(ColumnSchema.MAX_FIELDS, values.size());
    assertEquals("1022", values.get(1022));
    assertEquals("1023,1024,1025,1026,1027,1028", values.get(1023));
  }

  /** Writes a field quoted when it must be or when {@code quote} is set. */
  static String encode(String field, char delimiter, boolean quote) {
    boolean must =
        field.indexOf(delimiter) >= 0
            || field.indexOf('"') >= 0
            || field.indexOf('\n') >= 0
            || field.indexOf('\r') >= 0;
    if (!must && !quote) {
      return field;
    }
    return "\"" + field.replace("\"", "\"\"") + "\"";
  }
}
