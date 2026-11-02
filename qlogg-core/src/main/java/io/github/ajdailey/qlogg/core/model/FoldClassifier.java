package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import java.io.IOException;
import java.util.Arrays;

/**
 * Finds the multi-line entries of a region by reading its lines, never the whole file. An entry is
 * a line that does not continue the one above it, plus the continuation lines below it ({@link
 * FoldRules}). Line 0 always starts an entry. Runs on a worker.
 *
 * <p>To find the start of an entry that begins above the region, the scan goes back at most {@link
 * #MAX_ENTRY_LINES} lines; an entry longer than that, in either direction, is not reported, so it
 * stays unfolded.
 */
public final class FoldClassifier {
  /** Longest entry reported, in lines. */
  public static final int MAX_ENTRY_LINES = 10_000;

  private FoldClassifier() {}

  /**
   * Returns the entries of two or more lines that overlap a region.
   *
   * @param reader reads the lines
   * @param index the snapshot read
   * @param rules the compiled rules
   * @param from the region's first line
   * @param to the line after the region; lines past the index are left out
   * @param token checked per line
   * @return {@code [start0, end0, start1, end1, ...]} in line order, each end exclusive
   * @throws IOException on a read error
   * @throws java.util.concurrent.CancellationException when canceled
   */
  public static long[] entries(
      LineReader reader,
      LineIndex index,
      FoldRules.Compiled rules,
      long from,
      long to,
      CancellationToken token)
      throws IOException {
    long count = index.lineCount();
    long end = Math.min(to, count);
    if (from >= end || from < 0) {
      return new long[0];
    }
    long start = from;
    while (start > 0 && continues(reader, index, rules, start)) {
      token.check();
      if (from - start >= MAX_ENTRY_LINES) {
        start = nextStart(reader, index, rules, from, end, token);
        break;
      }
      start--;
    }
    long[] found = new long[16];
    int n = 0;
    while (start >= 0 && start < end) {
      long line = start + 1;
      while (line < count
          && line - start <= MAX_ENTRY_LINES
          && continues(reader, index, rules, line)) {
        token.check();
        line++;
      }
      if (line - start > MAX_ENTRY_LINES) {
        start = nextStart(reader, index, rules, line, end, token);
        continue;
      }
      if (line - start >= 2) {
        if (n + 2 > found.length) {
          found = Arrays.copyOf(found, found.length * 2);
        }
        found[n++] = start;
        found[n++] = line;
      }
      start = line;
    }
    return Arrays.copyOf(found, n);
  }

  /** The first line at or after {@code from}, below {@code end}, that starts an entry, or -1. */
  private static long nextStart(
      LineReader reader,
      LineIndex index,
      FoldRules.Compiled rules,
      long from,
      long end,
      CancellationToken token)
      throws IOException {
    for (long line = from; line < end; line++) {
      token.check();
      if (!continues(reader, index, rules, line)) {
        return line;
      }
    }
    return -1;
  }

  private static boolean continues(
      LineReader reader, LineIndex index, FoldRules.Compiled rules, long line) throws IOException {
    return line > 0 && rules.continues(reader.decodedLine(index, line));
  }
}
