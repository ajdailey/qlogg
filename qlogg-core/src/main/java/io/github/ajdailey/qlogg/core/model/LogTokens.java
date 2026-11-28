package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.text.BoundedChars;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The syntax-color rules for log lines, compiled once, and the matcher that applies them. The rules
 * are the {@code main} context of QCaret's {@code log} grammar, in grammar order; a change to
 * either rule set is sent to the other project.
 *
 * <p>Matching scans a line left to right. At each position the rules are tried in order with {@code
 * lookingAt}, lookarounds seeing the whole line; the first rule matching at the earliest position
 * wins, its text becomes one token, and the scan resumes at its end. A {@code "} opens a {@link
 * TokenKind#STRING} that ends at the next {@code "} or the line end; inside it a backslash and the
 * char after it are an {@link TokenKind#ESCAPE}. Tables derived from each pattern try a rule only
 * where it can start: at a possible first char and, for a rule whose leading lookbehind ends in a
 * literal char, after that char.
 *
 * <p>Bounds are those of {@link Highlighter.LineMatcher}: each rule reads at most {@code 8 * length
 * + 65,536} chars of a line, and a matcher stops coloring after {@link Highlighter#BLOCK_STEPS}
 * chars. A rule that reaches its bound or overflows the regex stack is abandoned for that line, and
 * the tokens found so far stay. The scan ends at the column window's right edge.
 */
public final class LogTokens {
  /** ASCII chars, plus one entry for every other char. */
  private static final int CHARS = 129;

  private static final Rule[] RULES = {
    rule(
        TokenKind.COMMENT,
        "^\\s+(?:at\\s+\\S.*|\\.\\.\\.\\s+\\d+\\s+(?:more|common frames omitted)"
            + "|File \".*\", line \\d+.*)$"),
    rule(TokenKind.KEYWORD, "^(?:Caused by|Suppressed|Traceback \\(most recent call last\\)):?"),
    rule(
        TokenKind.NUMBER,
        "(?<![\\w.:-])\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}(?::\\d{2}(?:[.,]\\d{1,9})?)?"
            + "(?:Z|[+-]\\d{2}:?\\d{2})?)?(?![\\w:])"
            + "|(?<![\\w.:/-])\\d{4}/\\d{2}/\\d{2}(?: \\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?)?"
            + "(?![\\w:])"
            + "|\\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) [ \\d]\\d"
            + " \\d{2}:\\d{2}:\\d{2}\\b"
            + "|\\[\\d{2}/(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)/\\d{4}"
            + ":\\d{2}:\\d{2}:\\d{2} [+-]\\d{4}\\]"
            + "|(?<![\\w.:-])\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?![\\w:])"),
    rule(
        TokenKind.INVALID,
        "(?<![\\w.-])(?:FATAL|ERROR|SEVERE|CRITICAL|CRIT|EMERG|EMERGENCY|ALERT|ERR|FTL|EROR)"
            + "(?![\\w.-])"),
    rule(
        TokenKind.INVALID,
        "(?<![\\w.-])(?<=\\b(?:level|lvl|severity)=)"
            + "(?i:fatal|error|err|critical|crit|emerg|alert|panic)\\b"),
    rule(
        TokenKind.INVALID,
        "(?<=\"(?:level|severity|@l)\":\\s?)"
            + "\"(?i:fatal|error|err|critical|crit|emerg|alert|panic)\""),
    rule(TokenKind.INVALID, levelLetter("[EF]", true)),
    rule(TokenKind.ESCAPE, "(?<![\\w.-])(?:WARN|WARNING|WRN)(?![\\w.-])"),
    rule(TokenKind.ESCAPE, "(?<![\\w.-])(?<=\\b(?:level|lvl|severity)=)(?i:warn|warning)\\b"),
    rule(TokenKind.ESCAPE, "(?<=\"(?:level|severity|@l)\":\\s?)\"(?i:warn|warning)\""),
    rule(TokenKind.ESCAPE, levelLetter("W", true)),
    rule(TokenKind.TYPE, "(?<![\\w.-])(?:INFO|NOTICE|CONFIG|INF)(?![\\w.-])"),
    rule(TokenKind.TYPE, "(?<![\\w.-])(?<=\\b(?:level|lvl|severity)=)(?i:info|notice)\\b"),
    rule(TokenKind.TYPE, "(?<=\"(?:level|severity|@l)\":\\s?)\"(?i:info|information|notice)\""),
    rule(TokenKind.TYPE, levelLetter("I", true)),
    rule(TokenKind.KEYWORD2, "(?<![\\w.-])(?:DEBUG|FINE|DBG)(?![\\w.-])"),
    rule(TokenKind.KEYWORD2, "(?<![\\w.-])(?<=\\b(?:level|lvl|severity)=)(?i:debug)\\b"),
    rule(TokenKind.KEYWORD2, "(?<=\"(?:level|severity|@l)\":\\s?)\"(?i:debug)\""),
    rule(TokenKind.KEYWORD2, levelLetter("D", false)),
    rule(TokenKind.COMMENT, "(?<![\\w.-])(?:TRACE|FINER|FINEST|TRC|VERBOSE)(?![\\w.-])"),
    rule(TokenKind.COMMENT, "(?<![\\w.-])(?<=\\b(?:level|lvl|severity)=)(?i:trace|verbose)\\b"),
    rule(TokenKind.COMMENT, "(?<=\"(?:level|severity|@l)\":\\s?)\"(?i:trace|verbose)\""),
    rule(TokenKind.COMMENT, levelLetter("[TV]", false)),
    rule(
        TokenKind.DELIMITER,
        "\\[(?!\\s*(?i:fatal|error|severe|critical|crit|emerg|alert|err|ftl|warn|warning|wrn"
            + "|info|notice|config|inf|debug|fine|dbg|trace|finer|finest|trc|verbose"
            + "|[EFWIDTV])\\s*\\])[^\\[\\]]{1,80}\\]"),
    rule(TokenKind.LINK, "(?<![\\w.-])(?:https?|ftps?|wss?|file)://[^\\s\"'<>]+"),
    rule(
        TokenKind.KEYWORD,
        "(?<![\\w$.])(?:[A-Za-z_$][\\w$]*\\.)*[A-Z][\\w$]*(?:Exception|Error|Throwable)(?![\\w$])"),
    rule(TokenKind.FUNCTION, "(?<![\\w$.])(?:[a-z_][\\w$]*\\.)+[A-Z][\\w$]*(?![\\w$])"),
    rule(TokenKind.ATTRIBUTE, "(?<![\\w.-])[A-Za-z_][\\w.-]*(?==)"),
    new Rule(TokenKind.STRING, Pattern.compile("\""), true)
  };

  /** Rules that may match at position 0, by first char. */
  private static final int[][] AT_START = new int[CHARS][];

  /** Rules that may match after a char that is no ASCII word char, by first char. */
  private static final int[][] AFTER_OTHER = new int[CHARS][];

  /** Rules that may match after an ASCII letter, digit or underscore, by first char. */
  private static final int[][] AFTER_WORD = new int[CHARS][];

  /** Every rule, for a matcher built without first-char tables. */
  private static final int[] EVERY = new int[RULES.length];

  /** Per rule, the char every match must follow, or -1 for any. */
  private static final int[] PREVIOUS = new int[RULES.length];

  static {
    boolean[][] start = new boolean[RULES.length][];
    boolean[][] other = new boolean[RULES.length][];
    boolean[][] word = new boolean[RULES.length][];
    for (int r = 0; r < RULES.length; r++) {
      start[r] = new boolean[CHARS];
      other[r] = new boolean[CHARS];
      word[r] = new boolean[CHARS];
      List<String> alternatives = alternatives(RULES[r].pattern().pattern());
      PREVIOUS[r] = previous(alternatives.get(0));
      for (String alternative : alternatives) {
        firstChars(alternative, start[r], other[r], word[r]);
        if (previous(alternative) != PREVIOUS[r]) {
          PREVIOUS[r] = -1;
        }
      }
      EVERY[r] = r;
    }
    for (int c = 0; c < CHARS; c++) {
      AT_START[c] = list(start, c);
      AFTER_OTHER[c] = list(other, c);
      AFTER_WORD[c] = list(word, c);
    }
  }

  private LogTokens() {}

  /**
   * A compiled rule.
   *
   * @param kind the token it makes
   * @param pattern the regex
   * @param string true for the quote that opens a {@link TokenKind#STRING}
   */
  private record Rule(TokenKind kind, Pattern pattern, boolean string) {}

  private static Rule rule(TokenKind kind, String regex) {
    return new Rule(kind, Pattern.compile(regex), false);
  }

  /**
   * Returns a one-letter level rule: the letter inside {@code [ ]} or {@code < >}, at the start or
   * after a space before an Android-style {@code /tag:}, and, when {@code glog}, at the line start
   * before a glog date.
   */
  private static String levelLetter(String letter, boolean glog) {
    return "(?<![\\w.-])(?:(?<=[\\[<])"
        + letter
        + "(?=[\\]>])|(?<=^|\\s)"
        + letter
        + "(?=/[\\w.$-]+(?:\\(\\s*\\d+\\))?:)"
        + (glog ? "|^" + letter + "(?=\\d{4} \\d{2}:)" : "")
        + ")";
  }

  /**
   * Creates a matcher for one thread.
   *
   * @return the matcher
   */
  public static LineMatcher matcher() {
    return new LineMatcher(true, Highlighter.BLOCK_STEPS);
  }

  /**
   * Creates a matcher.
   *
   * @param tabled false to try every rule at every position, ignoring the first-char tables
   * @param budget chars read before the matcher stops coloring further lines
   * @return the matcher
   */
  static LineMatcher matcher(boolean tabled, long budget) {
    return new LineMatcher(tabled, budget);
  }

  /** Returns the rules marked in a table column, in rule order. */
  private static int[] list(boolean[][] table, int c) {
    int n = 0;
    for (boolean[] row : table) {
      if (row[c]) {
        n++;
      }
    }
    int[] out = new int[n];
    n = 0;
    for (int r = 0; r < table.length; r++) {
      if (table[r][c]) {
        out[n++] = r;
      }
    }
    return out;
  }

  /**
   * Marks the first chars an alternative may match: at position 0, after a non-word char, and after
   * an ASCII word char. An alternative starting with {@code ^} matches only at position 0; one
   * starting with a negative lookbehind that rejects every ASCII word char never follows one; one
   * starting with {@code \b} follows a word char only with a non-word char. Every non-ASCII char is
   * a possible first char.
   */
  private static void firstChars(
      String alternative, boolean[] start, boolean[] other, boolean[] word) {
    boolean anchored = false;
    boolean afterWord = true;
    boolean boundary = false;
    int at = 0;
    while (true) {
      if (alternative.startsWith("^", at)) {
        anchored = true;
        at++;
      } else if (alternative.startsWith("\\b", at)) {
        boundary = true;
        at += 2;
      } else if (lookaround(alternative, at)) {
        int close = closing(alternative, at);
        if (alternative.startsWith("(?<!", at)
            && rejectsWordChars(alternative.substring(at + 4, close))) {
          afterWord = false;
        }
        at = close + 1;
      } else {
        break;
      }
    }
    Pattern probe = Pattern.compile(widen(alternative));
    for (int c = 0; c < CHARS; c++) {
      boolean first = c == CHARS - 1 || possibleFirst(probe, (char) c);
      if (!first) {
        continue;
      }
      start[c] = true;
      if (!anchored) {
        other[c] = true;
        if (afterWord && !(boundary && c < CHARS - 1 && word((char) c))) {
          word[c] = true;
        }
      }
    }
  }

  /**
   * Returns the char an alternative's match must follow: the last char of a leading positive
   * lookbehind with one alternative that ends in a plain literal char, as {@code (?<=level=)} ends
   * in {@code =}; -1 otherwise.
   */
  static int previous(String alternative) {
    int at = 0;
    while (lookaround(alternative, at) || alternative.startsWith("(?<!", at)) {
      int close = closing(alternative, at);
      if (alternative.startsWith("(?<=", at)) {
        String body = alternative.substring(at + 4, close);
        int last = body.length() - 1;
        if (last >= 0
            && alternatives(body).size() == 1
            && "\\^$.|?*+()[]{}".indexOf(body.charAt(last)) < 0
            && !escaped(body, last)) {
          return body.charAt(last);
        }
      }
      at = close + 1;
    }
    return -1;
  }

  /** Reports whether the char at {@code at} follows an odd run of backslashes. */
  private static boolean escaped(String regex, int at) {
    int slashes = 0;
    for (int i = at - 1; i >= 0 && regex.charAt(i) == '\\'; i--) {
      slashes++;
    }
    return (slashes & 1) != 0;
  }

  /** Reports whether a match of the probe may start with a char. */
  private static boolean possibleFirst(Pattern probe, char c) {
    Matcher m = probe.matcher(String.valueOf(c));
    return m.lookingAt() || m.hitEnd();
  }

  private static boolean rejectsWordChars(String body) {
    Pattern p = Pattern.compile(body);
    for (char c = 0; c < CHARS - 1; c++) {
      if (word(c) && !p.matcher(String.valueOf(c)).matches()) {
        return false;
      }
    }
    return true;
  }

  private static boolean lookaround(String regex, int at) {
    return regex.startsWith("(?=", at)
        || regex.startsWith("(?!", at)
        || regex.startsWith("(?<=", at)
        || regex.startsWith("(?<!", at);
  }

  /**
   * Returns a regex without its lookarounds, {@code \b}, {@code \B}, {@code ^} and {@code $}: it
   * matches every string the regex matches, and more.
   */
  static String widen(String regex) {
    StringBuilder out = new StringBuilder();
    boolean inClass = false;
    int i = 0;
    while (i < regex.length()) {
      char c = regex.charAt(i);
      if (c == '\\') {
        char next = regex.charAt(i + 1);
        if (inClass || (next != 'b' && next != 'B')) {
          out.append(c).append(next);
        }
        i += 2;
      } else if (inClass) {
        inClass = c != ']';
        out.append(c);
        i++;
      } else if (c == '[') {
        inClass = true;
        out.append(c);
        i++;
        if (i < regex.length() && regex.charAt(i) == '^') {
          out.append('^');
          i++;
        }
        if (i < regex.length() && regex.charAt(i) == ']') {
          out.append(']');
          i++;
        }
      } else if (lookaround(regex, i)) {
        i = closing(regex, i) + 1;
      } else if (c == '^' || c == '$') {
        i++;
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  /** Splits a regex at its top-level {@code |}. */
  static List<String> alternatives(String regex) {
    List<String> out = new ArrayList<String>();
    int depth = 0;
    boolean inClass = false;
    int from = 0;
    for (int i = 0; i < regex.length(); i++) {
      char c = regex.charAt(i);
      if (c == '\\') {
        i++;
      } else if (inClass) {
        inClass = c != ']';
      } else if (c == '[') {
        inClass = true;
        if (i + 1 < regex.length() && regex.charAt(i + 1) == '^') {
          i++;
        }
        if (i + 1 < regex.length() && regex.charAt(i + 1) == ']') {
          i++;
        }
      } else if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == '|' && depth == 0) {
        out.add(regex.substring(from, i));
        from = i + 1;
      }
    }
    out.add(regex.substring(from));
    return out;
  }

  /** Returns the index of the parenthesis closing the group opened at {@code open}. */
  private static int closing(String regex, int open) {
    int depth = 0;
    boolean inClass = false;
    for (int i = open; i < regex.length(); i++) {
      char c = regex.charAt(i);
      if (c == '\\') {
        i++;
      } else if (inClass) {
        inClass = c != ']';
      } else if (c == '[') {
        inClass = true;
        if (i + 1 < regex.length() && regex.charAt(i + 1) == '^') {
          i++;
        }
        if (i + 1 < regex.length() && regex.charAt(i + 1) == ']') {
          i++;
        }
      } else if (c == '(') {
        depth++;
      } else if (c == ')' && --depth == 0) {
        return i;
      }
    }
    throw new IllegalArgumentException("unclosed group in " + regex);
  }

  private static boolean word(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
  }

  private static boolean lineTerminator(char c) {
    return c == '\n' || c == '\r' || c == '\u0085' || c == ' ' || c == ' ';
  }

  /** Tokenizes lines on one thread. */
  public static final class LineMatcher {
    private final boolean tabled;
    private final BoundedChars[] chars = new BoundedChars[RULES.length];
    private final Matcher[] matchers = new Matcher[RULES.length];
    private final boolean[] abandoned = new boolean[RULES.length];

    /** Per rule, the line its matcher was last reset for. */
    private final long[] resetFor = new long[RULES.length];

    private int[] columns = new int[256];
    private int[] data = new int[3 * 16];
    private int count;
    private int mapped;
    private long budget;
    private long limit;
    private long line;
    private CharSequence text;
    private int length;
    private long from;
    private long to;

    LineMatcher(boolean tabled, long budget) {
      this.tabled = tabled;
      this.budget = budget;
    }

    /**
     * Tokenizes a line, clipped to a column window.
     *
     * @param line the decoded line, tabs kept, terminator excluded
     * @param firstColumn first display column kept
     * @param windowColumns width of the window
     * @return the tokens; {@link TokenSpans#NONE} once the matcher's budget is spent
     */
    public TokenSpans spans(CharSequence line, long firstColumn, int windowColumns) {
      if (budget <= 0) {
        return TokenSpans.NONE;
      }
      text = line;
      length = line.length();
      from = firstColumn;
      to = firstColumn + windowColumns;
      count = 0;
      this.line++;
      int stop = mapColumns();
      limit = Math.min(BoundedChars.lineLimit(line), budget);
      try {
        scan(stop);
      } finally {
        for (int r = 0; r < RULES.length; r++) {
          if (resetFor[r] == this.line) {
            budget -= chars[r].used();
          }
        }
        text = null;
      }
      if (count == 0) {
        return TokenSpans.NONE;
      }
      int[] copy = new int[3 * count];
      System.arraycopy(data, 0, copy, 0, copy.length);
      return new TokenSpans(copy, count);
    }

    private void scan(int stop) {
      int pos = 0;
      while (pos < stop && count < Highlighter.MAX_SPANS) {
        int rule = -1;
        int at = pos;
        for (; at < stop && rule < 0; at++) {
          rule = firstMatch(at);
        }
        if (rule < 0) {
          return;
        }
        at--;
        int end = matchers[rule].end();
        if (end == at) {
          pos = at + 1;
        } else if (RULES[rule].string()) {
          pos = string(at, stop);
        } else {
          add(at, end, RULES[rule].kind());
          pos = end;
        }
      }
    }

    /** Returns the first rule in order that matches at a position, or -1. */
    private int firstMatch(int at) {
      int[] candidates = EVERY;
      if (tabled) {
        char c = text.charAt(at);
        int column = c < CHARS - 1 ? c : CHARS - 1;
        if (at == 0) {
          candidates = AT_START[column];
        } else if (word(text.charAt(at - 1))) {
          candidates = AFTER_WORD[column];
        } else {
          candidates = AFTER_OTHER[column];
        }
      }
      for (int rule : candidates) {
        int previous = PREVIOUS[rule];
        if (tabled && previous >= 0 && (at == 0 || text.charAt(at - 1) != previous)) {
          continue;
        }
        Matcher m = matcher(rule);
        if (abandoned[rule]) {
          continue;
        }
        try {
          m.region(at, length);
          if (m.lookingAt()) {
            return rule;
          }
        } catch (BoundedChars.LimitReached | StackOverflowError e) {
          // This rule is abandoned for this line; a deeply recursive pattern on a long line
          // overflows the regex engine's stack instead of reaching its read bound.
          abandoned[rule] = true;
        }
      }
      return -1;
    }

    /** Returns a rule's matcher, created on first use and reset at a rule's first use on a line. */
    private Matcher matcher(int rule) {
      Matcher m = matchers[rule];
      if (resetFor[rule] == line) {
        return m;
      }
      if (m == null) {
        chars[rule] = new BoundedChars();
        m = RULES[rule].pattern().matcher(chars[rule]);
        m.useTransparentBounds(true);
        m.useAnchoringBounds(false);
        matchers[rule] = m;
      }
      m.reset(chars[rule].reset(text, limit));
      abandoned[rule] = false;
      resetFor[rule] = line;
      return m;
    }

    /** Adds a string opened by the quote at {@code at}; returns the index after it. */
    private int string(int at, int stop) {
      add(at, at + 1, TokenKind.STRING);
      int j = at + 1;
      while (j < length && j < stop) {
        char c = text.charAt(j);
        if (c == '\\' && j + 1 < length && !lineTerminator(text.charAt(j + 1))) {
          add(j, j + 2, TokenKind.ESCAPE);
          j += 2;
        } else {
          add(j, j + 1, TokenKind.STRING);
          j++;
          if (c == '"') {
            break;
          }
        }
      }
      return j;
    }

    /** Adds the token {@code [start, end)} in chars, clipped to the window. */
    private void add(int start, int end, TokenKind kind) {
      long a = Math.max(column(start), from);
      long b = Math.min(column(end), to);
      if (b <= a) {
        return;
      }
      int ordinal = kind.ordinal();
      if (count > 0 && data[3 * count - 2] == a && data[3 * count - 1] == ordinal) {
        data[3 * count - 2] = (int) b;
        return;
      }
      if (count >= Highlighter.MAX_SPANS) {
        return;
      }
      if (data.length < 3 * (count + 1)) {
        int[] grown = new int[data.length * 2];
        System.arraycopy(data, 0, grown, 0, data.length);
        data = grown;
      }
      data[3 * count] = (int) a;
      data[3 * count + 1] = (int) b;
      data[3 * count + 2] = ordinal;
      count++;
    }

    private long column(int index) {
      return index <= mapped ? columns[index] : to;
    }

    /**
     * Fills {@code columns[i]} with the display column of char i up to the first char at or past
     * the window's right edge, or the line end; returns that index.
     */
    private int mapColumns() {
      long column = 0;
      int i = 0;
      while (i < length && column < to) {
        if (i + 1 >= columns.length) {
          int[] grown = new int[columns.length * 2];
          System.arraycopy(columns, 0, grown, 0, columns.length);
          columns = grown;
        }
        columns[i] = (int) column;
        column = LineReader.advance(column, text.charAt(i));
        i++;
      }
      columns[i] = (int) Math.min(column, to);
      mapped = i;
      return i;
    }
  }
}
