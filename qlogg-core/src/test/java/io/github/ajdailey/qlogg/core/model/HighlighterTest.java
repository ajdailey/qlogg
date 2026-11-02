package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class HighlighterTest {
  private static final Rgb RED = Rgb.of(0xff0000);
  private static final Rgb BLUE = Rgb.of(0x0000ff);
  private static final Rgb GREEN = Rgb.of(0x00ff00);
  private static final int OPAQUE = 0xff000000;

  private static HighlightSpans spans(String line, HighlightRule... rules) {
    return Highlighter.of(List.of(rules)).matcher().spans(line, 0, 1000);
  }

  private static String describe(HighlightSpans spans) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < spans.count(); i++) {
      out.append('[')
          .append(spans.start(i))
          .append(',')
          .append(spans.end(i))
          .append(' ')
          .append(Integer.toHexString(spans.background(i)))
          .append(']');
    }
    return out.toString();
  }

  private static HighlightRule word(String pattern, Rgb background) {
    return HighlightRule.matchOnly(pattern, MatchMode.REGEX, null, background);
  }

  @Test
  void matchOnlyRuleColorsEachMatch() {
    HighlightSpans spans = spans("ab ERROR cd ERROR", word("ERROR", RED));
    assertEquals("[3,8 ffff0000][12,17 ffff0000]", describe(spans));
    assertEquals(0, spans.lineBackground());
    assertEquals(0, spans.foreground(0));
  }

  @Test
  void theLaterRuleWinsWhereSpansOverlap() {
    HighlightSpans spans = spans("0123456789", word("1234", RED), word("3456", BLUE));
    assertEquals("[1,3 ffff0000][3,7 ff0000ff]", describe(spans));
    HighlightSpans inside = spans("0123456789", word("1234567", RED), word("34", BLUE));
    assertEquals("[1,3 ffff0000][3,5 ff0000ff][5,8 ffff0000]", describe(inside));
    HighlightSpans covered = spans("0123456789", word("34", BLUE), word("1234567", RED));
    assertEquals("[1,8 ffff0000]", describe(covered));
  }

  @Test
  void wholeLineRuleSetsLineColorsAndDropsEarlierSpans() {
    HighlightRule line =
        new HighlightRule("WARN", MatchMode.LITERAL, true, true, GREEN, BLUE, 255, 0, true);
    HighlightSpans spans = spans("x WARN y", word("x", RED), line, word("y", RED));
    assertEquals(OPAQUE | 0x00ff00, spans.lineForeground());
    assertEquals(OPAQUE | 0x0000ff, spans.lineBackground());
    assertEquals("[7,8 ffff0000]", describe(spans));
    assertTrue(spans("no match", line).isEmpty());
  }

  @Test
  void captureGroupsColorOnlyTheGroups() {
    HighlightSpans spans = spans("user=alice id=7", word("user=(\\w+) id=(\\d)", RED));
    assertEquals("[5,10 ffff0000][14,15 ffff0000]", describe(spans));
    HighlightSpans optional = spans("ab", word("a(x)?(b)", RED));
    assertEquals("[1,2 ffff0000]", describe(optional));
  }

  @Test
  void caseFoldingAndLiteralMode() {
    HighlightRule folded = word("error", RED).withCaseSensitive(false);
    assertEquals("[0,5 ffff0000]", describe(spans("ERROR", folded)));
    assertTrue(spans("ERROR", word("error", RED)).isEmpty());
    HighlightRule literal = HighlightRule.matchOnly("a.c", MatchMode.LITERAL, null, RED);
    assertTrue(spans("abc", literal).isEmpty());
    assertEquals("[0,3 ffff0000]", describe(spans("a.c", literal)));
  }

  @Test
  void alphaAndDisabledRules() {
    HighlightRule faint = word("b", RED).withAlphaAndVariance(0x40, 0);
    assertEquals("[1,2 40ff0000]", describe(spans("abc", faint)));
    HighlightRule clear = word("b", RED).withAlphaAndVariance(0, 0);
    assertEquals(0, spans("abc", clear).background(0));
    assertSame(Highlighter.NONE, Highlighter.of(List.of(word("b", RED).withEnabled(false))));
    assertSame(Highlighter.NONE, Highlighter.of(List.of(word("", RED))));
  }

  @Test
  void varianceGivesEachStringItsOwnStableColor() {
    HighlightRule varied = word("[a-z]+", Rgb.of(0x808080)).withAlphaAndVariance(255, 30);
    HighlightSpans spans = spans("alpha beta alpha", varied);
    assertEquals(3, spans.count());
    assertEquals(spans.background(0), spans.background(2));
    assertNotEquals(spans.background(0), spans.background(1));
    for (int i = 0; i < 50; i++) {
      Rgb color = Highlighter.vary(Rgb.of(0xffffff), "s" + i, 50);
      assertTrue(color.red() >= 0 && color.red() <= 255);
    }
    Rgb dark = Highlighter.vary(Rgb.of(0x808080), "x", 1);
    assertTrue(Math.abs(dark.red() - 0x80) <= 2);
  }

  @Test
  void spansUseDisplayColumnsAndClipToTheWindow() {
    HighlightSpans spans =
        Highlighter.of(List.of(word("X", RED))).matcher().spans("\tX\tX", 0, 1000);
    assertEquals("[8,9 ffff0000][16,17 ffff0000]", describe(spans));
    HighlightSpans clipped =
        Highlighter.of(List.of(word("abcdef", RED))).matcher().spans("..abcdef..", 4, 3);
    assertEquals("[4,7 ffff0000]", describe(clipped));
    HighlightSpans outside =
        Highlighter.of(List.of(word("ab", RED))).matcher().spans("ab......", 5, 3);
    assertEquals(0, outside.count());
  }

  @Test
  void spanCountIsCapped() {
    StringBuilder line = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      line.append("x ");
    }
    HighlightSpans spans = spans(line.toString(), word("x", RED));
    assertEquals(Highlighter.MAX_SPANS, spans.count());
    assertEquals(
        HighlightSpans.OVERHEAD + HighlightSpans.BYTES_PER_SPAN * Highlighter.MAX_SPANS,
        spans.retainedBytes());
  }

  @Test
  void aBacktrackingPatternIsAbandonedOnTheLine() {
    StringBuilder line = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      line.append('a');
    }
    line.append('!');
    long started = System.nanoTime();
    HighlightSpans spans = spans(line.toString(), word("(a+)+$", RED), word("!", BLUE));
    assertTrue(System.nanoTime() - started < 5_000_000_000L);
    assertEquals("[40,41 ff0000ff]", describe(spans));
  }

  @Test
  void invalidPatternsAreSkippedAndReported() {
    assertThrows(
        java.util.regex.PatternSyntaxException.class,
        new Executable() {
          @Override
          public void execute() {
            Highlighter.compile(word("(", RED));
          }
        });
    assertEquals("[0,1 ffff0000]", describe(spans("b", word("(", BLUE), word("b", RED))));
  }

  @Test
  void ruleChecksItsComponents() {
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            word("x", RED).withAlphaAndVariance(256, 0);
          }
        });
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            word("x", RED).withAlphaAndVariance(255, HighlightRule.MAX_VARIANCE + 1);
          }
        });
  }

  @Test
  void readerAttachesSpansToRows() {
    LineBlock block =
        new LineBlock(
            0,
            0,
            10,
            new String[] {"a"},
            new long[] {1},
            null,
            new HighlightSpans[] {spans("a", word("a", RED))});
    assertEquals(1, block.spans(0).count());
    assertEquals(block.spans(0).retainedBytes(), block.spanBytes());
    assertSame(HighlightSpans.NONE, block.withoutSpans().spans(0));
  }
}
