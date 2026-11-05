package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Finds the range around a line for a range query: the nearest line at or above it that matches the
 * start expression, through the first later line that matches the end expression, or through the
 * last line when none does. Matching follows the query's case, whole-word and ANSI rules.
 *
 * <p>This is a local rule: a range search ({@link SearchEngine}) ignores a start line inside an
 * open range, so where start lines repeat before an end, the search's range begins at the first of
 * them and this one at the nearest.
 *
 * <p>Blocking; runs on a worker. Lines are read in blocks of at most {@value #BLOCK_LINES} lines
 * and {@value #BLOCK_BYTES} bytes through one heap buffer of that size; a longer line is matched on
 * its first {@value #BLOCK_BYTES} bytes. The token is checked per block and through the matcher.
 */
public final class RangeFinder {
  /** Bytes read at a time; also the longest line prefix matched. */
  public static final int BLOCK_BYTES = 1 << 20;

  /** Lines read at a time. */
  public static final int BLOCK_LINES = 4096;

  /**
   * A range of lines.
   *
   * @param first the 0-based first line
   * @param last the 0-based last line, inclusive
   */
  public record Range(long first, long last) {}

  private final ByteSource bytes;
  private final LineIndex index;
  private final TextEncoding encoding;
  private final CancellationToken token;
  private final LineText text;
  private final ByteBuffer buffer = ByteBuffer.allocate(BLOCK_BYTES);
  private final int[] starts = new int[BLOCK_LINES];
  private final int[] ends = new int[BLOCK_LINES];

  private RangeFinder(
      LineSource source, LineIndex index, boolean hideAnsi, CancellationToken token) {
    this.bytes = source.bytes();
    this.index = index;
    this.encoding = source.encoding();
    this.token = token;
    this.text = new LineText(encoding, token, hideAnsi);
  }

  /**
   * Finds the range around a line.
   *
   * @param source the document
   * @param index the snapshot to search
   * @param query a range query; its scope and {@code inverse} are not used
   * @param matchers compiles the start and end expressions
   * @param line the 0-based line
   * @param token checked while searching
   * @return the range, or null when no line at or above matches the start or the range ends above
   *     the line
   * @throws IOException on a read error
   * @throws java.util.concurrent.CancellationException once the token is canceled
   * @throws PatternTooComplexException when a regex overflows the stack on a line
   */
  public static Range around(
      LineSource source,
      LineIndex index,
      SearchQuery query,
      MatcherFactory matchers,
      long line,
      CancellationToken token)
      throws IOException {
    if (!query.range()) {
      throw new IllegalArgumentException("not a range query");
    }
    long count = index.lineCount();
    if (line < 0 || line >= count) {
      return null;
    }
    RangeFinder finder = new RangeFinder(source, index, query.hideAnsi(), token);
    TextEncoding encoding = source.encoding();
    LineMatcher start = matchers.create(query.lineSearch(query.expression()), encoding, token);
    long first = finder.backward(start, line);
    if (first < 0) {
      return null;
    }
    LineMatcher end = matchers.create(query.lineSearch(query.rangeEnd()), encoding, token);
    long last = finder.forward(end, first + 1, count);
    if (last < 0) {
      last = count - 1;
    }
    return last < line ? null : new Range(first, last);
  }

  /** The first line in {@code [from, to)} that matches, or -1. */
  private long forward(LineMatcher matcher, long from, long to) throws IOException {
    long a = from;
    while (a < to) {
      token.check();
      long b = Math.min(to, a + BLOCK_LINES) - 1;
      long start = index.lineStart(a);
      if (index.lineEnd(b) - start > BLOCK_BYTES) {
        b = Math.max(a, Math.min(b, index.lineAt(start + BLOCK_BYTES) - 1));
      }
      int n = read(a, b);
      for (int i = 0; i < n; i++) {
        if (matches(matcher, i, a + i)) {
          return a + i;
        }
      }
      a = b + 1;
    }
    return -1;
  }

  /** The last line in {@code [0, from]} that matches, or -1. */
  private long backward(LineMatcher matcher, long from) throws IOException {
    long b = from;
    while (b >= 0) {
      token.check();
      long a = Math.max(0, b - BLOCK_LINES + 1);
      long finish = index.lineEnd(b);
      if (finish - index.lineStart(a) > BLOCK_BYTES) {
        a = Math.min(b, Math.max(a, index.lineAt(finish - BLOCK_BYTES) + 1));
      }
      int n = read(a, b);
      for (int i = n - 1; i >= 0; i--) {
        if (matches(matcher, i, a + i)) {
          return a + i;
        }
      }
      b = a - 1;
    }
    return -1;
  }

  private boolean matches(LineMatcher matcher, int i, long line) {
    text.set(buffer, starts[i], ends[i]);
    try {
      return matcher.matches(text);
    } catch (StackOverflowError e) {
      throw new PatternTooComplexException(line);
    }
  }

  /**
   * Reads lines {@code [a, b]} and records each line's text bounds, a trailing CR dropped. A single
   * line longer than the buffer is cut to it.
   *
   * @return the line count
   */
  private int read(long a, long b) throws IOException {
    long position = index.lineStart(a);
    int unit = encoding.unitWidth();
    long span = index.lineEnd(b) - position;
    int length = (int) Math.min(span, BLOCK_BYTES / unit * unit);
    buffer.clear().limit(length);
    while (buffer.hasRemaining()) {
      if (bytes.read(position + buffer.position(), buffer) < 0) {
        throw new IOException("File is shorter than its line index");
      }
    }
    int lines = (int) (b - a + 1);
    byte[] lineFeed = encoding.lineFeed();
    byte[] carriageReturn = encoding.carriageReturn();
    int at = 0;
    for (int i = 0; i < lines; i++) {
      int lineEnd = i == lines - 1 ? length : find(at, length, lineFeed);
      if (lineEnd < 0) {
        throw new IOException("File changed after it was indexed");
      }
      int textEnd = lineEnd;
      if (lineEnd - at >= unit && unitAt(lineEnd - unit, carriageReturn)) {
        textEnd = lineEnd - unit;
      }
      starts[i] = at;
      ends[i] = textEnd;
      at = lineEnd + unit;
    }
    return lines;
  }

  private int find(int from, int to, byte[] unit) {
    for (int i = from; i + unit.length <= to; i += unit.length) {
      if (unitAt(i, unit)) {
        return i;
      }
    }
    return -1;
  }

  private boolean unitAt(int at, byte[] unit) {
    for (int k = 0; k < unit.length; k++) {
      if (buffer.get(at + k) != unit[k]) {
        return false;
      }
    }
    return true;
  }
}
