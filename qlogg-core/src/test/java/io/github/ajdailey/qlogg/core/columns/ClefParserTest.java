package io.github.ajdailey.qlogg.core.columns;

import static io.github.ajdailey.qlogg.core.columns.ColumnFixture.UTC;
import static io.github.ajdailey.qlogg.core.columns.ColumnFixture.parsed;
import static io.github.ajdailey.qlogg.core.columns.ColumnFixture.random;
import static io.github.ajdailey.qlogg.core.columns.ColumnFixture.unparsed;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.core.time.TimestampParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ClefParserTest {
  private static final String VALUE_CHARS = "ab {}\"\\/\n\t\u00e9\u0001";
  private static final String GRIN = "\ud83d\ude00";
  private static final int TIME = 0;
  private static final int LEVEL = 1;
  private static final int MESSAGE = 2;
  private static final int EXCEPTION = 3;
  private static final int PROPERTIES = 4;
  private static final int TEMPLATE = 5;
  private static final int EVENT_ID = 6;

  @Test
  void generatedEventsGiveTheirColumns() {
    Random random = new Random(5);
    ClefParser parser = new ClefParser(UTC);
    for (int r = 0; r < 1000; r++) {
      List<String> members = new ArrayList<String>();
      String[] expected = {"", "Information", "", "", "", "", ""};
      long millis = 1_767_225_600_001L + 2L * random.nextInt(1_000_000_000);
      String stamp = Instant.ofEpochMilli(millis).toString();
      members.add(member("@t", stamp));
      expected[TIME] = stamp;
      if (random.nextBoolean()) {
        expected[LEVEL] = random.nextBoolean() ? "Warning" : "Error";
        members.add(member("@l", expected[LEVEL]));
      }
      if (random.nextBoolean()) {
        expected[EXCEPTION] = "mock.Failure: " + random(random, VALUE_CHARS, 8) + "\n   at Run()";
        members.add(member("@x", expected[EXCEPTION]));
      }
      if (random.nextBoolean()) {
        expected[EVENT_ID] = Integer.toHexString(random.nextInt());
        members.add(member("@i", expected[EVENT_ID]));
      }
      List<String> names = new ArrayList<String>();
      List<String> shown = new ArrayList<String>();
      List<String> properties = new ArrayList<String>();
      int count = random.nextInt(4);
      for (int p = 0; p < count; p++) {
        String name = "Prop" + p;
        String json;
        String value;
        switch (random.nextInt(4)) {
          case 0 -> {
            value = random(random, VALUE_CHARS, random.nextInt(8)) + (p == 0 ? GRIN : "");
            json = quote(value);
          }
          case 1 -> {
            value = String.valueOf(random.nextInt(1000) - 500);
            json = value;
          }
          case 2 -> {
            value = "{\"a\":[1,{\"b\":\"}]\\\"\"}],\"c\":null}";
            json = value;
          }
          default -> {
            value = "true";
            json = value;
          }
        }
        String key = random.nextInt(5) == 0 ? "@@" + name : name;
        members.add("\"" + key + "\":" + json);
        names.add(name);
        shown.add(key.startsWith("@@") ? key.substring(1) : name);
        properties.add(value);
      }
      if (random.nextBoolean()) {
        expected[MESSAGE] = "plain " + random(random, VALUE_CHARS, 6);
        members.add(member("@m", expected[MESSAGE]));
      } else {
        StringBuilder template = new StringBuilder("{{literal}} ");
        StringBuilder message = new StringBuilder("{literal} ");
        for (int p = 0; p < count; p++) {
          if (shown.get(p).startsWith("@")) {
            continue;
          }
          String[] forms = {"{N}", "{@N}", "{$N}", "{N:000}", "{N,8}"};
          String form = forms[random.nextInt(forms.length)].replace("N", names.get(p));
          template.append(form).append(' ');
          message.append(properties.get(p)).append(' ');
        }
        template.append("{Missing} }} end");
        message.append("{Missing} } end");
        expected[TEMPLATE] = template.toString();
        expected[MESSAGE] = message.toString();
        members.add(member("@mt", template.toString()));
      }
      Collections.shuffle(members, random);
      List<String> pairs = new ArrayList<String>();
      for (String member : members) {
        String key = member.substring(1, member.indexOf("\":"));
        if (!key.startsWith("@") || key.startsWith("@@")) {
          int p = names.indexOf(key.startsWith("@@") ? key.substring(2) : key);
          pairs.add(shown.get(p) + "=" + properties.get(p));
        }
      }
      expected[PROPERTIES] = String.join(", ", pairs);
      String line = "{" + String.join(random.nextBoolean() ? "," : " , ", members) + "}";
      assertEquals(List.of(expected), parsed(parser, line), line);
      assertEquals(millis * 1_000_000L, parser.nanos(), line);
      assertEquals(3, parser.fractionDigits(), line);
    }
  }

  @Test
  void nestingDepthAndShape() {
    ClefParser parser = new ClefParser(UTC);
    String deep = "[".repeat(ClefParser.MAX_DEPTH) + "]".repeat(ClefParser.MAX_DEPTH);
    assertEquals("a=" + deep, parsed(parser, "{\"a\":" + deep + "}").get(PROPERTIES));
    String deeper = "[".repeat(ClefParser.MAX_DEPTH + 1) + "]".repeat(ClefParser.MAX_DEPTH + 1);
    unparsed(parser, "{\"a\":" + deeper + "}");
    unparsed(parser, "{\"@t\":\"2026-03-01T05:00:00Z\"} x");
    unparsed(parser, "{\"@t\":\"2026-03-01T05:00:00Z\"");
    unparsed(parser, "{\"@t\" \"x\"}");
    unparsed(parser, "{\"a\":}");
    unparsed(parser, "[1]");
    unparsed(parser, "");
    unparsed(parser, "2026-03-01 05:00:00 INFO {\"a\":1}");
    assertEquals(
        List.of("", "Information", "", "", "", "", ""), parsed(parser, " { } "), "empty object");
    assertEquals(TimestampParser.NONE, parser.nanos());
  }

  @Test
  void malformedEscapesStayAsWritten() {
    ClefParser parser = new ClefParser(UTC);
    List<String> values = parsed(parser, "{\"@m\":\"a\\x \\" + "uD83D b\\" + "u12\"}");
    assertEquals("a\\x \\" + "uD83D b\\" + "u12", values.get(MESSAGE));
  }

  private static String member(String key, String value) {
    return "\"" + key + "\":" + quote(value);
  }

  /** JSON-quotes a value, escaping every char that may be escaped in a random form. */
  private static String quote(String value) {
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '/' -> out.append(i % 2 == 0 ? "\\/" : "/");
        case '\n' -> out.append("\\n");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20 || Character.isSurrogate(c) || (c > 0x7f && i % 2 == 0)) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }
}
