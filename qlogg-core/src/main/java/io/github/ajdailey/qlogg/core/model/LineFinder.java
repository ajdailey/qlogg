package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.CpuSteps;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.search.PatternTooComplexException;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the nearest occurrence of a pattern before or after a position, line by line, for quick
 * find. Lines are read and decoded through a {@link LineReader}, so the first {@link
 * LineReader#MAX_LINE_BYTES} of a line are searched, as they are shown. Empty matches are skipped.
 *
 * <p>Blocking; runs on a worker, in steps on the CPU pool when given {@link Workers}. The token is
 * checked every {@link #CHECK_LINES} lines and, through the chars the matcher reads, every {@link
 * #CHECK_CHARS} chars, so a regex that backtracks without end stops on cancel.
 */
public final class LineFinder {
  /** Lines between cancellation checks. */
  public static final int CHECK_LINES = 1024;

  /** Chars a matcher reads between cancellation checks. */
  public static final int CHECK_CHARS = 4096;

  /**
   * One occurrence, in display columns.
   *
   * @param line the 0-based line
   * @param startColumn the first column of the match
   * @param endColumn the column after the match
   */
  public record Match(long line, long startColumn, long endColumn) {}

  private static final int[] NONE = new int[0];

  private LineFinder() {}

  /**
   * Finds the nearest occurrence after or before a position. Forward, it is the first match that
   * starts at or after the column on the start line, then the first match of each following listed
   * line. Backward, it is the last match that starts before the column, then the last match of each
   * preceding listed line.
   *
   * @param reader reads the lines
   * @param index the snapshot to search
   * @param rows the lines to visit, such as every line or the filtered view's rows
   * @param pattern the compiled pattern
   * @param line the start line, or -1 to begin before the first listed line
   * @param column the display column on the start line
   * @param forward true to search toward the end
   * @param token checked while searching
   * @return the match, or null when none is left in that direction
   * @throws IOException on a read error
   * @throws java.util.concurrent.CancellationException once the token is canceled
   * @throws PatternTooComplexException when the pattern overflows the stack on a line
   */
  public static Match find(
      LineReader reader,
      LineIndex index,
      RowCursor rows,
      Pattern pattern,
      long line,
      long column,
      boolean forward,
      CancellationToken token)
      throws IOException {
    return find(null, reader, index, rows, pattern, line, column, forward, token);
  }

  /**
   * Finds as {@link #find(LineReader, LineIndex, RowCursor, Pattern, long, long, boolean,
   * CancellationToken)} does, in steps of {@link CpuSteps#STEP_LINES} lines on the CPU pool.
   *
   * @param cpu runs the steps ({@link CpuSteps#run}), or null to run them on the calling thread
   * @param reader reads the lines
   * @param index the snapshot to search
   * @param rows the lines to visit
   * @param pattern the compiled pattern
   * @param line the start line, or -1 to begin before the first listed line
   * @param column the display column on the start line
   * @param forward true to search toward the end
   * @param token checked while searching
   * @return the match, or null when none is left in that direction
   * @throws IOException on a read error
   * @throws java.util.concurrent.CancellationException once the token is canceled
   * @throws PatternTooComplexException when the pattern overflows the stack on a line
   */
  public static Match find(
      Workers cpu,
      LineReader reader,
      LineIndex index,
      RowCursor rows,
      Pattern pattern,
      long line,
      long column,
      boolean forward,
      CancellationToken token)
      throws IOException {
    Scan scan = new Scan(reader, index, rows, pattern, line, column, forward, token);
    CpuSteps.run(cpu, scan);
    if (scan.tooComplexAt >= 0) {
      throw new PatternTooComplexException(scan.tooComplexAt);
    }
    return scan.match;
  }

  /** The scan's position between steps. */
  private static final class Scan implements CpuSteps.Steps {
    private final LineReader reader;
    private final LineIndex index;
    private final RowCursor rows;
    private final long column;
    private final boolean forward;
    private final CancellationToken token;
    private final long count;
    private final CheckedChars chars;
    private final Matcher matcher;
    private long current;
    private boolean first;
    private long visited;
    private Match match;
    private long tooComplexAt = -1;

    Scan(
        LineReader reader,
        LineIndex index,
        RowCursor rows,
        Pattern pattern,
        long line,
        long column,
        boolean forward,
        CancellationToken token) {
      this.reader = reader;
      this.index = index;
      this.rows = rows;
      this.column = column;
      this.forward = forward;
      this.token = token;
      this.count = index.lineCount();
      this.chars = new CheckedChars(token);
      this.matcher = pattern.matcher("");
      this.current = line;
      this.first = line >= 0 && line < count;
      if (!first) {
        current = forward ? rows.next(-1) : rows.previous(count);
      }
    }

    @Override
    public boolean step() throws IOException {
      for (int n = 0; n < CpuSteps.STEP_LINES; n++) {
        if (current < 0 || current >= count) {
          return false;
        }
        if (visited++ % CHECK_LINES == 0) {
          token.check();
        }
        CharSequence text = reader.decodedLine(index, current);
        chars.set(text);
        matcher.reset(chars);
        int limit = first ? charIndex(text, column) : (forward ? 0 : text.length() + 1);
        int[] found;
        try {
          found = forward ? firstFrom(matcher, limit) : lastBefore(matcher, limit);
        } catch (StackOverflowError e) {
          tooComplexAt = current;
          return false;
        }
        if (found.length == 2) {
          match = new Match(current, columnOf(text, found[0]), columnOf(text, found[1]));
          return false;
        }
        first = false;
        current = forward ? rows.next(current) : rows.previous(current);
      }
      return true;
    }
  }

  /** The first non-empty match starting at or after a char, as {start, end}; empty for none. */
  private static int[] firstFrom(Matcher matcher, int from) {
    int at = from;
    while (at <= matcher.regionEnd() && matcher.find(at)) {
      if (matcher.end() > matcher.start()) {
        return new int[] {matcher.start(), matcher.end()};
      }
      at = matcher.end() + 1;
    }
    return NONE;
  }

  /** The last non-empty match starting before a char, as {start, end}; empty for none. */
  private static int[] lastBefore(Matcher matcher, int before) {
    int[] last = NONE;
    int at = 0;
    while (at <= matcher.regionEnd() && matcher.find(at) && matcher.start() < before) {
      if (matcher.end() > matcher.start()) {
        last = new int[] {matcher.start(), matcher.end()};
        at = matcher.start() + 1;
      } else {
        at = matcher.end() + 1;
      }
    }
    return last;
  }

  /** The index of the first char at or after a display column. */
  static int charIndex(CharSequence text, long column) {
    long seen = 0;
    int i = 0;
    while (i < text.length() && seen < column) {
      seen = LineReader.advance(seen, text.charAt(i));
      i++;
    }
    while (i < text.length() && Character.isLowSurrogate(text.charAt(i))) {
      i++;
    }
    return i;
  }

  /** The display column of a char. */
  static long columnOf(CharSequence text, int charIndex) {
    long column = 0;
    for (int i = 0; i < charIndex && i < text.length(); i++) {
      column = LineReader.advance(column, text.charAt(i));
    }
    return column;
  }

  /** A line's chars, checking the token as a matcher reads them. */
  private static final class CheckedChars implements CharSequence {
    private final CancellationToken token;
    private CharSequence text = "";
    private int calls;

    CheckedChars(CancellationToken token) {
      this.token = token;
    }

    void set(CharSequence line) {
      text = line;
    }

    @Override
    public int length() {
      return text.length();
    }

    @Override
    public char charAt(int index) {
      if (++calls >= CHECK_CHARS) {
        calls = 0;
        token.check();
      }
      return text.charAt(index);
    }

    @Override
    public CharSequence subSequence(int from, int to) {
      return text.subSequence(from, to).toString();
    }

    @Override
    public String toString() {
      return text.toString();
    }
  }
}
