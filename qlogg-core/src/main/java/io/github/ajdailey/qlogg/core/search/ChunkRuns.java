package io.github.ajdailey.qlogg.core.search;

import java.util.Arrays;

/**
 * The byte ranges of one chunk, in line order: each run is a span of whole lines read in one
 * positional read, and the runs sit end to end in the chunk's buffer. A run's last line ends at its
 * last byte, before its LF.
 */
final class ChunkRuns {
  private long[] firstLines = new long[4];
  private int[] lineCounts = new int[4];
  private long[] starts = new long[4];
  private int[] lengths = new int[4];
  private int count;
  private int lines;

  /**
   * Appends a run.
   *
   * @param firstLine the run's first line
   * @param lastLine the run's last line
   * @param start the first line's first byte
   * @param finish the last line's end before its LF
   */
  void add(long firstLine, long lastLine, long start, long finish) {
    if (count == firstLines.length) {
      int size = count * 2;
      firstLines = Arrays.copyOf(firstLines, size);
      lineCounts = Arrays.copyOf(lineCounts, size);
      starts = Arrays.copyOf(starts, size);
      lengths = Arrays.copyOf(lengths, size);
    }
    int runLines = (int) (lastLine - firstLine + 1);
    firstLines[count] = firstLine;
    lineCounts[count] = runLines;
    starts[count] = start;
    lengths[count] = (int) Math.min(finish - start, Integer.MAX_VALUE);
    count++;
    lines += runLines;
  }

  /** Runs held. */
  int count() {
    return count;
  }

  /** Lines of every run, members and the lines between them. */
  int lines() {
    return lines;
  }

  long firstLine(int run) {
    return firstLines[run];
  }

  int lineCount(int run) {
    return lineCounts[run];
  }

  long start(int run) {
    return starts[run];
  }

  int length(int run) {
    return lengths[run];
  }
}
