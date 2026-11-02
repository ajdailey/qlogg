package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.text.AnsiCodes;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Search over text with ANSI SGR sequences removed. */
class AnsiSearchTest {
  private static final String LINE = "\u001b[31mer\u001b[0mror at \u001b[1;4mboot";

  private static boolean matches(String pattern, MatchMode mode, boolean hideAnsi) {
    TextEncoding encoding = TextEncoding.of(StandardCharsets.UTF_8, 0);
    CancellationToken token = new CancellationToken();
    SearchQuery query =
        new SearchQuery(
            new SearchExpression.Term(pattern, mode), true, false, false, null, hideAnsi);
    byte[] bytes = LINE.getBytes(StandardCharsets.UTF_8);
    LineText text = new LineText(encoding, token, hideAnsi);
    text.set(ByteBuffer.wrap(bytes), 0, bytes.length);
    return StandardMatchers.INSTANCE.create(query, encoding, token).matches(text);
  }

  @Test
  void literalAndRegexMatchTheStrippedText() {
    assertTrue(matches("error at boot", MatchMode.LITERAL, true));
    assertTrue(matches("^error at b", MatchMode.REGEX, true));
    assertFalse(matches("error at boot", MatchMode.LITERAL, false));
    assertTrue(matches("[31m", MatchMode.LITERAL, false));
    assertFalse(matches("[31m", MatchMode.LITERAL, true));
  }

  @Test
  void onlyCompleteSgrSequencesAreRemoved() {
    assertEquals("error at boot", AnsiCodes.strip(LINE));
    assertEquals("a\u001b[2Jb", AnsiCodes.strip("a\u001b[2Jb"));
    assertEquals("cut \u001b[31", AnsiCodes.strip("cut \u001b[31"));
    String plain = "no escapes";
    assertSame(plain, AnsiCodes.strip(plain));
  }
}
