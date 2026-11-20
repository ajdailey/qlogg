package io.github.ajdailey.qlogg.app.scratch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.i18n.UiText;
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
    // A logged query string is form-encoded, so + decodes as a space.
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
  }

  @Test
  void malformedJsonAndXmlAreRefusedWithLineAndColumn() {
    assertRefused(
        Transforms.Kind.JSON_FORMAT,
        "{\"a\": [1,\n  2",
        "Not transformed: unexpected end of the JSON text at line 2, column 4");
    assertRefused(
        Transforms.Kind.JSON_FORMAT,
        "{\"a\": [1, 2,]}",
        "Not transformed: expected a value at line 1, column 13");
    assertRefused(
        Transforms.Kind.XML_FORMAT,
        "<a>\n<b>x</c></a>",
        "Not transformed: end tag does not match <b> at line 2, column 7");
    assertRefused(
        Transforms.Kind.XML_FORMAT,
        "<log>\n<entry>",
        "Not transformed: <entry> not closed at the end of the text at line 2, column 8");
    assertRefused(
        Transforms.Kind.JSON_UNESCAPE,
        "a\\x",
        "Not transformed: invalid escape at line 1, column 2");
  }

  private static void assertRefused(final Transforms.Kind kind, final String input, String notice) {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            new Executable() {
              @Override
              public void execute() {
                Transforms.apply(kind, input);
              }
            });
    assertEquals(notice, UiText.SCRATCHPAD_FAILED.format(e.getMessage()));
  }

  @Test
  void jsonAndXmlChangeOnlyWhitespace() {
    assertEquals(
        "{\n  \"a\": [\n    1,\n    \"x,}\"\n  ],\n  \"b\": {} // kept\n}",
        Transforms.apply(Transforms.Kind.JSON_FORMAT, "{\"a\":[1, \"x,}\"],\"b\":{} // kept\n}"));
    assertEquals(
        "<?xml version=\"1.0\"?>\n<a>\n  <b x=\"1\"/>\n  <c> text </c>\n  <!-- n -->\n</a>",
        Transforms.apply(
            Transforms.Kind.XML_FORMAT,
            "<?xml version=\"1.0\"?><a><b x=\"1\"/><c> text </c><!-- n --></a>"));
  }

  @Test
  void unescapesReplaceEscapesAndEntities() {
    assertEquals(
        "say \"hi\"\n\u00e9",
        Transforms.apply(Transforms.Kind.JSON_UNESCAPE, "say \\\"hi\\\"\\n\\u00e9"));
    assertEquals(
        "<a> & \u00a0 A &bogus;",
        Transforms.apply(Transforms.Kind.XML_UNESCAPE, "&lt;a&gt; &amp; &nbsp; &#x41; &bogus;"));
  }

  // Regression: 330 000 nested XML tags asked for about 1e11 chars of indentation.
  @Test
  void outputAndInputAreCappedWhateverTheNesting() {
    int depth = 100_000;
    tooLong(Transforms.Kind.XML_FORMAT, "<a>".repeat(depth) + "</a>".repeat(depth));
    tooLong(Transforms.Kind.JSON_FORMAT, "[".repeat(depth) + "]".repeat(depth));
    final String brackets = "[".repeat(Transforms.MAX_CHARS);
    tooLong(Transforms.Kind.BASE64_ENCODE, brackets);
    tooLong(Transforms.Kind.HEX_ENCODE, brackets);
    for (Transforms.Kind kind :
        new Transforms.Kind[] {
          Transforms.Kind.URL_DECODE,
          Transforms.Kind.JSON_FORMAT,
          Transforms.Kind.XML_FORMAT,
          Transforms.Kind.JSON_UNESCAPE,
          Transforms.Kind.XML_UNESCAPE
        }) {
      tooLong(kind, brackets + "[");
    }
    String fits = "&amp;".repeat(Transforms.MAX_CHARS / 5);
    assertEquals(
        Transforms.MAX_CHARS / 5, Transforms.apply(Transforms.Kind.XML_UNESCAPE, fits).length());
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
