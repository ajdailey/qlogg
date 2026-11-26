package io.github.ajdailey.qlogg.core.columns;

import static io.github.ajdailey.qlogg.core.columns.ColumnFixture.UTC;
import static io.github.ajdailey.qlogg.core.columns.ColumnFixture.parsed;
import static io.github.ajdailey.qlogg.core.columns.ColumnFixture.unparsed;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ClfParserTest {
  private static final DateTimeFormatter CLF =
      DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ROOT);

  @Test
  void generatedCommonAndCombinedLinesGiveTheirFields() {
    Random random = new Random(3);
    ClfParser parser = new ClfParser(UTC);
    for (int r = 0; r < 1000; r++) {
      List<String> expected = new ArrayList<String>();
      StringBuilder line = new StringBuilder();
      String host = "10.0." + random.nextInt(256) + "." + random.nextInt(256);
      String ident = random.nextBoolean() ? "-" : "ident" + random.nextInt(9);
      String user = random.nextBoolean() ? "-" : "user" + random.nextInt(9);
      OffsetDateTime time =
          OffsetDateTime.ofInstant(
                  Instant.ofEpochSecond(1_767_225_600L + random.nextInt(31_536_000)),
                  ZoneOffset.UTC)
              .withOffsetSameInstant(ZoneOffset.ofHoursMinutes(random.nextInt(13), 30));
      String stamp = CLF.format(time);
      String request = random.nextBoolean() ? "-" : "GET /mock?q=\\\"" + r + "\\\" HTTP/1.1";
      String status = String.valueOf(100 + random.nextInt(500));
      String size = random.nextBoolean() ? "-" : String.valueOf(random.nextInt(100_000));
      line.append(host).append(' ').append(ident).append(' ').append(user);
      line.append(" [").append(stamp).append("] \"").append(request).append("\" ");
      line.append(status).append(' ').append(size);
      for (String value : new String[] {host, ident, user, stamp, request, status, size}) {
        expected.add(dash(value));
      }
      if (random.nextBoolean()) {
        String referer = random.nextBoolean() ? "-" : "http://mock.example/" + r;
        String agent = "Mock/1.0 (agent \\\"" + r + "\\\")";
        line.append(" \"").append(referer).append("\" \"").append(agent).append('"');
        expected.add(dash(referer));
        expected.add(agent);
      } else {
        expected.add("");
        expected.add("");
      }
      assertEquals(expected, parsed(parser, line), line.toString());
      assertEquals(time.toEpochSecond() * 1_000_000_000L, parser.nanos(), line.toString());
    }
  }

  @Test
  void otherShapesDoNotParse() {
    ClfParser parser = new ClfParser(UTC);
    String good = "h - - [01/Mar/2026:05:00:00 +0000] \"GET / HTTP/1.1\" 200 12";
    parsed(parser, good);
    parsed(parser, good + "  ");
    unparsed(parser, good + " extra");
    unparsed(parser, good.replace(" 200 ", " 20 "));
    unparsed(parser, good.replace("[", ""));
    unparsed(parser, good.replace("+0000", ""));
    unparsed(parser, good.replace("\"GET / HTTP/1.1\"", "GET"));
    unparsed(parser, good.replace("12", "x"));
    unparsed(parser, good + " \"ref\"");
    unparsed(parser, " " + good);
    unparsed(parser, "");
    unparsed(parser, "2026-03-01 05:00:00.002 INFO [worker-1] mock");
  }

  private static String dash(String value) {
    return value.equals("-") ? "" : value;
  }
}
