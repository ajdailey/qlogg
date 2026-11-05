package io.github.ajdailey.qlogg.core.time;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Shading from constructed samples: pauses, activity, disorder and rows without a measure. */
class TimeSpreadTest {
  private static final long SECOND = 1_000_000_000L;
  private static final long NONE = TimestampParser.NONE;

  /** Boundaries of equal rows of 100 lines. */
  private static long[] evenLines(int boundaries) {
    long[] lines = new long[boundaries];
    for (int i = 0; i < boundaries; i++) {
      lines[i] = i * 100L;
    }
    return lines;
  }

  /** Stamps whose row spans are the given seconds. */
  private static TimeSamples spans(long... seconds) {
    long[] nanos = new long[seconds.length + 1];
    for (int i = 0; i < seconds.length; i++) {
      nanos[i + 1] = nanos[i] + seconds[i] * SECOND;
    }
    return TimeSamples.of(nanos);
  }

  private static byte[] levels(TimeSamples samples, SpreadMode mode) {
    return TimeSpread.of(samples, evenLines(samples.count()), mode).levels();
  }

  @Test
  void evenSpacingShadesNothing() {
    byte[] none = new byte[4];
    assertArrayEquals(none, levels(spans(5, 5, 5, 5), SpreadMode.PAUSES));
    assertArrayEquals(none, levels(spans(5, 5, 5, 5), SpreadMode.ACTIVITY));
  }

  @Test
  void pausesShadeTheLog2OfTheRatioToTheAverageRow() {
    // 100 rows averaging 1 second: 20 rows of 0, then ratios 0.5 to 32.
    long[] seconds = new long[100];
    seconds[0] = 2;
    seconds[1] = 4;
    seconds[2] = 8;
    seconds[3] = 16;
    seconds[4] = 32;
    seconds[5] = 1;
    for (int i = 6; i < 43; i++) {
      seconds[i] = 1;
    }
    byte[] levels = levels(spans(seconds), SpreadMode.PAUSES);
    assertEquals(1, levels[0]);
    assertEquals(2, levels[1]);
    assertEquals(3, levels[2]);
    assertEquals(4, levels[3]);
    assertEquals(TimeSpread.MAX_LEVEL, levels[4]);
    assertEquals(0, levels[5]);
    assertEquals(0, levels[50]);
  }

  @Test
  void aRatioJustUnderTwoIsNotShaded() {
    // Average 10 seconds: 19 is under twice the average, 20 is not.
    assertEquals(0, levels(spans(19, 1), SpreadMode.PAUSES)[0]);
    assertEquals(1, levels(spans(20, 0), SpreadMode.PAUSES)[0]);
  }

  @Test
  void pausesClampAtARatioOfSixteen() {
    long[] seconds = new long[1000];
    seconds[10] = 1000;
    assertEquals(TimeSpread.MAX_LEVEL, levels(spans(seconds), SpreadMode.PAUSES)[10]);
  }

  @Test
  void activityShadesTheInverseRatio() {
    // Whole span: 400 lines in 16 seconds, so a row of 100 lines in 1 second is 4 times the rate.
    byte[] levels = levels(spans(1, 2, 4, 9), SpreadMode.ACTIVITY);
    assertEquals(2, levels[0]);
    assertEquals(1, levels[1]);
    assertEquals(0, levels[2]);
    assertEquals(0, levels[3]);
  }

  @Test
  void activityCountsLinesPerRowWhenRowsDiffer() {
    long[] lines = {0, 1, 101};
    byte[] levels = TimeSpread.of(spans(5, 5), lines, SpreadMode.ACTIVITY).levels();
    assertEquals(0, levels[0]);
    assertEquals(0, levels[1]);
    lines = new long[] {0, 300, 301, 302, 303};
    levels = TimeSpread.of(spans(1, 1, 1, 1), lines, SpreadMode.ACTIVITY).levels();
    assertEquals(1, levels[0]);
    assertEquals(0, levels[1]);
  }

  @Test
  void aRowWithLinesAndNoElapsedTimeTakesTheHighestActivity() {
    byte[] levels = levels(spans(0, 8, 8), SpreadMode.ACTIVITY);
    assertEquals(TimeSpread.MAX_LEVEL, levels[0]);
    assertEquals(0, levels[1]);
  }

  @Test
  void equalStampsEverywhereShadeNothing() {
    assertArrayEquals(new byte[3], levels(spans(0, 0, 0), SpreadMode.ACTIVITY));
    assertArrayEquals(new byte[3], levels(spans(0, 0, 0), SpreadMode.PAUSES));
  }

  @Test
  void aDescentRowIsDisorderInBothModesAndStaysOutOfTheAverage() {
    TimeSamples samples = TimeSamples.of(0, 10 * SECOND, 20 * SECOND, 5 * SECOND, 15 * SECOND);
    byte[] pauses = levels(samples, SpreadMode.PAUSES);
    assertEquals(TimeSpread.DISORDER, pauses[2]);
    assertArrayEquals(new byte[] {0, 0, TimeSpread.DISORDER, 0}, pauses);
    assertArrayEquals(pauses, levels(samples, SpreadMode.ACTIVITY));
  }

  @Test
  void aRowMissingAStampStaysUnshadedAndOutOfTheAverage() {
    TimeSamples samples = TimeSamples.of(0, SECOND, NONE, 2 * SECOND, 3 * SECOND, 13 * SECOND);
    byte[] levels = levels(samples, SpreadMode.PAUSES);
    assertEquals(0, levels[1]);
    assertEquals(0, levels[2]);
    // Rows of 1, 1 and 10 seconds: an average of 4.
    assertEquals(1, levels[4]);
  }

  @Test
  void rowsWithoutLinesStayOutOfTheAverage() {
    long[] lines = {0, 0, 0, 1, 2};
    TimeSamples samples = TimeSamples.of(0, 0, 0, SECOND, 11 * SECOND);
    byte[] levels = TimeSpread.of(samples, lines, SpreadMode.PAUSES).levels();
    assertEquals(0, levels[0]);
    assertEquals(0, levels[1]);
    assertEquals(0, levels[2]);
    assertEquals(0, levels[3]);
    lines = new long[] {0, 0, 0, 1, 2, 3};
    samples = TimeSamples.of(0, 0, 0, SECOND, 2 * SECOND, 22 * SECOND);
    levels = TimeSpread.of(samples, lines, SpreadMode.PAUSES).levels();
    // Three rows averaging 22 / 3 seconds; five would average 22 / 5, a ratio of 4.5.
    assertEquals(1, levels[4]);
  }

  @Test
  void costIsOneBytePerRowPlusOverhead() {
    TimeSpread spread = TimeSpread.of(spans(1, 2, 3), evenLines(4), SpreadMode.PAUSES);
    assertEquals(3, spread.height());
    assertEquals(TimeSpread.OVERHEAD + 3, spread.retainedBytes());
    assertEquals(SpreadMode.PAUSES, spread.mode());
  }

  @Test
  void refusesOffAndMismatchedLines() {
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            TimeSpread.of(spans(1), evenLines(2), SpreadMode.OFF);
          }
        });
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            TimeSpread.of(spans(1), evenLines(3), SpreadMode.PAUSES);
          }
        });
  }

  @Test
  void modeIdsRoundTrip() {
    for (SpreadMode mode : SpreadMode.values()) {
      assertEquals(mode, SpreadMode.byId(mode.id()));
    }
    assertEquals(null, SpreadMode.byId("sideways"));
  }
}
