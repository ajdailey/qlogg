package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The ported log rules, one positive and one look-around case each; tie order; the quoted string
 * and its escape; bounds; column clipping; and the first-char tables against a scan that tries
 * every rule at every position.
 */
class LogTokensTest {
  private static TokenSpans tokens(String line) {
    return LogTokens.matcher().spans(line, 0, 1000);
  }

  /** Lists each span as {@code KIND:text}; columns are chars on a line without tabs. */
  private static String describe(String line, TokenSpans spans) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < spans.count(); i++) {
      if (i > 0) {
        out.append(' ');
      }
      out.append(spans.kind(i)).append(':').append(line, spans.start(i), spans.end(i));
    }
    return out.toString();
  }

  static List<Arguments> rules() {
    List<Arguments> cases = new ArrayList<Arguments>();
    // Stack frames and continuation lines.
    cases.add(Arguments.of("    at io.mock.Worker.run(Worker.java:42)", "COMMENT:*"));
    cases.add(Arguments.of("  ... 12 more", "COMMENT:*"));
    cases.add(Arguments.of("  File \"mock.py\", line 3, in run", "COMMENT:*"));
    cases.add(Arguments.of("at io.mock.run", ""));
    cases.add(
        Arguments.of(
            "Caused by: io.mock.MockException: boom",
            "KEYWORD:Caused by: KEYWORD:io.mock.MockException"));
    cases.add(Arguments.of("Suppressed: x", "KEYWORD:Suppressed:"));
    cases.add(Arguments.of("Traceback (most recent call last):", "KEYWORD:*"));
    cases.add(Arguments.of("x Caused by: y", ""));
    // Timestamps.
    cases.add(Arguments.of("2026-11-14T10:00:00.123Z go", "NUMBER:2026-11-14T10:00:00.123Z"));
    cases.add(Arguments.of("2026/11/14 10:00:00 go", "NUMBER:2026/11/14 10:00:00"));
    cases.add(Arguments.of("Nov 14 10:00:00 host", "NUMBER:Nov 14 10:00:00"));
    cases.add(
        Arguments.of(
            "h - - [14/Nov/2026:10:00:00 +0000] go", "NUMBER:[14/Nov/2026:10:00:00 +0000]"));
    cases.add(Arguments.of("at 10:00:00,5 go", "NUMBER:10:00:00,5"));
    cases.add(Arguments.of("v2026-11-14 10:00:00x", ""));
    // Error and fatal.
    cases.add(Arguments.of("ERROR x", "INVALID:ERROR"));
    cases.add(Arguments.of("ERRORS x", ""));
    cases.add(Arguments.of("my.ERROR x", "FUNCTION:my.ERROR"));
    cases.add(Arguments.of("level=error x", "ATTRIBUTE:level INVALID:error"));
    cases.add(Arguments.of("xlevel=error x", "ATTRIBUTE:xlevel"));
    cases.add(Arguments.of("{\"level\":\"error\"}", "STRING:\"level\" INVALID:\"error\""));
    cases.add(Arguments.of("{\"lvl\":\"error\"}", "STRING:\"lvl\" STRING:\"error\""));
    cases.add(Arguments.of("[E] x", "INVALID:E"));
    cases.add(Arguments.of("E/MockTag(123): x", "INVALID:E"));
    cases.add(Arguments.of("E1114 10:00:00.000 x", "INVALID:E NUMBER:10:00:00.000"));
    cases.add(Arguments.of("[EX] x", "DELIMITER:[EX]"));
    // Warnings.
    cases.add(Arguments.of("WARN x", "ESCAPE:WARN"));
    cases.add(Arguments.of("WARN.x", ""));
    cases.add(Arguments.of("lvl=warning", "ATTRIBUTE:lvl ESCAPE:warning"));
    cases.add(Arguments.of("{\"severity\": \"WARN\"}", "STRING:\"severity\" ESCAPE:\"WARN\""));
    cases.add(Arguments.of("<W> x", "ESCAPE:W"));
    cases.add(Arguments.of("<Wx> x", ""));
    // Info.
    cases.add(Arguments.of("INFO x", "TYPE:INFO"));
    cases.add(Arguments.of("-INFO x", ""));
    cases.add(Arguments.of("severity=notice", "ATTRIBUTE:severity TYPE:notice"));
    cases.add(Arguments.of("{\"level\":\"info\"}", "STRING:\"level\" TYPE:\"info\""));
    cases.add(Arguments.of("[INFO] x", "TYPE:INFO"));
    cases.add(Arguments.of("I1114 10:00:00 x", "TYPE:I NUMBER:10:00:00"));
    // Debug.
    cases.add(Arguments.of("DEBUG x", "KEYWORD2:DEBUG"));
    cases.add(Arguments.of("_DEBUG x", ""));
    cases.add(Arguments.of("level=debug", "ATTRIBUTE:level KEYWORD2:debug"));
    cases.add(Arguments.of("{\"level\":\"debug\"}", "STRING:\"level\" KEYWORD2:\"debug\""));
    cases.add(Arguments.of("D/MockTag: x", "KEYWORD2:D"));
    cases.add(Arguments.of("D1114 10:00:00 x", "NUMBER:10:00:00"));
    // Trace.
    cases.add(Arguments.of("TRACE x", "COMMENT:TRACE"));
    cases.add(Arguments.of("TRACE-x", ""));
    cases.add(Arguments.of("level=verbose", "ATTRIBUTE:level COMMENT:verbose"));
    cases.add(Arguments.of("{\"level\":\"trace\"}", "STRING:\"level\" COMMENT:\"trace\""));
    // Serilog CLEF levels.
    cases.add(Arguments.of("{\"@l\":\"Verbose\"}", "STRING:\"@l\" COMMENT:\"Verbose\""));
    cases.add(Arguments.of("{\"@l\":\"Information\"}", "STRING:\"@l\" TYPE:\"Information\""));
    cases.add(Arguments.of("{\"@l\":\"Warning\"}", "STRING:\"@l\" ESCAPE:\"Warning\""));
    cases.add(Arguments.of("{\"@l\":\"Fatal\"}", "STRING:\"@l\" INVALID:\"Fatal\""));
    cases.add(Arguments.of("[V] x", "COMMENT:V"));
    cases.add(Arguments.of("xT/MockTag: x", ""));
    // Groups, links, exceptions, classes, keys.
    cases.add(Arguments.of("[main] x", "DELIMITER:[main]"));
    cases.add(Arguments.of("[ warn ] x", ""));
    cases.add(Arguments.of("see https://mock.example/a?b go", "LINK:https://mock.example/a?b"));
    cases.add(Arguments.of("a-https://mock.example/a", ""));
    cases.add(Arguments.of("io.mock.MockException thrown", "KEYWORD:io.mock.MockException"));
    cases.add(Arguments.of("MockExceptionHandler x", ""));
    cases.add(Arguments.of("io.mock.Worker started", "FUNCTION:io.mock.Worker"));
    cases.add(Arguments.of("Io.mock.Worker started", ""));
    cases.add(Arguments.of("user=mock", "ATTRIBUTE:user"));
    cases.add(Arguments.of("user = mock", ""));
    return cases;
  }

  @ParameterizedTest
  @MethodSource("rules")
  void eachRuleMatchesItsTextAndNotItsLookAround(String line, String expected) {
    String want =
        expected.equals("COMMENT:*") || expected.equals("KEYWORD:*")
            ? whole(expected, line)
            : expected;
    assertEquals(want, describe(line, tokens(line)));
  }

  private static String whole(String expected, String line) {
    return expected.substring(0, expected.indexOf(':') + 1) + line;
  }

  /** The first rule matching at the earliest position wins; a longer later match does not. */
  @Test
  void theFirstRuleInOrderWinsAtAPosition() {
    assertEquals("INVALID:ERROR", describe("ERROR=x", tokens("ERROR=x")));
    assertEquals(
        "KEYWORD:io.mock.MockError", describe("io.mock.MockError", tokens("io.mock.MockError")));
    String line = "x [14/Nov/2026:10:00:00 +0000]";
    assertEquals("NUMBER:[14/Nov/2026:10:00:00 +0000]", describe(line, tokens(line)));
  }

  @Test
  void aStringHoldsItsEscapesAndEndsAtTheQuoteOrTheLineEnd() {
    String line = "say \"a\\\"b\" end";
    assertEquals("STRING:\"a ESCAPE:\\\" STRING:b\"", describe(line, tokens(line)));
    String open = "x \"open \\";
    assertEquals("STRING:\"open \\", describe(open, tokens(open)));
    String rules = "\"ERROR\" ERROR";
    assertEquals("STRING:\"ERROR\" INVALID:ERROR", describe(rules, tokens(rules)));
  }

  @Test
  void spansAreClippedToTheColumnWindowWithTabsExpanded() {
    String line = "\tERROR x";
    TokenSpans all = LogTokens.matcher().spans(line, 0, 100);
    assertEquals(1, all.count());
    assertEquals(8, all.start(0));
    assertEquals(13, all.end(0));
    TokenSpans cut = LogTokens.matcher().spans(line, 10, 2);
    assertEquals(1, cut.count());
    assertEquals(10, cut.start(0));
    assertEquals(12, cut.end(0));
    assertSame(TokenSpans.NONE, LogTokens.matcher().spans(line, 0, 8));
  }

  @Test
  void aLineKeepsAtMostMaxSpans() {
    String line = "[a] ".repeat(Highlighter.MAX_SPANS + 10);
    assertEquals(Highlighter.MAX_SPANS, LogTokens.matcher().spans(line, 0, 100_000).count());
  }

  /** A spent block budget colors no further line. */
  @Test
  void aMatcherStopsColoringOnceItsBudgetIsSpent() {
    LogTokens.LineMatcher matcher = LogTokens.matcher(true, 2000);
    assertEquals(1, matcher.spans("ERROR x", 0, 100).count());
    matcher.spans("x ".repeat(5000), 0, 100_000);
    assertSame(TokenSpans.NONE, matcher.spans("ERROR x", 0, 100));
  }

  /**
   * A rule that overflows the regex stack on a long line is abandoned for that line; the tokens
   * found before it stay, and the next line is tokenized again. The small thread stack makes the
   * overflow certain.
   */
  @Test
  void aRuleThatOverflowsTheStackIsAbandonedForTheLine() throws Exception {
    final String line = "ERROR " + "a.".repeat(200_000) + "B";
    final AtomicReference<String> result = new AtomicReference<String>();
    final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
    Thread thread =
        new Thread(
            null,
            new Runnable() {
              @Override
              public void run() {
                LogTokens.LineMatcher matcher = LogTokens.matcher();
                String first = describe(line, matcher.spans(line, 0, 20));
                String next = describe("ERROR y", matcher.spans("ERROR y", 0, 20));
                result.set(first + "|" + next);
              }
            },
            "mock-small-stack",
            1 << 18);
    thread.setUncaughtExceptionHandler(
        new Thread.UncaughtExceptionHandler() {
          @Override
          public void uncaughtException(Thread t, Throwable e) {
            failure.set(e);
          }
        });
    thread.start();
    thread.join(60_000);
    assertEquals(null, failure.get());
    assertEquals("INVALID:ERROR|INVALID:ERROR", result.get());
  }

  @Test
  void retainedBytesCountEachSpan() {
    TokenSpans spans = tokens("ERROR [main] x");
    assertEquals(2, spans.count());
    assertEquals(TokenSpans.OVERHEAD + 2L * TokenSpans.BYTES_PER_SPAN, spans.retainedBytes());
    assertEquals(0, TokenSpans.NONE.retainedBytes());
    LineBlock block =
        new LineBlock(
            0,
            0,
            10,
            new String[] {"a"},
            new long[] {1},
            null,
            null,
            new TokenSpans[] {spans},
            null,
            null);
    assertEquals(spans.retainedBytes(), block.spanBytes());
    assertSame(TokenSpans.NONE, block.withoutSpans().tokens(0));
    assertEquals(0, block.withoutSpans().spanBytes());
  }

  @Test
  void patternAnalysisDropsAssertionsAndFindsTheRequiredPreviousChar() {
    assertEquals("x(?:a|b)", LogTokens.widen("(?<![\\w.])\\bx(?=y)(?:a|b)$"));
    assertEquals("[^\\]]", LogTokens.widen("^[^\\]]"));
    assertEquals(List.of("a(b|c)", "[|]", "d"), LogTokens.alternatives("a(b|c)|[|]|d"));
    assertEquals('=', LogTokens.previous("(?<![\\w.-])(?<=\\b(?:level|lvl)=)x"));
    assertEquals(-1, LogTokens.previous("(?<=\"level\":\\s?)x"));
    assertEquals(-1, LogTokens.previous("(?<=a|b=)x"));
    assertEquals(-1, LogTokens.previous("(?<=\\=)x"));
  }

  /**
   * The first-char tables give the same tokens as trying every rule at every position, over random
   * lines built from log fragments.
   */
  @Test
  void firstCharTablesMatchAScanOfEveryRule() {
    String[] parts = {
      " ",
      "x",
      "my.",
      "io.mock.",
      "Worker",
      "MockException",
      "ERROR",
      "error",
      "WARN",
      "INFO",
      "DEBUG",
      "TRACE",
      "E",
      "W",
      "I",
      "D",
      "T",
      "V",
      "[",
      "]",
      "<",
      ">",
      "/",
      "Tag",
      ":",
      "(12)",
      "=",
      "level",
      "lvl",
      "\"",
      "\\",
      "2026-11-14",
      "T",
      "10:00:00",
      ".123",
      "Z",
      "+01:00",
      "Nov 14 ",
      "14/Nov/2026",
      " +0000",
      "https://",
      "file://",
      "Caused by",
      "    at ",
      "... 3 more",
      "_",
      "-",
      ".",
      "$",
      "é",
      "日"
    };
    SplittableRandom random = new SplittableRandom(11);
    LogTokens.LineMatcher tabled = LogTokens.matcher(true, Long.MAX_VALUE);
    LogTokens.LineMatcher every = LogTokens.matcher(false, Long.MAX_VALUE);
    for (int n = 0; n < 20_000; n++) {
      StringBuilder line = new StringBuilder();
      int pieces = 1 + random.nextInt(12);
      for (int k = 0; k < pieces; k++) {
        line.append(parts[random.nextInt(parts.length)]);
      }
      String text = line.toString();
      String want = describe(text, every.spans(text, 0, 500));
      String got = describe(text, tabled.spans(text, 0, 500));
      assertEquals(want, got, text);
    }
  }
}
