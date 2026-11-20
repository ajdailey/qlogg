package io.github.ajdailey.qlogg.core.format;

import static io.github.ajdailey.qlogg.core.format.FormatFixture.xml;
import static io.github.ajdailey.qlogg.core.format.FormatFixture.xmlError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.format.FormatException.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** XML pretty print and its tag-balance check through {@link PrettyPrint#xml}. */
class XmlFormatTest {
  @Test
  void eachElementGoesOnItsOwnLine() {
    String flat = "<a><b>x</b><c/><d><e k='1'/></d></a>";
    String laid = "<a>\n  <b>x</b>\n  <c/>\n  <d>\n    <e k='1'/>\n  </d>\n</a>";
    assertEquals(laid, xml(flat));
    assertEquals(laid, xml(laid));
  }

  @Test
  void attributeSpacingAndTextOnlyContentStay() {
    assertEquals(
        "<a  x = \"1\"\n   y='>2' >\n  <b> two  words </b>\n</a>",
        xml("<a  x = \"1\"\n   y='>2' ><b> two  words </b></a>"));
  }

  @Test
  void anElementWithOnlyWhitespaceBecomesEmpty() {
    assertEquals("<a>\n  <b></b>\n</a>", xml("<a><b>  \n </b></a>"));
  }

  @Test
  void prologDoctypeCommentsPisAndCdataStayVerbatim() {
    String in =
        "<?xml version=\"1.0\"?><!DOCTYPE a [\n <!ENTITY e \"]>\">\n <!-- ]> -->\n]>"
            + "<!--  c  --><a><?pi  x ?><s><![CDATA[ <x> ]]></s></a>";
    assertEquals(
        "<?xml version=\"1.0\"?>\n<!DOCTYPE a [\n <!ENTITY e \"]>\">\n <!-- ]> -->\n]>\n"
            + "<!--  c  -->\n<a>\n  <?pi  x ?>\n  <s><![CDATA[ <x> ]]></s>\n</a>",
        xml(in));
  }

  @Test
  void mixedContentStaysVerbatimToItsEnd() {
    String p = "<p>Hi <b><i>x</i> </b>there<q><r/></q></p>";
    assertEquals("<d>\n  " + p + "\n  <e/>\n</d>", xml("<d>" + p + "<e/></d>"));
  }

  @Test
  void preserveKeepsItsSubtreeAndDefaultResets() {
    String preserved = "<s xml:space=\"preserve\"> <t> <u/> </t> </s>";
    assertEquals("<r>\n  " + preserved + "\n</r>", xml("<r>" + preserved + "</r>"));
    assertEquals(
        "<s xml:space='preserve'> <d xml:space=\"default\">\n    <e/>\n  </d> </s>",
        xml("<s xml:space='preserve'> <d xml:space=\"default\"><e/></d> </s>"));
  }

  @Test
  void edgesStay() {
    assertEquals("\r\n<a>\n  <b/>\n</a>\r\n", xml("\r\n<a>\r\n<b/></a>\r\n"));
  }

  @Test
  void entityReferencesAreKeptAndNothingIsRead() {
    String text = "<!DOCTYPE a SYSTEM \"file:///mock.dtd\" [<!ENTITY e SYSTEM \"mock.xml\">]>";
    assertEquals(
        text + "\n<a>\n  <b>&e;&undeclared;</b>\n</a>",
        xml(text + "<a><b>&e;&undeclared;</b></a>"));
  }

  @Test
  void severalTopLevelItemsGoOnePerLine() {
    assertEquals("<a>\n  <b/>\n</a>\n<c/>", xml("<a><b/></a><c/>"));
    assertEquals("<!-- c -->\n<a/>\n<b/>", xml("<!-- c --><a/> <b/>"));
    String mixed = "<a> <x/> </a> text <b>\n<c/></b>";
    assertEquals(mixed, xml(mixed));
  }

  @Test
  void anUnbalancedTagFailsAtItsName() {
    FormatException mismatch = xmlError("<a>\n  <b>x</c></a>");
    assertEquals(Reason.MISMATCHED_END_TAG, mismatch.reason());
    assertEquals("b", mismatch.detail());
    assertEquals(2, mismatch.line());
    assertEquals(9, mismatch.column());
    FormatException unopened = xmlError("<a/></b >");
    assertEquals(Reason.UNOPENED_END_TAG, unopened.reason());
    assertEquals("b", unopened.detail());
    assertEquals(6, unopened.offset());
    assertEquals("</a >", xml("<a></a >").substring(3));
  }

  @Test
  void aTruncatedTextFailsWithLineAndColumn() {
    FormatException open = xmlError("<log>\r\n  <entry id=\"1\">\n    <msg>x</msg>");
    assertEquals(Reason.UNCLOSED_ELEMENT, open.reason());
    assertEquals("entry", open.detail());
    assertEquals(3, open.line());
    assertEquals(17, open.column());
    assertUnterminated("<a>\n  <b x='1", Reason.UNTERMINATED_QUOTE, 2, 8);
    assertUnterminated("<a>\n  <b x='1' ", Reason.UNTERMINATED_TAG, 2, 3);
    assertUnterminated("<a>\n  </a", Reason.UNTERMINATED_TAG, 2, 3);
    assertUnterminated("<a><!-- c </a>", Reason.UNTERMINATED_COMMENT, 1, 4);
    assertUnterminated("<a><![CDATA[ x </a>", Reason.UNTERMINATED_CDATA, 1, 4);
    assertUnterminated("<a><?pi x </a>", Reason.UNTERMINATED_PI, 1, 4);
    assertUnterminated("<!DOCTYPE a [ <!ENTITY e 'x'>", Reason.UNTERMINATED_DOCTYPE, 1, 1);
  }

  private static void assertUnterminated(String text, Reason reason, int line, int column) {
    FormatException e = xmlError(text);
    assertEquals(reason, e.reason(), text);
    assertEquals(line, e.line(), text);
    assertEquals(column, e.column(), text);
  }

  @Test
  void aSupplementaryCharCountsTwoColumns() {
    FormatException e = xmlError("<a>\r\n😀x</b>");
    assertEquals(2, e.line());
    assertEquals(6, e.column());
  }

  @Test
  void deepNestingFormatsWithoutRecursion() {
    int depth = 100_000;
    String preserved =
        "<r xml:space=\"preserve\">" + "<a>".repeat(depth) + "</a>".repeat(depth) + "</r>";
    assertTrue(preserved.length() < 1 << 20);
    assertEquals(preserved, PrettyPrint.xml(preserved, 1 << 20));
    FormatException open = xmlError("<a>".repeat(depth));
    assertEquals(Reason.UNCLOSED_ELEMENT, open.reason());
    assertEquals(3 * depth, open.offset());
    final String nested = "<a>".repeat(depth) + "</a>".repeat(depth);
    FormatException tooLong =
        assertThrows(
            FormatException.class,
            new Executable() {
              @Override
              public void execute() {
                PrettyPrint.xml(nested, 1 << 20);
              }
            });
    assertEquals(Reason.TOO_LONG, tooLong.reason());
  }

  @Test
  void aLargeTextFormats() {
    StringBuilder flat = new StringBuilder("<r>");
    int items = 20_000;
    for (int i = 0; i < items; i++) {
      flat.append("<item id=\"").append(i).append("\"><v>value ").append(i).append("</v></item>");
    }
    flat.append("</r>");
    String laid = xml(flat.toString());
    assertTrue(laid.endsWith("    <v>value " + (items - 1) + "</v>\n  </item>\n</r>"));
  }
}
