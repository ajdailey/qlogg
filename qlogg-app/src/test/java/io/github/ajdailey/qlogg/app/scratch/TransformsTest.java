package io.github.ajdailey.qlogg.app.scratch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** The scratchpad's transforms and derived values. */
class TransformsTest {
  @Test
  void encodingsRoundTrip() {
    String text = "mock value é";
    for (Transforms.Kind[] pair :
        new Transforms.Kind[][] {
          {Transforms.Kind.BASE64_ENCODE, Transforms.Kind.BASE64_DECODE},
          {Transforms.Kind.HEX_ENCODE, Transforms.Kind.HEX_DECODE}
        }) {
      assertEquals(text, Transforms.apply(pair[1], Transforms.apply(pair[0], text)));
    }
    assertEquals("6869", Transforms.apply(Transforms.Kind.HEX_ENCODE, "hi"));
    assertEquals("hi", Transforms.apply(Transforms.Kind.HEX_DECODE, "0x68 69"));
    assertEquals("a b/c", Transforms.apply(Transforms.Kind.URL_DECODE, "a+b%2Fc"));
  }

  @Test
  void badInputIsRefusedWithAReason() {
    for (final String input : new String[] {"abc", "zz"}) {
      assertThrows(
          IllegalArgumentException.class,
          new Executable() {
            @Override
            public void execute() {
              Transforms.apply(Transforms.Kind.HEX_DECODE, input);
            }
          });
    }
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            Transforms.apply(Transforms.Kind.JSON_FORMAT, "{\"a\": [1, 2}");
          }
        });
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            Transforms.apply(Transforms.Kind.XML_FORMAT, "<a><b");
          }
        });
  }

  @Test
  void jsonAndXmlAreIndented() {
    assertEquals(
        "{\n  \"a\": [\n    1,\n    \"x,}\"\n  ],\n  \"b\": {}\n}",
        Transforms.apply(Transforms.Kind.JSON_FORMAT, "{\"a\":[1, \"x,}\"],\"b\":{}}"));
    assertEquals(
        "<?xml version=\"1.0\"?>\n<a>\n  <b x=\"1\"/>\n  <c>\n    text\n  </c>\n  <!-- n -->\n</a>",
        Transforms.apply(
            Transforms.Kind.XML_FORMAT,
            "<?xml version=\"1.0\"?><a><b x=\"1\"/><c>text</c><!-- n --></a>"));
  }

  // Regression: 330 000 nested XML tags asked for about 1e11 chars of indentation.
  @Test
  void deepNestingIndentsAtMostMaxDepthAndOutputIsBounded() {
    int depth = Transforms.MAX_DEPTH + 10;
    String deepest = Transforms.INDENT.repeat(Transforms.MAX_DEPTH);
    String xml = Transforms.apply(Transforms.Kind.XML_FORMAT, "<a>".repeat(depth) + "x");
    assertTrue(xml.endsWith("\n" + deepest + "<a>\n" + deepest + "x"), xml);
    String json =
        Transforms.apply(Transforms.Kind.JSON_FORMAT, "[".repeat(depth) + "]".repeat(depth));
    assertTrue(json.contains("\n" + deepest + "[]"), json);
    tooLong(Transforms.Kind.XML_FORMAT, "<a>".repeat(330_000));
    final String brackets = "[".repeat(Transforms.MAX_CHARS);
    tooLong(Transforms.Kind.JSON_FORMAT, brackets);
    tooLong(Transforms.Kind.BASE64_ENCODE, brackets);
    tooLong(Transforms.Kind.HEX_ENCODE, brackets);
    tooLong(Transforms.Kind.URL_DECODE, brackets + "[");
  }

  private static void tooLong(final Transforms.Kind kind, final String input) {
    assertThrows(
        Transforms.TooLongException.class,
        new Executable() {
          @Override
          public void execute() {
            Transforms.apply(kind, input);
          }
        });
  }

  @Test
  void valuesIncludeTheChecksumNumberBaseAndEpochTime() {
    List<String> values = Transforms.values("1700000000", ZoneOffset.ofHours(1));
    assertEquals(3, values.size());
    assertTrue(values.get(0).startsWith("CRC32: "), values.get(0));
    assertEquals("Number: 1700000000 = 0x6553f100", values.get(1));
    assertEquals("Time: 2023-11-14 22:13:20.000 UTC, 2023-11-14 23:13:20.000 local", values.get(2));
    assertEquals("Number: 0xff = 255", Transforms.values("0xff", ZoneOffset.UTC).get(1));
    assertEquals(1, Transforms.values("not a number", ZoneOffset.UTC).size());
  }
}
