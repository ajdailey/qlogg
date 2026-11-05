package io.github.ajdailey.qlogg.core.time;

import static io.github.ajdailey.qlogg.core.time.TimeFixture.ASCII;
import static io.github.ajdailey.qlogg.core.time.TimeFixture.nanosAt;
import static io.github.ajdailey.qlogg.core.time.TimeFixture.reader;
import static io.github.ajdailey.qlogg.core.time.TimeFixture.stampAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.index.LineStart;
import io.github.ajdailey.qlogg.core.io.MemoryByteSource;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TimeSamplesTest {
  private static final int LINES = 5000;
  private final CancellationToken token = new CancellationToken();

  @Test
  void fullIndexSamplesEvenlySpacedLines() throws Exception {
    MemoryByteSource source = source();
    LineIndex index = TimeFixture.index(source, ASCII, MemoryBudget.of(64L << 20));
    assertFalse(index.sparse());
    TimeSamples samples = TimeSamples.sample(reader(source, ASCII), index, LINES, 11, token);
    assertEquals(11, samples.count());
    assertEquals(0, samples.descents());
    for (int i = 0; i < 11; i++) {
      assertEquals(nanosAt(i * (LINES - 1L) / 10), samples.nanos(i), "sample " + i);
    }
    assertEquals(new LineStart(17, index.lineStart(17)), index.nearestStart(17));
  }

  @Test
  void sparseIndexSnapsSamplesToCheckpoints() throws Exception {
    MemoryByteSource source = source();
    LineIndex index = TimeFixture.index(source, ASCII, MemoryBudget.of(150_000));
    assertTrue(index.sparse());
    TimeReader reader = reader(source, ASCII);
    TimeSamples samples = TimeSamples.sample(reader, index, LINES, 9, token);
    for (int i = 0; i < 9; i++) {
      long line = i * (LINES - 1L) / 8;
      LineStart start = index.nearestStart(line);
      assertTrue(start.line() <= line && line - start.line() < 1024, "line " + line);
      assertEquals(index.lineStart(start.line()), start.offset());
      assertEquals(nanosAt(start.line()), samples.nanos(i), "sample " + i);
    }
    byte[] bytes = (text() + stampAt(LINES)).getBytes(StandardCharsets.US_ASCII);
    MemoryByteSource partial = new MemoryByteSource(bytes, 0);
    LineIndex last = TimeFixture.index(partial, ASCII, MemoryBudget.of(150_000));
    assertEquals(
        new LineStart(LINES, bytes.length - stampAt(LINES).length()), last.nearestStart(LINES));
  }

  @Test
  void costAndEmptyRanges() throws Exception {
    MemoryByteSource source = source();
    LineIndex index = TimeFixture.index(source, ASCII, MemoryBudget.of(64L << 20));
    TimeSamples none = TimeSamples.sample(reader(source, ASCII), index, 0, 4, token);
    assertEquals(TimestampParser.NONE, none.nanos(3));
    assertEquals(8 * 4 + 64, none.retainedBytes());
    assertEquals(8L * 4000 + 64, TimeSamples.bytesFor(4000));
    TimeSamples one = TimeSamples.sample(reader(source, ASCII), index, LINES, 1, token);
    assertEquals(nanosAt(0), one.nanos(0));
  }

  /** Line {@code i} carries {@code BASE + i} ms. */
  private static String text() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < LINES; i++) {
      text.append(stampAt(i)).append(" INFO mock entry ").append(i).append('\n');
    }
    return text.toString();
  }

  private static MemoryByteSource source() {
    return new MemoryByteSource(text().getBytes(StandardCharsets.US_ASCII), 0);
  }
}
