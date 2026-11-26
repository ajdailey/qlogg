package io.github.ajdailey.qlogg.core.columns;

import static io.github.ajdailey.qlogg.core.columns.ColumnFixture.parsed;
import static io.github.ajdailey.qlogg.core.columns.ColumnFixture.random;
import static io.github.ajdailey.qlogg.core.columns.ColumnFixture.unparsed;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.core.time.TimestampParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class Log4jXmlParserTest {
  private static final String TEXT_CHARS = "ab <>&\"' ]\n\t\u00e9";
  private static final int TIME = 0;
  private static final int LEVEL = 1;
  private static final int LOGGER = 2;
  private static final int THREAD = 3;
  private static final int MESSAGE = 4;
  private static final int THROWABLE = 5;
  private static final int NDC = 6;
  private static final int LOCATION = 7;
  private static final int PROPERTIES = 8;

  @Test
  void generatedEventsGiveTheirColumns() {
    Random random = new Random(7);
    Log4jXmlParser parser = new Log4jXmlParser();
    for (int r = 0; r < 1000; r++) {
      String[] expected = new String[9];
      for (int i = 0; i < expected.length; i++) {
        expected[i] = "";
      }
      long millis = 1_767_225_600_000L + random.nextInt(1_000_000_000);
      expected[TIME] = String.valueOf(millis);
      expected[LEVEL] = random.nextBoolean() ? "INFO" : "ERROR";
      expected[LOGGER] = "mock.Logger" + random(random, "<&\"", 1);
      expected[THREAD] = "worker-" + r;
      List<String> attributes = new ArrayList<String>();
      attributes.add("logger=\"" + escape(expected[LOGGER]) + "\"");
      attributes.add("timestamp=\"" + millis + "\"");
      attributes.add("level='" + expected[LEVEL] + "'");
      attributes.add("thread=\"" + expected[THREAD] + "\"");
      attributes.add("extra=\"x\"");
      Collections.shuffle(attributes, random);
      StringBuilder event = new StringBuilder(random.nextBoolean() ? "" : "  ");
      event.append("<log4j:event ").append(String.join(" ", attributes)).append(">\r\n");
      expected[MESSAGE] = random(random, TEXT_CHARS, random.nextInt(12));
      event.append("<log4j:message>").append(text(expected[MESSAGE], random));
      event.append("</log4j:message>\r\n");
      if (random.nextBoolean()) {
        expected[NDC] = "ndc " + r;
        event.append("<log4j:NDC>").append(text(expected[NDC], random)).append("</log4j:NDC>\n");
      }
      if (random.nextBoolean()) {
        expected[THROWABLE] = "mock.Failure: x\n\tat mock.Run.run(Run.java:" + r + ")\n";
        event.append("<log4j:throwable><![CDATA[").append(expected[THROWABLE]);
        event.append("]]></log4j:throwable>\n");
      }
      event.append("<mock:unknown a=\"<log4j:message>\">skipped</mock:unknown>\n");
      if (random.nextBoolean()) {
        expected[LOCATION] = "mock.Run.run(Run.java:" + r + ")";
        event.append("<log4j:locationInfo class=\"mock.Run\" method=\"run\" file=\"Run.java\" ");
        event.append("line=\"").append(r).append("\"/>\n");
      }
      if (random.nextBoolean()) {
        expected[PROPERTIES] = "key=v&1, other=" + r;
        event.append("<log4j:properties>\n<log4j:data name=\"key\" value=\"v&amp;1\"/>\n");
        event.append("<log4j:data value=\"").append(r).append("\" name=\"other\" />\n");
        event.append("</log4j:properties>\n");
      }
      event.append("</log4j:event>\r\n");
      assertEquals(List.of(expected), parsed(parser, event), event.toString());
      assertEquals(millis * 1_000_000L, parser.nanos(), event.toString());
      assertEquals(3, parser.fractionDigits());
    }
  }

  @Test
  void splitCdataEntitiesAndDeclarations() {
    Log4jXmlParser parser = new Log4jXmlParser();
    String event =
        "<log4j:event timestamp=\"1\"><log4j:message><![CDATA[a]]>]]&gt;<![CDATA[b]]>"
            + "</log4j:message></log4j:event>";
    assertEquals("a]]>b", parsed(parser, event).get(MESSAGE));
    String entity = "<log4j:event><log4j:message>&xxe; &#65;&lt;</log4j:message></log4j:event>";
    assertEquals("&xxe; A<", parsed(parser, entity).get(MESSAGE));
    assertEquals(TimestampParser.NONE, parser.nanos(), "no timestamp attribute");
    unparsed(parser, "<?xml version=\"1.0\"?>");
    unparsed(parser, "<!DOCTYPE log [<!ENTITY xxe SYSTEM \"file:///mock\">]>");
    unparsed(parser, "<log4j:eventx timestamp=\"1\">");
    unparsed(parser, "<log4j:event timestamp=\"1\"");
    unparsed(parser, "<log4j:event timestamp=1>");
    unparsed(parser, "x <log4j:event>");
    unparsed(parser, "");
  }

  @Test
  void aCutRecordKeepsWhatItHolds() {
    Log4jXmlParser parser = new Log4jXmlParser();
    List<String> values =
        parsed(parser, "<log4j:event level=\"WARN\">\n<log4j:message>partial message");
    assertEquals("WARN", values.get(LEVEL));
    assertEquals("partial message", values.get(MESSAGE));
    assertEquals("", parsed(parser, "<log4j:event level=\"WARN\"/>").get(MESSAGE));
    assertEquals(TimestampParser.NONE, parser.nanos());
    parsed(parser, "<log4j:event timestamp=\"99999999999999\">");
    assertEquals(TimestampParser.NONE, parser.nanos(), "14 digits");
  }

  /** Writes text as CDATA or entity-escaped. */
  private static String text(String value, Random random) {
    if (random.nextBoolean() && !value.contains("]]>")) {
      return "<![CDATA[" + value + "]]>";
    }
    return escape(value);
  }

  private static String escape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
