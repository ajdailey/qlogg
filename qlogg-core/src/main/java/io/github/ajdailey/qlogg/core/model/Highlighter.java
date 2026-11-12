package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.text.BoundedChars;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.CRC32;

/**
 * Computes {@link HighlightSpans} for a line from rules in apply order: each rule paints over the
 * ones before it, so the last rule wins where colors overlap. A whole-line rule that matches sets
 * the line's colors (a null color keeps the earlier rules') and drops the spans of earlier rules; a
 * match-only rule adds a span per match, or per captured group when the pattern has groups.
 * Thread-safe; each {@link LineMatcher} it creates is not.
 *
 * <p>Regexes compile on first use, on the worker that reads lines, with {@link
 * StandardMatchers#regexFlags}; a literal compiles quoted. A rule whose pattern does not compile is
 * skipped. A rule that reads more than {@code 8 * length + 65,536} chars on one line is abandoned
 * for that line, and a line matcher stops coloring after {@link #BLOCK_STEPS} chars, so a
 * backtracking pattern bounds the cost of a read instead of stalling it. A line keeps at most
 * {@link #MAX_SPANS} spans; later spans are dropped.
 */
public final class Highlighter {
  /** No rules. */
  public static final Highlighter NONE = new Highlighter(List.<HighlightRule>of());

  /** Most spans one line keeps. */
  public static final int MAX_SPANS = 256;

  /** Chars a line matcher reads before it stops coloring further lines. */
  public static final long BLOCK_STEPS = 1L << 26;

  private final List<HighlightRule> rules;
  private volatile List<Pattern> compiled;

  private Highlighter(List<HighlightRule> rules) {
    this.rules = rules;
  }

  /**
   * Creates a highlighter.
   *
   * @param applyOrder the rules, lowest priority first
   * @return the highlighter, or {@link #NONE} when no rule is enabled
   */
  public static Highlighter of(List<HighlightRule> applyOrder) {
    List<HighlightRule> kept = new ArrayList<HighlightRule>();
    for (HighlightRule rule : applyOrder) {
      if (rule.enabled() && !rule.pattern().isEmpty()) {
        kept.add(rule);
      }
    }
    return kept.isEmpty() ? NONE : new Highlighter(Collections.unmodifiableList(kept));
  }

  /**
   * Returns the rules in apply order.
   *
   * @return the enabled rules with a pattern
   */
  public List<HighlightRule> rules() {
    return rules;
  }

  /**
   * Reports whether the highlighter colors nothing.
   *
   * @return true without rules
   */
  public boolean isEmpty() {
    return rules.isEmpty();
  }

  /**
   * Compiles a rule's pattern.
   *
   * @param rule the rule
   * @return the pattern
   * @throws PatternSyntaxException when a regex does not compile
   */
  public static Pattern compile(HighlightRule rule) {
    String source = rule.mode() == MatchMode.REGEX ? rule.pattern() : Pattern.quote(rule.pattern());
    return Pattern.compile(source, StandardMatchers.regexFlags(rule.caseSensitive()));
  }

  /**
   * Creates a matcher for one thread.
   *
   * @return the matcher
   */
  public LineMatcher matcher() {
    List<Pattern> patterns = compiled;
    if (patterns == null) {
      List<Pattern> built = new ArrayList<Pattern>();
      for (HighlightRule rule : rules) {
        Pattern pattern;
        try {
          pattern = compile(rule);
        } catch (PatternSyntaxException e) {
          pattern = null;
        }
        built.add(pattern);
      }
      patterns = Collections.unmodifiableList(built);
      compiled = patterns;
    }
    return new LineMatcher(rules, patterns);
  }

  /**
   * Returns a color varied for a matched string: the same string always gets the same color.
   *
   * @param rgb the rule's color
   * @param matched the matched text
   * @param variance 1 to {@link HighlightRule#MAX_VARIANCE}
   * @return the varied color
   */
  static Rgb vary(Rgb rgb, CharSequence matched, int variance) {
    CRC32 crc = new CRC32();
    crc.update(matched.toString().getBytes(StandardCharsets.UTF_8));
    int factor = 100 - variance + (int) (crc.getValue() % (2L * variance + 1));
    return scale(rgb, 100.0 / factor);
  }

  /** Scales the color's brightness; past white, the excess moves it toward white. */
  private static Rgb scale(Rgb rgb, double k) {
    double r = rgb.red() * k;
    double g = rgb.green() * k;
    double b = rgb.blue() * k;
    double max = Math.max(r, Math.max(g, b));
    if (max > 255) {
      double over = (max - 255) / max;
      r = 255 * (r / max) + (255 - 255 * (r / max)) * over;
      g = 255 * (g / max) + (255 - 255 * (g / max)) * over;
      b = 255 * (b / max) + (255 - 255 * (b / max)) * over;
    }
    return new Rgb(clamp(r), clamp(g), clamp(b));
  }

  private static int clamp(double v) {
    return (int) Math.max(0, Math.min(255, Math.round(v)));
  }

  /**
   * Packs a color.
   *
   * @param rgb the color, or null
   * @param alpha 0 to 255
   * @return {@code 0xAARRGGBB}; 0 for null or alpha 0
   */
  public static int argb(Rgb rgb, int alpha) {
    if (rgb == null || alpha == 0) {
      return 0;
    }
    return alpha << 24 | rgb.red() << 16 | rgb.green() << 8 | rgb.blue();
  }

  /** Spans of one line under construction, sorted, without overlap. */
  private static final class Spans {
    private int[] data = new int[4 * 16];
    private int count;

    void clear() {
      count = 0;
    }

    boolean full() {
      return count >= MAX_SPANS;
    }

    /** Paints [start, end) over what is there. */
    void paint(int start, int end, int foreground, int background) {
      int[] next = new int[4 * (count + 2)];
      int n = 0;
      for (int i = 0; i < count; i++) {
        int a = data[4 * i];
        int b = data[4 * i + 1];
        int f = data[4 * i + 2];
        int g = data[4 * i + 3];
        if (b <= start || a >= end) {
          n = put(next, n, a, b, f, g);
          continue;
        }
        if (a < start) {
          n = put(next, n, a, start, f, g);
        }
        if (b > end) {
          n = put(next, n, end, b, f, g);
        }
      }
      n = put(next, n, start, end, foreground, background);
      for (int k = n / 4 - 1; k > 0 && next[4 * (k - 1)] > next[4 * k]; k--) {
        for (int j = 0; j < 4; j++) {
          int swap = next[4 * k + j];
          next[4 * k + j] = next[4 * (k - 1) + j];
          next[4 * (k - 1) + j] = swap;
        }
      }
      data = next;
      count = n / 4;
    }

    private static int put(int[] array, int at, int a, int b, int foreground, int background) {
      array[at] = a;
      array[at + 1] = b;
      array[at + 2] = foreground;
      array[at + 3] = background;
      return at + 4;
    }

    HighlightSpans freeze(int lineForeground, int lineBackground) {
      int kept = Math.min(count, MAX_SPANS);
      if (kept == 0 && lineForeground == 0 && lineBackground == 0) {
        return HighlightSpans.NONE;
      }
      int[] copy = new int[4 * kept];
      System.arraycopy(data, 0, copy, 0, copy.length);
      return new HighlightSpans(lineForeground, lineBackground, copy, kept);
    }
  }

  /** Computes spans line by line on one thread. */
  public static final class LineMatcher {
    private final List<HighlightRule> rules;
    private final Matcher[] matchers;
    private final BoundedChars chars = new BoundedChars();
    private final Spans spans = new Spans();
    private int[] columns = new int[256];
    private long budget = BLOCK_STEPS;

    LineMatcher(List<HighlightRule> rules, List<Pattern> patterns) {
      this.rules = rules;
      this.matchers = new Matcher[patterns.size()];
      for (int i = 0; i < matchers.length; i++) {
        Pattern pattern = patterns.get(i);
        matchers[i] = pattern == null ? null : pattern.matcher("");
      }
    }

    /**
     * Colors a line, clipped to a column window.
     *
     * @param text the decoded line, tabs kept, terminator excluded
     * @param firstColumn first display column kept
     * @param windowColumns width of the window
     * @return the spans; {@link HighlightSpans#NONE} once the matcher's budget is spent
     */
    public HighlightSpans spans(CharSequence text, long firstColumn, int windowColumns) {
      if (rules.isEmpty() || budget <= 0) {
        return HighlightSpans.NONE;
      }
      mapColumns(text);
      long from = firstColumn;
      long to = firstColumn + windowColumns;
      spans.clear();
      int lineForeground = 0;
      int lineBackground = 0;
      long limit = BoundedChars.lineLimit(text);
      for (int r = 0; r < rules.size() && budget > 0; r++) {
        Matcher matcher = matchers[r];
        if (matcher == null) {
          continue;
        }
        HighlightRule rule = rules.get(r);
        matcher.reset(chars.reset(text, Math.min(limit, budget)));
        try {
          if (rule.wholeLine()) {
            if (matcher.find()) {
              if (rule.foreground() != null) {
                lineForeground = argb(rule.foreground(), 255);
              }
              if (rule.background() != null && rule.alpha() > 0) {
                lineBackground = argb(rule.background(), rule.alpha());
              }
              spans.clear();
            }
          } else {
            matchOnly(rule, matcher, text, from, to);
          }
        } catch (BoundedChars.LimitReached | StackOverflowError e) {
          // This rule is abandoned for this line; spans it added so far stay. A deeply
          // recursive pattern on a long line overflows the regex engine's stack instead.
        }
        budget -= chars.used();
      }
      return spans.freeze(lineForeground, lineBackground);
    }

    private void matchOnly(
        HighlightRule rule, Matcher matcher, CharSequence text, long from, long to) {
      int groups = matcher.groupCount();
      int at = 0;
      while (!spans.full() && at <= text.length() && matcher.find(at)) {
        if (groups == 0) {
          add(rule, text, matcher.start(), matcher.end(), from, to);
        } else {
          for (int g = 1; g <= groups && !spans.full(); g++) {
            int start = matcher.start(g);
            if (start >= 0) {
              add(rule, text, start, matcher.end(g), from, to);
            }
          }
        }
        at = matcher.end() > matcher.start() ? matcher.end() : matcher.end() + 1;
      }
    }

    private void add(
        HighlightRule rule, CharSequence text, int start, int end, long from, long to) {
      if (end <= start) {
        return;
      }
      long a = Math.max(columns[start], from);
      long b = Math.min(columns[end], to);
      if (b <= a) {
        return;
      }
      Rgb foreground = rule.foreground();
      Rgb background = rule.background();
      if (rule.variance() > 0) {
        CharSequence matched = text.subSequence(start, end);
        foreground = foreground == null ? null : vary(foreground, matched, rule.variance());
        background = background == null ? null : vary(background, matched, rule.variance());
      }
      spans.paint((int) a, (int) b, argb(foreground, 255), argb(background, rule.alpha()));
    }

    /** Fills {@code columns[i]} with the display column of char i, and of the end at length. */
    private void mapColumns(CharSequence text) {
      int length = text.length();
      if (columns.length < length + 1) {
        columns = new int[Math.max(length + 1, columns.length * 2)];
      }
      long column = 0;
      for (int i = 0; i < length; i++) {
        columns[i] = (int) column;
        column = LineReader.advance(column, text.charAt(i));
      }
      columns[length] = (int) column;
    }
  }
}
