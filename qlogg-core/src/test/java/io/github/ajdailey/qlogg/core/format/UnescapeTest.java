package io.github.ajdailey.qlogg.core.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qlogg.core.format.FormatException.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** {@link JsonStrings#unescape} and {@link XmlEntities#unescape}. */
class UnescapeTest {
  private static final String GRIN = "😀";

  @Test
  void xmlUnescapeReadsEntitiesAndCharacterReferences() {
    assertEquals(
        "<a>\"'& ÿ€♦ŒΩω",
        XmlEntities.unescape(
            "&lt;a&gt;&quot;&apos;&amp;&nbsp;&yuml;&euro;&diams;&OElig;&Omega;&omega;"));
    assertEquals("AA" + GRIN + GRIN, XmlEntities.unescape("&#65;&#x41;&#X1F600;&#128512;"));
  }

  @Test
  void xmlUnescapeLeavesWhatItDoesNotKnow() {
    for (String kept :
        new String[] {
          "&bogus;",
          "&AMP;",
          "&amp",
          "& amp;",
          "&;",
          "&#;",
          "&#x;",
          "&#xD800;",
          "&#0;",
          "&#x110000;",
          "&#99999999999;",
          "&#12a;",
          "&#x" + "1".repeat(40) + ";",
          "a & b"
        }) {
      assertEquals(kept, XmlEntities.unescape(kept));
    }
    assertEquals("&&", XmlEntities.unescape("&&amp;"));
  }

  @Test
  void theNamedEntitiesAreHtml401sAndApos() {
    assertEquals(253, XmlEntities.nameCount());
    assertEquals(0xa0, XmlEntities.named("nbsp"));
    assertEquals(0xff, XmlEntities.named("yuml"));
    assertEquals(0x3c2, XmlEntities.named("sigmaf"));
    assertEquals(0x2135, XmlEntities.named("alefsym"));
    assertEquals(0x232a, XmlEntities.named("rang"));
    assertEquals(0x200d, XmlEntities.named("zwj"));
    assertEquals(-1, XmlEntities.named("hellip2"));
  }

  @Test
  void jsonUnescapeReadsEveryEscapeAndEscapedPairs() {
    String escaped = "\\\"\\\\\\/\\b\\f\\n\\r\\t\\" + "u00e9\\" + "uD83D\\" + "ude00" + GRIN;
    assertEquals("\"\\/\b\f\n\r\té" + GRIN + GRIN, JsonStrings.unescape(escaped));
    assertEquals("say \"hi\"", JsonStrings.unescape("say \"hi\""));
  }

  @Test
  void jsonUnescapeRefusesAMalformedOrLoneSurrogateEscapeAtItsBackslash() {
    assertRefused("a\\", Reason.INVALID_ESCAPE, 2);
    assertRefused("\\x", Reason.INVALID_ESCAPE, 1);
    assertRefused("ab\\" + "u12", Reason.INVALID_ESCAPE, 3);
    assertRefused("\\" + "u12G4", Reason.INVALID_ESCAPE, 1);
    assertRefused("\\" + "u１234", Reason.INVALID_ESCAPE, 1);
    assertRefused("\\" + "uD83D", Reason.LONE_SURROGATE_ESCAPE, 1);
    assertRefused("\\" + "uDE00", Reason.LONE_SURROGATE_ESCAPE, 1);
    assertRefused("\\" + "uD83D\\" + "u0041", Reason.LONE_SURROGATE_ESCAPE, 1);
    assertRefused("x\n\\" + "uD83D\uDE00", Reason.LONE_SURROGATE_ESCAPE, 1);
  }

  private static void assertRefused(final String text, Reason reason, int column) {
    FormatException e =
        assertThrows(
            FormatException.class,
            new Executable() {
              @Override
              public void execute() {
                JsonStrings.unescape(text);
              }
            },
            text);
    assertEquals(reason, e.reason(), text);
    assertEquals(column, e.column(), text);
  }
}
