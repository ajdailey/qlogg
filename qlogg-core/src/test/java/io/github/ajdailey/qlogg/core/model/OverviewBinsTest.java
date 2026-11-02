package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import java.math.BigInteger;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class OverviewBinsTest {
  private static long exact(int y, long lineCount, int height) {
    return BigInteger.valueOf(y)
        .multiply(BigInteger.valueOf(lineCount))
        .divide(BigInteger.valueOf(height))
        .longValueExact();
  }

  @Test
  void lineAtIsExactPastTheIntRange() {
    long[] counts = {
      1, 7, 600, 1L << 31, (1L << 31) + 17, 3_000_000_123L, 1L << 52, Long.MAX_VALUE
    };
    int[] heights = {1, 3, 600, 4096};
    for (long count : counts) {
      for (int height : heights) {
        assertEquals(0, OverviewBins.lineAt(0, count, height));
        assertEquals(count, OverviewBins.lineAt(height, count, height));
        for (int y = 0; y <= height; y += Math.max(1, height / 7)) {
          assertEquals(exact(y, count, height), OverviewBins.lineAt(y, count, height));
        }
      }
    }
  }

  @Test
  void rowOfInvertsLineAt() {
    long count = (1L << 31) + 12_345;
    int height = 700;
    for (int y = 0; y < height; y++) {
      long line = OverviewBins.lineAt(y, count, height);
      assertEquals(y, OverviewBins.rowOf(line, count, height));
    }
    assertEquals(height - 1, OverviewBins.rowOf(count - 1, count, height));
    assertEquals(0, OverviewBins.rowOf(-5, count, height));
    assertEquals(3, OverviewBins.rowOf(3, 10, 600) / 60);
  }

  @Test
  void binsCountMatchesAndMarksPerPixel() {
    MutableLineSet matches = new MutableLineSet();
    MutableLineSet marks = new MutableLineSet();
    long count = 1000;
    for (long line = 0; line < 100; line++) {
      matches.add(line);
    }
    matches.add(999);
    marks.add(500);
    marks.add(501);
    OverviewBins bins = OverviewBins.compute(matches, marks, count, 10, new CancellationToken());
    assertEquals(10, bins.height());
    assertEquals(100, bins.matches()[0]);
    assertEquals(1, bins.matches()[9]);
    assertEquals(2, bins.marks()[5]);
    assertEquals(0, bins.marks()[4]);
    assertEquals(OverviewBins.OVERHEAD + 80, bins.retainedBytes());
  }

  @Test
  void fewerLinesThanPixels() {
    MutableLineSet matches = new MutableLineSet();
    matches.add(2);
    OverviewBins bins = OverviewBins.compute(matches, null, 4, 100, new CancellationToken());
    int total = 0;
    for (int value : bins.matches()) {
      total += value;
    }
    assertEquals(1, total);
    assertEquals(1, bins.matches()[OverviewBins.rowOf(2, 4, 100)]);
  }

  @Test
  void binsOverMoreThanTwoBillionLines() {
    MutableLineSet matches = new MutableLineSet();
    long count = 5_000_000_000L;
    matches.add(4_999_999_999L);
    matches.add(2_500_000_000L);
    OverviewBins bins = OverviewBins.compute(matches, null, count, 1000, new CancellationToken());
    assertEquals(1, bins.matches()[999]);
    assertEquals(1, bins.matches()[500]);
    assertTrue(OverviewBins.compute(matches, null, 0, 50, new CancellationToken()).height() == 0);
  }

  @Test
  void computeStopsOnCancel() {
    final CancellationToken token = new CancellationToken();
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            OverviewBins.compute(new MutableLineSet(), null, 10, 10, token);
          }
        });
  }
}
