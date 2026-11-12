package io.github.ajdailey.qlogg.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class BoundedCharsTest {
  @Test
  void readsPastTheLimitThrowAndResetStartsOver() {
    final BoundedChars chars = new BoundedChars().reset("abc", 2);
    assertEquals('a', chars.charAt(0));
    assertEquals('b', chars.charAt(1));
    assertThrows(
        BoundedChars.LimitReached.class,
        new Executable() {
          @Override
          public void execute() {
            chars.charAt(2);
          }
        });
    assertEquals(2, chars.used());
    chars.reset("xyz", 10);
    assertEquals(0, chars.used());
    assertEquals('z', chars.charAt(2));
    assertEquals(3, chars.length());
    assertEquals("yz", chars.subSequence(1, 3).toString());
  }

  @Test
  void aBacktrackingRegexEndsAtTheLineLimit() {
    String line = "a".repeat(32) + "!";
    final BoundedChars chars = new BoundedChars().reset(line, BoundedChars.lineLimit(line));
    assertEquals(8L * 33 + BoundedChars.BASE_READS, BoundedChars.lineLimit(line));
    long started = System.nanoTime();
    assertThrows(
        BoundedChars.LimitReached.class,
        new Executable() {
          @Override
          public void execute() {
            Pattern.compile("(.*a){12}$").matcher(chars).find();
          }
        });
    assertTrue(System.nanoTime() - started < 5_000_000_000L);
    assertEquals(BoundedChars.lineLimit(line), chars.used());
  }

  @Test
  void findTakesTheLimitAndAStackOverflowAsNoMatch() {
    BoundedChars chars = new BoundedChars();
    assertTrue(chars.find(Pattern.compile("b+").matcher(""), "abbc"));
    String line = "a".repeat(32) + "!";
    assertFalse(chars.find(Pattern.compile("(.*a){12}$").matcher(""), line));
    assertEquals(BoundedChars.lineLimit(line), chars.used());
    assertFalse(chars.find(Pattern.compile("(a|b)*c").matcher(""), "a".repeat(400_000)));
  }

  @Test
  void aCanceledTokenEndsARegexRun() {
    CancellationToken token = new CancellationToken();
    token.cancel();
    final BoundedChars chars = new BoundedChars(token);
    final Matcher matcher = Pattern.compile("x").matcher("");
    assertTrue(chars.find(matcher, "x"));
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            chars.find(matcher, "a".repeat(BoundedChars.CHECK_CHARS) + "x");
          }
        });
  }
}
