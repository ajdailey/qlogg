package io.github.ajdailey.qlogg.core.time;

/**
 * Shading levels of the overview bar's pixel rows, from a stamp at each row boundary. Row {@code y}
 * covers lines {@code [lines[y], lines[y + 1])} and the time from sample {@code y} to sample {@code
 * y + 1}. The level is {@code floor(log2(ratio))}, 0 below twice the average and {@link #MAX_LEVEL}
 * from a ratio of 16:
 *
 * <ul>
 *   <li>{@link SpreadMode#PAUSES}: the row's time against the average row's time.
 *   <li>{@link SpreadMode#ACTIVITY}: the row's lines per second against the whole span's. A row
 *       with lines and no elapsed time takes {@link #MAX_LEVEL}.
 * </ul>
 *
 * <p>A row whose time runs backward is {@link #DISORDER} in both modes and stays out of the
 * averages, as does a row with no lines or without a stamp at either boundary, which has level 0.
 * Immutable.
 *
 * @param mode the mode the levels were computed for
 * @param levels one level per pixel row
 */
public record TimeSpread(SpreadMode mode, byte[] levels) {
  /** No rows. */
  public static final TimeSpread EMPTY = new TimeSpread(SpreadMode.OFF, new byte[0]);

  /** Strongest shading. */
  public static final int MAX_LEVEL = 4;

  /** The level of a row whose time runs backward. */
  public static final byte DISORDER = -1;

  /** Bytes per instance beyond its array. */
  public static final int OVERHEAD = 64;

  /**
   * Computes the levels.
   *
   * @param samples one stamp per row boundary, so one more than the rows
   * @param lines the line at each row boundary, ascending, as many as the samples
   * @param mode {@link SpreadMode#ACTIVITY} or {@link SpreadMode#PAUSES}
   * @return the levels
   */
  public static TimeSpread of(TimeSamples samples, long[] lines, SpreadMode mode) {
    if (lines.length != samples.count() || mode == SpreadMode.OFF) {
      throw new IllegalArgumentException(lines.length + " lines, " + mode);
    }
    byte[] levels = new byte[lines.length - 1];
    double time = 0;
    double counted = 0;
    int rows = 0;
    for (int y = 0; y < levels.length; y++) {
      if (measured(samples, lines, y)) {
        if (samples.nanos(y + 1) < samples.nanos(y)) {
          levels[y] = DISORDER;
        } else {
          time += span(samples, y);
          counted += lines[y + 1] - lines[y];
          rows++;
        }
      }
    }
    if (time > 0) {
      for (int y = 0; y < levels.length; y++) {
        if (levels[y] != DISORDER && measured(samples, lines, y)) {
          double span = span(samples, y);
          double ratio =
              mode == SpreadMode.PAUSES
                  ? span * rows / time
                  : (lines[y + 1] - lines[y]) * time / (span * counted);
          levels[y] = level(ratio);
        }
      }
    }
    return new TimeSpread(mode, levels);
  }

  private static boolean measured(TimeSamples samples, long[] lines, int y) {
    return lines[y + 1] > lines[y]
        && samples.nanos(y) != TimestampParser.NONE
        && samples.nanos(y + 1) != TimestampParser.NONE;
  }

  private static double span(TimeSamples samples, int y) {
    return (double) samples.nanos(y + 1) - (double) samples.nanos(y);
  }

  private static byte level(double ratio) {
    return ratio < 2 ? 0 : (byte) Math.min(MAX_LEVEL, Math.getExponent(ratio));
  }

  /**
   * Returns the bar's height in pixels.
   *
   * @return pixel rows
   */
  public int height() {
    return levels.length;
  }

  /**
   * Returns the heap this instance holds.
   *
   * @return bytes
   */
  public long retainedBytes() {
    return OVERHEAD + levels.length;
  }
}
