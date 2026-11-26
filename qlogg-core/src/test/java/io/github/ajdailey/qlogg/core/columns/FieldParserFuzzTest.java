package io.github.ajdailey.qlogg.core.columns;

import static io.github.ajdailey.qlogg.core.columns.ColumnFixture.UTC;
import static io.github.ajdailey.qlogg.core.columns.ColumnFixture.random;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.core.columns.ColumnFixture.CountingChars;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Random and mutated records into every parser: no exception, at most {@link #READS_PER_CHAR} reads
 * per char plus {@link #BASE_READS}, and no allocation once the buffers have grown.
 */
class FieldParserFuzzTest {
  private static final int READS_PER_CHAR = 16;
  private static final int BASE_READS = 8192;
  private static final long ALLOCATION_SLACK = 64 * 1024;
  private static final String SYNTAX =
      "\"{}[]<>&;:,|\t\n\r\\/=' @!-?#xutml0123456789abc\u00e9\ud83d\ude00";
  private static final String[] SAMPLES = {
    "name;\"a;b\";\"c\"\"d\"\n,x\ty|z",
    "10.0.0.1 - user [01/Mar/2026:05:00:00 +0100] \"GET /a\\\"b HTTP/1.1\" 200 12"
        + " \"http://mock.example/\" \"Mock/1.0\"",
    "{\"@t\":\"2026-03-01T05:00:00.002Z\",\"@mt\":\"{A} {{x}} {B:000} {@C}\",\"A\":\"\\u00e9\\n\","
        + "\"B\":12,\"C\":{\"d\":[1,\"}\"]},\"@@E\":true,\"@l\":\"Error\",\"@x\":\"boom\"}",
    "<log4j:event logger=\"m&amp;\" timestamp=\"1772341200002\" level=\"INFO\" thread=\"t\">\n"
        + "<log4j:message><![CDATA[a]]>]]&gt;<![CDATA[b]]></log4j:message>\n"
        + "<log4j:throwable>x &lt; y\n\tat z</log4j:throwable><log4j:NDC>n</log4j:NDC>\n"
        + "<log4j:locationInfo class=\"c\" method=\"m\" file=\"f\" line=\"1\"/>\n"
        + "<log4j:properties><log4j:data name=\"k\" value=\"v\"/></log4j:properties>\n"
        + "<!-- c --></log4j:event>",
    "[2026-03-01 05:00:00.002] INFO mock"
  };

  @Test
  void parsersStayBoundedOnRandomAndMutatedRecords() {
    List<String> inputs = inputs(new Random(17));
    List<FieldParser> parsers = parsers();
    for (FieldParser parser : parsers) {
      for (String input : inputs) {
        CountingChars chars = new CountingChars(input);
        parser.parse(chars);
        long bound = (long) READS_PER_CHAR * input.length() + BASE_READS;
        assertTrue(
            chars.reads() <= bound,
            parser.getClass().getSimpleName() + " read " + chars.reads() + " of " + input.length());
      }
    }
  }

  @Test
  void aParseAllocatesNothingOnceItsBuffersHaveGrown() {
    java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
    com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) bean;
    assumeTrue(threads.isThreadAllocatedMemorySupported());
    threads.setThreadAllocatedMemoryEnabled(true);
    List<String> inputs = inputs(new Random(19));
    List<FieldParser> parsers = parsers();
    for (int pass = 0; pass < 3; pass++) {
      parseAll(parsers, inputs);
    }
    long id = Thread.currentThread().threadId();
    long before = threads.getThreadAllocatedBytes(id);
    int parsedChars = parseAll(parsers, inputs);
    long allocated = threads.getThreadAllocatedBytes(id) - before;
    assertTrue(
        allocated < ALLOCATION_SLACK,
        allocated + " bytes allocated parsing " + parsedChars + " chars");
  }

  private static int parseAll(List<FieldParser> parsers, List<String> inputs) {
    int chars = 0;
    for (int p = 0; p < parsers.size(); p++) {
      FieldParser parser = parsers.get(p);
      for (int i = 0; i < inputs.size(); i++) {
        String input = inputs.get(i);
        parser.parse(input);
        chars += input.length();
      }
    }
    return chars;
  }

  private static List<FieldParser> parsers() {
    List<FieldParser> parsers = new ArrayList<FieldParser>();
    parsers.add(new DelimitedParser(',', 8));
    parsers.add(new DelimitedParser('\t', 3));
    parsers.add(new ClfParser(UTC));
    parsers.add(new ClefParser(UTC));
    parsers.add(new Log4jXmlParser());
    parsers.add(new StampParser(BuiltInFormat.ISO.parser(UTC)));
    parsers.add(new StampParser(BuiltInFormat.CLF.parser(UTC)));
    return parsers;
  }

  /** Random chars, Latin-1 noise, mutated samples and long runs of one syntax char. */
  private static List<String> inputs(Random random) {
    List<String> inputs = new ArrayList<String>();
    for (int i = 0; i < 300; i++) {
      inputs.add(random(random, SYNTAX, random.nextInt(600)));
      StringBuilder noise = new StringBuilder();
      for (int k = random.nextInt(600); k > 0; k--) {
        noise.append((char) random.nextInt(256));
      }
      inputs.add(noise.toString());
      for (String sample : SAMPLES) {
        inputs.add(mutate(sample, random));
      }
    }
    for (String run :
        new String[] {"<", "{", "[", "\"", "\\", "&", "{a}", "<log4j:message>", "a=\"\" ", "&#1"}) {
      inputs.add(run.repeat(20_000));
      inputs.add("<log4j:event " + run.repeat(20_000));
      inputs.add("{\"@mt\":\"" + run.repeat(20_000) + "\",\"a\":\"" + "x".repeat(5000) + "\"}");
    }
    StringBuilder tokens = new StringBuilder("{");
    for (int i = 0; i < 2000; i++) {
      tokens.append("\"p").append(i).append("\":\"").append("v".repeat(20)).append("\",");
    }
    tokens.append("\"@mt\":\"").append("{p1999}".repeat(3000)).append("\"}");
    inputs.add(tokens.toString());
    return inputs;
  }

  private static String mutate(String sample, Random random) {
    StringBuilder text = new StringBuilder(sample);
    int mutations = 1 + random.nextInt(4);
    for (int m = 0; m < mutations && text.length() > 0; m++) {
      int at = random.nextInt(text.length());
      int span = Math.min(text.length() - at, 1 + random.nextInt(8));
      switch (random.nextInt(4)) {
        case 0 -> text.delete(at, at + span);
        case 1 -> text.insert(at, random(random, SYNTAX, span));
        case 2 -> text.insert(at, text.substring(at, at + span));
        default -> text.setLength(at);
      }
    }
    return text.toString();
  }
}
