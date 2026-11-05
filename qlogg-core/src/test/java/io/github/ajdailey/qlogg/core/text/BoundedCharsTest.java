package io.github.ajdailey.qlogg.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
