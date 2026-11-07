package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The matchers QLogg searches with. Each term compiles to one of two forms:
 *
 * <table>
 *   <caption>Term forms</caption>
 *   <tr><th>Term</th><th>Form</th></tr>
 *   <tr><td>Literal, case-sensitive, pattern encodable</td><td>Byte search for the encoded
 *       pattern</td></tr>
 *   <tr><td>Literal, case-insensitive, ASCII pattern, ASCII-compatible encoding</td><td>Byte
 *       search folding ASCII letters</td></tr>
 *   <tr><td>Any other literal</td><td>Quoted regex on decoded text</td></tr>
 *   <tr><td>Case-sensitive regex without metacharacters</td><td>As a case-sensitive
 *       literal</td></tr>
 *   <tr><td>Any other regex</td><td>{@link Pattern} on decoded text</td></tr>
 * </table>
 *
 * <p>Byte search runs only where a byte match is a character match: UTF-8, US-ASCII, single-byte
 * charsets, and UTF-16 or UTF-32 at unit-aligned offsets. Whole word on bytes needs an
 * ASCII-compatible encoding, else the term runs as a regex. Regexes compile with {@code
 * UNIX_LINES}, so {@code ^}, {@code $} and {@code .} treat the line as one line, plus {@code
 * CASE_INSENSITIVE | UNICODE_CASE} when case folds; whole word wraps the pattern with {@link
 * #wholeWord}. A case-sensitive regex whose {@link #requiredPrefix} encodes to at least {@link
 * #MIN_PREFILTER_BYTES} bytes, where byte search runs and ANSI sequences are not hidden, decodes
 * and matches only lines whose bytes contain that prefix. Thread-safe; each matcher it creates is
 * not.
 */
public final class StandardMatchers implements MatcherFactory {
  /** The shared instance. */
  public static final StandardMatchers INSTANCE = new StandardMatchers();

  /** Characters with a meaning in a {@link Pattern} outside a character class. */
  private static final String REGEX_META = "\\^$.|?*+()[]{}";

  /** Shortest encoded literal prefix a regex is pre-filtered by. */
  static final int MIN_PREFILTER_BYTES = 3;

  /** Text tried after a pattern so the wrapper's closing group stays outside it. */
  private static final String[] CLOSERS = {"", "\\E", "\n", "\\E\n"};

  private StandardMatchers() {}

  /**
   * Wraps a regex in {@code (?<!\w)(?:...)(?!\w)}, so a match needs a non-word char or the line
   * edge on each side. The pattern is closed first with {@code \E} when it ends inside {@code \Q},
   * with a line feed when it ends inside a {@code (?x)} comment, or with both for a {@code \Q}
   * inside such a comment, which {@link Pattern} still reads as a quote. Each would otherwise
   * swallow the closing parenthesis.
   *
   * @param pattern a regex that compiles alone
   * @return the wrapped regex
   * @throws PatternSyntaxException when the pattern does not compile
   */
  public static String wholeWord(String pattern) {
    int groups = Pattern.compile(pattern, Pattern.UNIX_LINES).matcher("").groupCount();
    for (String closer : CLOSERS) {
      String closed = pattern + closer;
      try {
        // A group appended after the closer counts only when the closer ends quoting or a comment.
        if (Pattern.compile(closed + "()", Pattern.UNIX_LINES).matcher("").groupCount()
            == groups + 1) {
          return "(?<!\\w)(?:" + closed + ")(?!\\w)";
        }
      } catch (PatternSyntaxException e) {
        // A stray \E outside \Q; the next closer applies.
      }
    }
    throw new PatternSyntaxException("Whole word cannot close the pattern", pattern, -1);
  }

  @Override
  public LineMatcher create(SearchQuery query, TextEncoding encoding, CancellationToken token) {
    final Node root = compile(query.expression(), query, encoding);
    final boolean inverse = query.inverse();
    return new LineMatcher() {
      @Override
      public boolean matches(LineText line) {
        return root.test(line) != inverse;
      }
    };
  }

  /**
   * Reports whether a term runs as a byte search.
   *
   * @param term the term
   * @param query its query
   * @param encoding the document's encoding
   * @return false when it runs as a regex
   */
  static boolean bytewise(SearchExpression.Term term, SearchQuery query, TextEncoding encoding) {
    return literal(term, query) && byteLiteral(term.pattern(), query, encoding) != null;
  }

  /**
   * True for a literal, and for a case-sensitive regex without metacharacters, whose matches are
   * the literal's. A case-insensitive regex stays a regex: {@code UNICODE_CASE} matches {@code k}
   * with the Kelvin sign and {@code s} with the long s, which ASCII folding on bytes does not. So
   * does a regex with U+FFFD, which the decoded text has where the bytes are malformed.
   */
  private static boolean literal(SearchExpression.Term term, SearchQuery query) {
    if (term.mode() == MatchMode.LITERAL) {
      return true;
    }
    String pattern = term.pattern();
    if (!query.caseSensitive() || pattern.isEmpty()) {
      return false;
    }
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (REGEX_META.indexOf(c) >= 0 || c == '\ufffd') {
        return false;
      }
    }
    return true;
  }

  /** Compiled expression node. */
  private interface Node {
    boolean test(LineText line);
  }

  private static Node compile(SearchExpression expression, SearchQuery query, TextEncoding enc) {
    if (expression instanceof SearchExpression.Term term) {
      return term(term, query, enc);
    }
    if (expression instanceof SearchExpression.Not not) {
      final Node operand = compile(not.operand(), query, enc);
      return new Node() {
        @Override
        public boolean test(LineText line) {
          return !operand.test(line);
        }
      };
    }
    if (expression instanceof SearchExpression.And and) {
      final Node left = compile(and.left(), query, enc);
      final Node right = compile(and.right(), query, enc);
      return new Node() {
        @Override
        public boolean test(LineText line) {
          return left.test(line) && right.test(line);
        }
      };
    }
    if (expression instanceof SearchExpression.Or or) {
      final Node left = compile(or.left(), query, enc);
      final Node right = compile(or.right(), query, enc);
      return new Node() {
        @Override
        public boolean test(LineText line) {
          return left.test(line) || right.test(line);
        }
      };
    }
    SearchExpression.Xor xor = (SearchExpression.Xor) expression;
    final Node left = compile(xor.left(), query, enc);
    final Node right = compile(xor.right(), query, enc);
    return new Node() {
      @Override
      public boolean test(LineText line) {
        return left.test(line) != right.test(line);
      }
    };
  }

  private static Node term(SearchExpression.Term term, SearchQuery query, TextEncoding enc) {
    if (literal(term, query)) {
      ByteLiteral literal = byteLiteral(term.pattern(), query, enc);
      if (literal != null) {
        return literal;
      }
    }
    if (term.mode() == MatchMode.LITERAL) {
      return regex(Pattern.quote(term.pattern()), query, null);
    }
    return regex(term.pattern(), query, prefilter(term.pattern(), query, enc));
  }

  /**
   * Returns a byte search for the literal every match of a case-sensitive regex starts with, or
   * null when that literal is shorter than {@link #MIN_PREFILTER_BYTES} bytes or a byte match would
   * not be a character match.
   */
  private static ByteLiteral prefilter(String pattern, SearchQuery query, TextEncoding enc) {
    if (!query.caseSensitive() || query.hideAnsi() || !byteAligned(enc)) {
      return null;
    }
    String prefix = requiredPrefix(pattern);
    if (prefix.isEmpty() || !encodable(prefix, enc.charset())) {
      return null;
    }
    byte[] bytes = prefix.getBytes(enc.charset());
    if (bytes.length < MIN_PREFILTER_BYTES) {
      return null;
    }
    return new ByteLiteral(bytes, false, enc.unitWidth(), false);
  }

  /**
   * Returns the chars before a regex's first metacharacter, less the last one when a {@code ?},
   * {@code *} or <code>{</code> quantifier follows it; empty when the pattern has a {@code |}.
   *
   * @param pattern the regex
   * @return a literal every match starts with, possibly empty
   */
  static String requiredPrefix(String pattern) {
    if (pattern.indexOf('|') >= 0) {
      return "";
    }
    int end = 0;
    while (end < pattern.length()
        && REGEX_META.indexOf(pattern.charAt(end)) < 0
        && pattern.charAt(end) != '\ufffd') {
      end++;
    }
    if (end < pattern.length() && "?*{".indexOf(pattern.charAt(end)) >= 0) {
      end = Math.max(0, end - 1);
    }
    return pattern.substring(0, end);
  }

  private static ByteLiteral byteLiteral(String pattern, SearchQuery query, TextEncoding enc) {
    if (query.hideAnsi() || !byteAligned(enc) || query.wholeWord() && !enc.asciiCompatible()) {
      return null;
    }
    if (!query.caseSensitive()) {
      if (!enc.asciiCompatible() || !ascii(pattern)) {
        return null;
      }
      byte[] bytes = pattern.getBytes(StandardCharsets.US_ASCII);
      for (int i = 0; i < bytes.length; i++) {
        bytes[i] = ByteLiteral.lower(bytes[i]);
      }
      return new ByteLiteral(bytes, true, 1, query.wholeWord());
    }
    if (!encodable(pattern, enc.charset())) {
      return null;
    }
    return new ByteLiteral(
        pattern.getBytes(enc.charset()), false, enc.unitWidth(), query.wholeWord());
  }

  /** True where a byte match at a unit-aligned offset is a character match. */
  private static boolean byteAligned(TextEncoding enc) {
    Charset charset = enc.charset();
    if (!charset.canEncode()) {
      return false;
    }
    String name = charset.name();
    if (enc.unitWidth() > 1) {
      return name.startsWith("UTF-16") || name.startsWith("UTF-32");
    }
    if (charset.equals(StandardCharsets.UTF_8) || charset.equals(StandardCharsets.US_ASCII)) {
      return true;
    }
    return charset.newEncoder().maxBytesPerChar() == 1.0f;
  }

  private static boolean ascii(String pattern) {
    for (int i = 0; i < pattern.length(); i++) {
      if (pattern.charAt(i) >= 0x80) {
        return false;
      }
    }
    return true;
  }

  /** True when every char maps; a lone surrogate does not. */
  private static boolean encodable(String pattern, Charset charset) {
    CharsetEncoder encoder =
        charset
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      encoder.encode(CharBuffer.wrap(pattern));
      return true;
    } catch (CharacterCodingException e) {
      return false;
    }
  }

  private static Node regex(String pattern, SearchQuery query, final ByteLiteral required) {
    int flags = Pattern.UNIX_LINES;
    if (!query.caseSensitive()) {
      flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    }
    String source = query.wholeWord() ? wholeWord(pattern) : pattern;
    final Matcher matcher = Pattern.compile(source, flags).matcher("");
    return new Node() {
      @Override
      public boolean test(LineText line) {
        return (required == null || required.test(line)) && matcher.reset(line.chars()).find();
      }
    };
  }

  /** Byte search for an encoded pattern at unit-aligned offsets. */
  private static final class ByteLiteral implements Node {
    private final byte[] pattern;
    private final boolean fold;
    private final int unit;
    private final boolean wholeWord;
    private final int trail;
    private final boolean foldLead;
    private final boolean foldTrail;

    ByteLiteral(byte[] pattern, boolean fold, int unit, boolean wholeWord) {
      this.pattern = pattern;
      this.fold = fold;
      this.unit = unit;
      this.wholeWord = wholeWord;
      int last = pattern.length - 1;
      while (last > 0 && pattern[last] == 0) {
        last--;
      }
      this.trail = Math.max(0, last);
      this.foldLead = pattern.length > 0 && fold && letter(pattern[0]);
      this.foldTrail = pattern.length > 0 && fold && letter(pattern[trail]);
    }

    private static boolean letter(byte b) {
      return b >= 'a' && b <= 'z';
    }

    static byte lower(byte b) {
      return b >= 'A' && b <= 'Z' ? (byte) (b + ('a' - 'A')) : b;
    }

    private static boolean wordByte(byte b) {
      return b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9' || b == '_';
    }

    /**
     * Finds candidates by the first pattern byte and the last nonzero one together, then compares
     * the rest at aligned offsets.
     */
    @Override
    public boolean test(LineText line) {
      ByteBuffer bytes = line.buffer();
      int from = line.start();
      int to = line.end();
      if (pattern.length == 0) {
        return testEmpty(bytes, from, to);
      }
      int stop = to - pattern.length + 1;
      int at = from;
      while (at < stop) {
        int found =
            ByteScan.indexOfPair(
                bytes, at, stop, pattern[0], foldLead, pattern[trail], foldTrail, trail);
        if (found < 0) {
          return false;
        }
        if ((found - from) % unit == 0
            && equalAt(bytes, found)
            && (!wholeWord || bounded(bytes, found, from, to))) {
          return true;
        }
        at = found + 1;
      }
      return false;
    }

    /** An empty pattern matches at every aligned offset that passes the whole-word check. */
    private boolean testEmpty(ByteBuffer bytes, int from, int to) {
      for (int at = from; at <= to; at += unit) {
        if (!wholeWord || bounded(bytes, at, from, to)) {
          return true;
        }
      }
      return false;
    }

    private boolean equalAt(ByteBuffer bytes, int at) {
      for (int k = 0; k < pattern.length; k++) {
        byte b = bytes.get(at + k);
        if ((fold ? lower(b) : b) != pattern[k]) {
          return false;
        }
      }
      return true;
    }

    private boolean bounded(ByteBuffer bytes, int at, int from, int to) {
      int after = at + pattern.length;
      return (at == from || !wordByte(bytes.get(at - 1)))
          && (after == to || !wordByte(bytes.get(after)));
    }
  }
}
