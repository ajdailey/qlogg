package io.github.ajdailey.qlogg.core.format;

import static io.github.ajdailey.qlogg.core.format.FormatFixture.json;
import static io.github.ajdailey.qlogg.core.format.FormatFixture.jsonError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.format.FormatException.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** JSON pretty print through {@link PrettyPrint#json}. */
class JsonFormatTest {
  @Test
  void eachMemberGoesOnItsOwnLine() {
    String flat = "{\"a\":1,\"b\":[true,null,{}],\"c\":[],\"d\":{\"e\":\"f\"}}";
    String laid =
        "{\n  \"a\": 1,\n  \"b\": [\n    true,\n    null,\n    {}\n  ],\n  \"c\": [],\n"
            + "  \"d\": {\n    \"e\": \"f\"\n  }\n}";
    assertEquals(laid, json(flat));
    assertEquals(laid, json(laid));
    assertEquals("[]", json("[ \n ]"));
    assertEquals("{\n  \"a\": \"x,}  {\"\n}", json("{ \"a\" :\"x,}  {\" }"));
  }

  @Test
  void numbersEscapesAndScalarsStay() {
    String flat =
        "[1.0E10,-0,12345678901234567890,\"\\u00e9\\n\\\"\\/\\\\\",\"\uD83D\uDE00\",-1.5e-3]";
    assertTrue(json(flat).contains("\n  12345678901234567890,\n"));
    assertTrue(json(flat).contains("\n  \"\\u00e9\\n\\\"\\/\\\\\",\n"));
    assertEquals(" 7 \n", json(" 7 \n"));
    assertEquals("\"s\"", json("\"s\""));
  }

  @Test
  void extensionsAreErrorsAtTheirPosition() {
    assertError("[1,]", 3, Reason.EXPECTED_VALUE);
    assertError("{'a':1}", 1, Reason.EXPECTED_NAME_OR_CLOSE);
    assertError("{a:1}", 1, Reason.EXPECTED_NAME_OR_CLOSE);
    assertError("{\"a\":1,}", 7, Reason.EXPECTED_NAME);
    assertError("[NaN]", 1, Reason.EXPECTED_VALUE);
    assertError("[Infinity]", 1, Reason.EXPECTED_VALUE);
    assertError("[-Infinity]", 2, Reason.INVALID_NUMBER);
    assertError("[01]", 2, Reason.EXPECTED_COMMA_OR_BRACKET);
    assertError("[1.]", 3, Reason.INVALID_NUMBER);
    assertError("[\"a\tb\"]", 3, Reason.CONTROL_CHARACTER);
    assertError("[\"\\x\"]", 2, Reason.INVALID_ESCAPE);
    assertError("[\"\\u12\"]", 2, Reason.INVALID_UNICODE_ESCAPE);
    assertError("[\"abc", 1, Reason.UNTERMINATED_STRING);
    assertError("{\"a\" 1}", 5, Reason.EXPECTED_COLON);
    assertError("{\"a\":1 \"b\":2}", 7, Reason.EXPECTED_COMMA_OR_BRACE);
    assertError("[1] [2]", 4, Reason.TEXT_AFTER_VALUE);
    assertError("  ", 2, Reason.EXPECTED_VALUE);
    assertError("[1", 2, Reason.UNEXPECTED_END);
    assertError("[1 /* x", 3, Reason.UNTERMINATED_COMMENT);
    assertError("[1 / 2]", 3, Reason.UNEXPECTED_SLASH);
  }

  private static void assertError(String text, int offset, Reason reason) {
    FormatException e = jsonError(text);
    assertEquals(reason, e.reason(), text);
    assertEquals(offset, e.offset(), text);
  }

  @Test
  void aTruncatedTextFailsAtItsEndWithLineAndColumn() {
    FormatException e = jsonError("{\r\n  \"a\": [1,\n    2,\r    {\"b\": 3");
    assertEquals(Reason.UNEXPECTED_END, e.reason());
    assertEquals(4, e.line());
    assertEquals(12, e.column());
    FormatException value = jsonError("{\r\n  \"a\": tru\r\n}");
    assertEquals(Reason.EXPECTED_VALUE, value.reason());
    assertEquals(2, value.line());
    assertEquals(8, value.column());
    FormatException string = jsonError("[\n\"cut");
    assertEquals(Reason.UNTERMINATED_STRING, string.reason());
    assertEquals(2, string.line());
    assertEquals(1, string.column());
  }

  @Test
  void commentsStayWhereTheyStood() {
    String in =
        "// head\n{ // after open\n\"a\" : 1, // after a\n/* own */ \"b\":[ /* in */ 2\n"
            + "// before close\n]} // tail";
    String out =
        "// head\n{ // after open\n  \"a\": 1, // after a\n  /* own */\n  \"b\": [ /* in */\n"
            + "    2\n    // before close\n  ]\n} // tail";
    assertEquals(out, json(in));
    assertEquals(out, json(out));
  }

  @Test
  void aLineCommentIsFollowedByABreak() {
    assertEquals("{\n  \"a\": // why\n  1\n}", json("{\"a\": // why\n1}"));
    assertEquals("[\n  1 // one\n  ,\n  2\n]", json("[1 // one\n,2]"));
    assertEquals("{\n  \"a\": 1\n} // end", json("{\"a\":1} // end"));
  }

  @Test
  void commentMarksInsideAStringAreText() {
    assertEquals("{\n  \"a\": \"/* x // y\"\n}", json("{\"a\":\"/* x // y\"}"));
  }

  @Test
  void deepNestingFailsOrStopsAtTheLimitWithoutRecursion() {
    int depth = 100_000;
    FormatException truncated = jsonError("[".repeat(depth));
    assertEquals(Reason.UNEXPECTED_END, truncated.reason());
    assertEquals(depth, truncated.offset());
    final String nested = "[".repeat(depth) + "]".repeat(depth);
    assertTrue(nested.length() < 1 << 20);
    FormatException tooLong =
        assertThrows(
            FormatException.class,
            new Executable() {
              @Override
              public void execute() {
                PrettyPrint.json(nested, 1 << 20);
              }
            });
    assertEquals(Reason.TOO_LONG, tooLong.reason());
    String fits = "[".repeat(500) + "1" + "]".repeat(500);
    assertTrue(json(fits).contains("\n" + "  ".repeat(500) + "1\n" + "  ".repeat(499) + "]\n"));
  }

  @Test
  void aLargeTextFormats() {
    StringBuilder flat = new StringBuilder("[");
    int items = 20_000;
    for (int i = 0; i < items; i++) {
      flat.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append(",\"v\":\"x y\"}");
    }
    flat.append("]");
    String laid = json(flat.toString());
    assertTrue(laid.endsWith("    \"v\": \"x y\"\n  }\n]"));
  }
}
