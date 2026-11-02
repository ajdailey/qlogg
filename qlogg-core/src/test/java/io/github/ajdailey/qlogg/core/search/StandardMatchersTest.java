package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class StandardMatchersTest {
  private static final Charset[] CHARSETS = {
    StandardCharsets.UTF_8,
    StandardCharsets.ISO_8859_1,
    Charset.forName("windows-1252"),
    StandardCharsets.UTF_16LE,
    StandardCharsets.UTF_16BE
  };

  /** Matches one line; the line sits after two units of other text in the buffer. */
  private static boolean matches(SearchQuery query, Charset charset, String line) {
    TextEncoding encoding = TextEncoding.of(charset, 0);
    CancellationToken token = new CancellationToken();
    byte[] prefix = "zz".getBytes(encoding.charset());
    byte[] text = line.getBytes(encoding.charset());
    ByteBuffer buffer = ByteBuffer.allocate(prefix.length + text.length + prefix.length);
    buffer.put(prefix).put(text).put(prefix);
    LineText lineText = new LineText(encoding, token);
    lineText.set(buffer, prefix.length, prefix.length + text.length);
    return StandardMatchers.INSTANCE.create(query, encoding, token).matches(lineText);
  }

  private static SearchQuery query(
      String pattern, MatchMode mode, boolean caseSensitive, boolean wholeWord) {
    return new SearchQuery(
        new SearchExpression.Term(pattern, mode), caseSensitive, wholeWord, false, null);
  }

  private static boolean wordChar(char c) {
    return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_';
  }

  /** Reference: an occurrence of the pattern, ASCII case folded when asked, bounded when asked. */
  private static boolean reference(
      String line, String pattern, boolean caseSensitive, boolean wholeWord) {
    for (int i = 0; i + pattern.length() <= line.length(); i++) {
      boolean equal = true;
      for (int k = 0; k < pattern.length() && equal; k++) {
        char a = line.charAt(i + k);
        char b = pattern.charAt(k);
        equal = a == b || !caseSensitive && b < 0x80 && a < 0x80 && fold(a) == fold(b);
      }
      int after = i + pattern.length();
      boolean bounded =
          (i == 0 || !wordChar(line.charAt(i - 1)))
              && (after == line.length() || !wordChar(line.charAt(after)));
      if (equal && (!wholeWord || bounded)) {
        return true;
      }
    }
    return false;
  }

  private static char fold(char c) {
    return c >= 'A' && c <= 'Z' ? (char) (c + 32) : c;
  }

  @Test
  void literalBytesAgreeWithQuotedRegexOnRandomCorpora() {
    SplittableRandom random = new SplittableRandom(20261101);
    for (Charset charset : CHARSETS) {
      String alphabet = encodable("aAbB_ 1-é中€", charset);
      TextEncoding encoding = TextEncoding.of(charset, 0);
      for (int round = 0; round < 1500; round++) {
        String line = random(random, alphabet, random.nextInt(0, 24));
        String pattern =
            random.nextInt(3) == 0 && !line.isEmpty()
                ? substring(random, line)
                : random(random, alphabet, random.nextInt(1, 4));
        boolean caseSensitive = random.nextBoolean();
        boolean wholeWord = random.nextBoolean();
        SearchQuery literal = query(pattern, MatchMode.LITERAL, caseSensitive, wholeWord);
        SearchQuery regex =
            query(Pattern.quote(pattern), MatchMode.REGEX, caseSensitive, wholeWord);
        boolean expected = reference(line, pattern, caseSensitive, wholeWord);
        String context = charset + " line=" + line + " pattern=" + pattern + " cs=" + caseSensitive;
        assertEquals(expected, matches(literal, charset, line), context + " ww=" + wholeWord);
        assertEquals(expected, matches(regex, charset, line), context + " regex ww=" + wholeWord);
      }
      SearchExpression.Term ascii = new SearchExpression.Term("aB", MatchMode.LITERAL);
      assertTrue(
          StandardMatchers.bytewise(ascii, query("aB", MatchMode.LITERAL, true, false), encoding),
          charset.name());
    }
  }

  private static String encodable(String chars, Charset charset) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < chars.length(); i++) {
      if (charset.newEncoder().canEncode(chars.charAt(i))) {
        out.append(chars.charAt(i));
      }
    }
    return out.toString();
  }

  private static String random(SplittableRandom random, String alphabet, int length) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < length; i++) {
      out.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return out.toString();
  }

  private static String substring(SplittableRandom random, String line) {
    int from = random.nextInt(line.length());
    return line.substring(from, random.nextInt(from + 1, line.length() + 1));
  }

  @Test
  void aCaseSensitiveRegexWithoutMetacharactersRunsAsBytes() {
    TextEncoding utf8 = TextEncoding.of(StandardCharsets.UTF_8, 0);
    SearchExpression.Term plain = new SearchExpression.Term("worker 1", MatchMode.REGEX);
    SearchExpression.Term dotted = new SearchExpression.Term("worker.1", MatchMode.REGEX);
    assertTrue(
        StandardMatchers.bytewise(plain, query("worker 1", MatchMode.REGEX, true, false), utf8));
    assertFalse(
        StandardMatchers.bytewise(dotted, query("worker.1", MatchMode.REGEX, true, false), utf8));
    assertFalse(
        StandardMatchers.bytewise(plain, query("worker 1", MatchMode.REGEX, false, false), utf8));
  }

  /** A plain regex run as bytes finds what {@link Pattern} finds, with and without whole word. */
  @Test
  void aPlainRegexMatchesAsItsPattern() {
    SplittableRandom random = new SplittableRandom(11);
    String alphabet = "ab _-1\u00e9";
    for (Charset charset : CHARSETS) {
      String chars = encodable(alphabet, charset);
      for (int i = 0; i < 400; i++) {
        String line = random(random, chars, 1 + random.nextInt(12));
        String pattern = random(random, "ab _-1", 1 + random.nextInt(3));
        boolean wholeWord = random.nextBoolean();
        String regex = wholeWord ? "(?<!\\w)(?:" + pattern + ")(?!\\w)" : pattern;
        boolean expected = Pattern.compile(regex, Pattern.UNIX_LINES).matcher(line).find();
        assertEquals(
            expected,
            matches(query(pattern, MatchMode.REGEX, true, wholeWord), charset, line),
            charset + " '" + pattern + "' in '" + line + "' word " + wholeWord);
      }
    }
  }

  @Test
  void termFormFollowsCaseEncodingAndWholeWord() {
    TextEncoding utf8 = TextEncoding.of(StandardCharsets.UTF_8, 0);
    TextEncoding utf16 = TextEncoding.of(StandardCharsets.UTF_16LE, 0);
    TextEncoding sjis = TextEncoding.of(Charset.forName("Shift_JIS"), 0);
    SearchExpression.Term ascii = new SearchExpression.Term("Abc", MatchMode.LITERAL);
    SearchExpression.Term accented = new SearchExpression.Term("été", MatchMode.LITERAL);
    SearchQuery folded = query("Abc", MatchMode.LITERAL, false, false);
    SearchQuery word = query("Abc", MatchMode.LITERAL, true, true);
    assertTrue(StandardMatchers.bytewise(ascii, folded, utf8));
    assertTrue(
        StandardMatchers.bytewise(accented, query("x", MatchMode.LITERAL, true, false), utf8));
    assertFalse(StandardMatchers.bytewise(accented, folded, utf8));
    assertFalse(StandardMatchers.bytewise(ascii, folded, utf16));
    assertTrue(StandardMatchers.bytewise(ascii, query("x", MatchMode.LITERAL, true, false), utf16));
    assertFalse(StandardMatchers.bytewise(ascii, word, utf16));
    assertTrue(StandardMatchers.bytewise(ascii, word, utf8));
    assertFalse(StandardMatchers.bytewise(ascii, folded, sjis));
  }

  @Test
  void caseFoldingOnBytesFoldsAsciiOnly() {
    SearchQuery folded = query("error", MatchMode.LITERAL, false, false);
    assertTrue(matches(folded, StandardCharsets.UTF_8, "An ERROR here"));
    assertTrue(matches(folded, StandardCharsets.UTF_8, "an ErRoR"));
    assertFalse(
        matches(query("error", MatchMode.LITERAL, true, false), StandardCharsets.UTF_8, "ERROR"));
    // U+212A KELVIN SIGN folds to k under UNICODE_CASE but not on bytes.
    assertFalse(matches(query("k", MatchMode.LITERAL, false, false), StandardCharsets.UTF_8, "K"));
    assertTrue(matches(query("k", MatchMode.REGEX, false, false), StandardCharsets.UTF_8, "K"));
    // A non-ASCII literal folds through the regex path.
    assertTrue(
        matches(query("éTÉ", MatchMode.LITERAL, false, false), StandardCharsets.UTF_8, "Été"));
    assertTrue(
        matches(
            query("ERROR", MatchMode.LITERAL, false, false),
            StandardCharsets.UTF_16BE,
            "an error"));
  }

  @Test
  void utf16MatchesOnlyAtUnitBoundaries() {
    // UTF-16LE U+4100 U+4200 is 00 41 00 42: "A" (41 00) appears only at an odd offset.
    SearchQuery a = query("A", MatchMode.LITERAL, true, false);
    assertFalse(matches(a, StandardCharsets.UTF_16LE, "䄀䈀"));
    assertTrue(matches(a, StandardCharsets.UTF_16LE, "䄀A"));
    assertTrue(
        matches(query("䄀", MatchMode.LITERAL, true, false), StandardCharsets.UTF_16BE, "x䄀"));
  }

  @Test
  void literalSeesOriginalBytesAndRegexSeesReplacements() {
    TextEncoding utf8 = TextEncoding.of(StandardCharsets.UTF_8, 0);
    CancellationToken token = new CancellationToken();
    ByteBuffer buffer = ByteBuffer.wrap(new byte[] {'a', 'b', (byte) 0xff, 'c', 'd'});
    LineText line = new LineText(utf8, token);
    line.set(buffer, 0, 5);
    assertTrue(create("b", MatchMode.LITERAL, utf8, token).matches(line));
    assertFalse(create("b�c", MatchMode.LITERAL, utf8, token).matches(line));
    assertTrue(create("b�c", MatchMode.REGEX, utf8, token).matches(line));
    assertEquals("ab�cd", line.chars().toString());
  }

  private static LineMatcher create(
      String pattern, MatchMode mode, TextEncoding encoding, CancellationToken token) {
    return StandardMatchers.INSTANCE.create(SearchQuery.of(pattern, mode), encoding, token);
  }

  @Test
  void wholeWordAtLineEdgesAndBesideNonAscii() {
    for (MatchMode mode : MatchMode.values()) {
      SearchQuery word = query("foo", mode, true, true);
      for (Charset charset : new Charset[] {StandardCharsets.UTF_8, StandardCharsets.UTF_16LE}) {
        String context = mode + " " + charset;
        assertTrue(matches(word, charset, "foo"), context);
        assertTrue(matches(word, charset, "foo bar"), context);
        assertTrue(matches(word, charset, "bar foo"), context);
        assertTrue(matches(word, charset, "(foo)"), context);
        assertTrue(matches(word, charset, "éfooé"), context);
        assertTrue(matches(word, charset, "foobar foo"), context);
        assertFalse(matches(word, charset, "foobar"), context);
        assertFalse(matches(word, charset, "_foo"), context);
        assertFalse(matches(word, charset, "foo1"), context);
      }
    }
  }

  @Test
  void regexAnchorsAtTheLineEdges() {
    assertTrue(
        matches(query("^abc$", MatchMode.REGEX, true, false), StandardCharsets.UTF_8, "abc"));
    assertFalse(
        matches(query("^abc$", MatchMode.REGEX, true, false), StandardCharsets.UTF_8, "abc\rx"));
    assertFalse(
        matches(query("abc$", MatchMode.REGEX, true, false), StandardCharsets.UTF_8, "abc "));
    assertTrue(
        matches(query("^a.c$", MatchMode.REGEX, true, false), StandardCharsets.UTF_8, "a\rc"));
  }

  @Test
  void inverseAndCombinedTerms() throws ExpressionException {
    SearchExpression expression =
        ExpressionParser.parse("\"warn\" and not \"mock\" or \"fatal\"", MatchMode.LITERAL, true);
    SearchQuery query = new SearchQuery(expression, true, false, false, null);
    SearchQuery inverse = new SearchQuery(expression, true, false, true, null);
    List<String> lines = new ArrayList<String>();
    lines.add("warn real");
    lines.add("warn mock");
    lines.add("fatal mock");
    lines.add("info");
    boolean[] expected = {true, false, true, false};
    for (int i = 0; i < lines.size(); i++) {
      assertEquals(expected[i], matches(query, StandardCharsets.UTF_8, lines.get(i)), lines.get(i));
      assertEquals(!expected[i], matches(inverse, StandardCharsets.UTF_8, lines.get(i)));
    }
    SearchQuery xor =
        new SearchQuery(
            ExpressionParser.parse("\"a\" xor \"b\"", MatchMode.LITERAL, true),
            true,
            false,
            false,
            null);
    assertTrue(matches(xor, StandardCharsets.UTF_8, "a"));
    assertFalse(matches(xor, StandardCharsets.UTF_8, "ab"));
  }

  @Test
  void emptyLiteralMatchesEveryLine() {
    assertTrue(matches(SearchQuery.of("", MatchMode.LITERAL), StandardCharsets.UTF_8, ""));
    assertTrue(matches(SearchQuery.of("", MatchMode.LITERAL), StandardCharsets.UTF_16LE, "x"));
  }

  @Test
  void invalidRegexThrowsWhenCreated() {
    assertThrows(
        PatternSyntaxException.class,
        new Executable() {
          @Override
          public void execute() {
            create(
                "(unclosed",
                MatchMode.REGEX,
                TextEncoding.of(StandardCharsets.UTF_8, 0),
                new CancellationToken());
          }
        });
  }

  @Test
  void canceledTokenStopsCatastrophicBacktracking() {
    TextEncoding utf8 = TextEncoding.of(StandardCharsets.UTF_8, 0);
    final CancellationToken token = new CancellationToken();
    byte[] text = "a".repeat(60).getBytes(StandardCharsets.US_ASCII);
    final LineText line = new LineText(utf8, token);
    line.set(ByteBuffer.wrap(text), 0, text.length);
    final LineMatcher matcher = create("(.*a){25}x", MatchMode.REGEX, utf8, token);
    token.cancel();
    long started = System.nanoTime();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            matcher.matches(line);
          }
        });
    assertTrue(System.nanoTime() - started < 5_000_000_000L);
  }
}
