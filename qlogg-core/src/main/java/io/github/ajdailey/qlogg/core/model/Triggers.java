package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.text.BoundedChars;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The highlighter rules with a {@link RuleTrigger}, compiled, and the scan that applies them to
 * appended lines. Every matching rule acts, whatever its color precedence. Immutable apart from the
 * once-only template failure report; each {@link #scan} uses its own matchers, so scans on several
 * threads do not interfere.
 *
 * <p>A rule matches a line as the highlighter matches it ({@link Highlighter#compile}, {@code
 * find}); a match that reads more than {@code 8 * length + 65,536} chars or overflows the stack
 * counts as no match. A rule whose pattern does not compile is left out.
 */
public final class Triggers {
  /** Lines one {@link #scan} call reads at most; the caller loops over larger growth. */
  public static final int BATCH_LINES = 4096;

  /** No trigger. */
  public static final Triggers NONE = new Triggers(List.<Entry>of());

  /**
   * What one scan did.
   *
   * @param from the first line scanned
   * @param to the line after the last line scanned; less than asked when canceled
   * @param loudLines lines no quiet rule matched
   * @param stopRule the pattern of the first rule that stops follow, or null
   * @param stopLine the 0-based line it matched, or -1
   * @param marked lines a rule marked, already marked ones included
   * @param refused true when the budget or the comment cap refused a mark or a comment
   * @param quietRefused true when the budget refused the quiet lines
   * @param failures one text per rule whose template failed for the first time: the pattern and the
   *     reason
   */
  public record Batch(
      long from,
      long to,
      long loudLines,
      String stopRule,
      long stopLine,
      int marked,
      boolean refused,
      boolean quietRefused,
      List<String> failures) {}

  /** One rule with its compiled pattern and template. */
  private record Entry(
      HighlightRule rule, Pattern pattern, CommentTemplate template, AtomicBoolean reported) {}

  private final List<Entry> entries;
  private final boolean quiet;

  private Triggers(List<Entry> entries) {
    this.entries = entries;
    boolean any = false;
    for (Entry entry : entries) {
      any |= entry.rule().trigger().quiet();
    }
    this.quiet = any;
  }

  /**
   * Compiles the rules that act.
   *
   * @param priorityOrder rules, highest priority first; that order picks the comment of a line
   *     several marking rules match
   * @return the triggers, or {@link #NONE} when no enabled rule with a pattern has an action
   */
  public static Triggers of(List<HighlightRule> priorityOrder) {
    List<Entry> kept = new ArrayList<Entry>();
    for (HighlightRule rule : priorityOrder) {
      if (!rule.enabled() || rule.pattern().isEmpty() || !rule.trigger().any()) {
        continue;
      }
      Pattern pattern;
      try {
        pattern = Highlighter.compile(rule);
      } catch (PatternSyntaxException e) {
        continue;
      }
      kept.add(
          new Entry(
              rule,
              pattern,
              CommentTemplate.parse(rule.trigger().commentTemplate()),
              new AtomicBoolean()));
    }
    return kept.isEmpty() ? NONE : new Triggers(Collections.unmodifiableList(kept));
  }

  /**
   * Reports whether no rule acts.
   *
   * @return true without triggers
   */
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /**
   * Reports whether a rule keeps its lines off the tab's data dot.
   *
   * @return true when a quiet rule exists
   */
  public boolean quiet() {
    return quiet;
  }

  /**
   * Scans lines, at most {@link #BATCH_LINES}, and applies the marks and comments. Marks of the
   * batch are added together and reserve their storage first: a refusal adds none of them and no
   * comment. A line keeps an existing comment; otherwise it takes the expansion of the first
   * marking rule, in priority order, with a template, or the template's text when the expansion
   * fails.
   *
   * @param reader reads the lines; owned by the calling thread
   * @param index the snapshot to read
   * @param path the document's path, or null without a file
   * @param from the first 0-based line
   * @param to the line after the last; lines past {@code from + BATCH_LINES} are not scanned
   * @param marks the document's marks
   * @param comments the document's mark comments
   * @param quietLines receives the lines a quiet rule matched, or null to keep none
   * @param token checked per line
   * @return what the scan did
   * @throws IOException on a read error
   */
  public Batch scan(
      LineReader reader,
      LineIndex index,
      Path path,
      long from,
      long to,
      MutableLineSet marks,
      MarkComments comments,
      MutableLineSet quietLines,
      CancellationToken token)
      throws IOException {
    long end = Math.min(to, from + BATCH_LINES);
    int size = (int) Math.max(0, end - from);
    Matcher[] matchers = new Matcher[entries.size()];
    for (int i = 0; i < matchers.length; i++) {
      matchers[i] = entries.get(i).pattern().matcher("");
    }
    BoundedChars chars = new BoundedChars();
    long[] marked = new long[Math.min(size, 64)];
    String[] texts = new String[marked.length];
    int markCount = 0;
    long[] quieted = new long[Math.min(size, 64)];
    int quietCount = 0;
    int commentRoom = MarkComments.MAX_COMMENTS - comments.size();
    int commentCount = 0;
    boolean capped = false;
    long loud = 0;
    String stopRule = null;
    long stopLine = -1;
    List<String> failures = new ArrayList<String>();
    long line = from;
    for (; line < end; line++) {
      if (token.isCanceled()) {
        break;
      }
      CharSequence text = reader.decodedLine(index, line);
      boolean isQuiet = false;
      boolean mark = false;
      String comment = null;
      for (int r = 0; r < matchers.length; r++) {
        Entry entry = entries.get(r);
        if (!matches(matchers[r], chars, text)) {
          continue;
        }
        RuleTrigger trigger = entry.rule().trigger();
        isQuiet |= trigger.quiet();
        if (trigger.stopFollow() && stopRule == null) {
          stopRule = entry.rule().pattern();
          stopLine = line;
        }
        if (trigger.setMark()) {
          mark = true;
          if (comment == null && !trigger.commentTemplate().isEmpty()) {
            if (commentCount < commentRoom) {
              comment = expand(entry, line, path, text, failures);
            } else {
              capped = true;
            }
          }
        }
      }
      if (mark) {
        if (markCount == marked.length) {
          marked = Arrays.copyOf(marked, Math.min(size, markCount * 2));
          texts = Arrays.copyOf(texts, marked.length);
        }
        marked[markCount] = line;
        texts[markCount] = comment;
        markCount++;
        if (comment != null) {
          commentCount++;
        }
      }
      if (isQuiet) {
        if (quietCount == quieted.length) {
          quieted = Arrays.copyOf(quieted, Math.min(size, quietCount * 2));
        }
        quieted[quietCount++] = line;
      } else {
        loud++;
      }
    }
    boolean refused = capped;
    int added = 0;
    if (markCount > 0) {
      if (marks.addSorted(marked, markCount)) {
        added = markCount;
        for (int i = 0; i < markCount; i++) {
          if (texts[i] != null
              && comments.get(marked[i]) == null
              && !comments.put(marked[i], texts[i])) {
            refused = true;
          }
        }
      } else {
        refused = true;
      }
    }
    boolean quietRefused =
        quietLines != null && quietCount > 0 && !quietLines.addSorted(quieted, quietCount);
    return new Batch(
        from, line, loud, stopRule, stopLine, added, refused, quietRefused, List.copyOf(failures));
  }

  private static boolean matches(Matcher matcher, BoundedChars chars, CharSequence text) {
    matcher.reset(chars.reset(text, BoundedChars.lineLimit(text)));
    try {
      return matcher.find();
    } catch (BoundedChars.LimitReached | StackOverflowError e) {
      return false;
    }
  }

  private static String expand(
      Entry entry, long line, Path path, CharSequence text, List<String> failures) {
    try {
      return entry.template().expand(line, path, text);
    } catch (CommentTemplate.TemplateException e) {
      if (entry.reported().compareAndSet(false, true)) {
        failures.add(entry.rule().pattern() + ": " + e.getMessage());
      }
      return entry.template().text();
    }
  }
}
