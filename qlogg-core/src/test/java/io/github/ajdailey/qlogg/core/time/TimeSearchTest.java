package io.github.ajdailey.qlogg.core.time;

import static io.github.ajdailey.qlogg.core.time.TimeFixture.ASCII;
import static io.github.ajdailey.qlogg.core.time.TimeFixture.nanosAt;
import static io.github.ajdailey.qlogg.core.time.TimeFixture.reader;
import static io.github.ajdailey.qlogg.core.time.TimeFixture.stampAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.MemoryByteSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class TimeSearchTest {
  private static final ScanProgress NO_PROGRESS =
      new ScanProgress() {
        @Override
        public void scanned(long position, long end) {}
      };

  private final CancellationToken token = new CancellationToken();

  @Test
  void bisectionMatchesTheLinearScanForEveryTarget() throws Exception {
    Random random = new Random(7);
    for (int round = 0; round < 3; round++) {
      Log log = new Log();
      log.text.append("preamble without a time\n");
      long millis = 0;
      for (int i = 0; i < 400; i++) {
        millis += random.nextInt(4);
        log.entry(millis, random.nextInt(4));
      }
      byte[] bytes = log.bytes();
      TimeReader reader = reader(new MemoryByteSource(bytes, 0), ASCII);
      for (long target = -2; target <= millis + 2; target++) {
        long nanos = nanosAt(target);
        Stamp expected = log.oracle(nanos);
        assertEquals(
            expected, TimeSearch.locateLinear(reader, bytes.length, nanos, token, NO_PROGRESS));
        assertEquals(
            expected, TimeSearch.locate(reader, bytes.length, nanos, token), "target " + target);
      }
    }
  }

  @Test
  void equalStampsReturnTheFirstAndTheEndsClamp() throws Exception {
    Log log = new Log();
    log.entry(0, 1);
    log.entry(5, 0);
    log.entry(5, 2);
    log.entry(5, 0);
    log.entry(9, 0);
    byte[] bytes = log.bytes();
    TimeReader reader = reader(new MemoryByteSource(bytes, 0), ASCII);
    assertEquals(log.stamps.get(1), TimeSearch.locate(reader, bytes.length, nanosAt(5), token));
    assertEquals(log.stamps.get(3), TimeSearch.locate(reader, bytes.length, nanosAt(7), token));
    assertEquals(log.stamps.get(0), TimeSearch.locate(reader, bytes.length, nanosAt(-1000), token));
    assertEquals(log.stamps.get(4), TimeSearch.locate(reader, bytes.length, nanosAt(1000), token));
    assertNull(TimeSearch.locate(reader(source("no time\n"), ASCII), 8, nanosAt(0), token));
    assertNull(
        TimeSearch.locateLinear(reader(source("no time\n"), ASCII), 8, 0, token, NO_PROGRESS));
  }

  /** Offsets past 2^31 through a 3 GB source that stores nothing. */
  @Test
  void bisectionOverThreeGigabytes() throws Exception {
    long size = 3_000_000_000L / GeneratedSource.WIDTH * GeneratedSource.WIDTH;
    TimeReader reader =
        new TimeReader(
            new GeneratedSource(size),
            new BlockCache(64 * 1024, 1 << 20),
            ASCII,
            BuiltInFormat.ISO.parser(TimeFixture.UTC));
    long[] lines = {
      0, (1L << 31) / GeneratedSource.WIDTH + 1, (1L << 31) / GeneratedSource.WIDTH + 2, 70_000_000
    };
    for (long line : lines) {
      long expected = GeneratedSource.stamped(line) ? line : line - 1;
      long millis = line * GeneratedSource.STEP_MILLIS;
      Stamp exact = TimeSearch.locate(reader, size, nanosAt(millis), token);
      assertEquals(expected * GeneratedSource.WIDTH, exact.offset(), "line " + line);
      Stamp between = TimeSearch.locate(reader, size, nanosAt(millis + 5), token);
      assertEquals(expected * GeneratedSource.WIDTH, between.offset(), "line " + line + " + 5 ms");
    }
    long lastLine = size / GeneratedSource.WIDTH - 1;
    long lastStamped = GeneratedSource.stamped(lastLine) ? lastLine : lastLine - 1;
    assertEquals(
        lastStamped * GeneratedSource.WIDTH,
        TimeSearch.locate(reader, size, Long.MAX_VALUE, token).offset());
    assertTrue(lastStamped * GeneratedSource.WIDTH > 1L << 31);
  }

  @Test
  void concatenatedDaysFailTheOrderCheckAndTheLinearScanMatchesTheOracle() throws Exception {
    Log log = new Log();
    long day = 86_400_000L;
    for (int i = 0; i < 2000; i++) {
      log.entry(day + i * 1000L, i % 3);
    }
    for (int i = 0; i < 2000; i++) {
      log.entry(i * 1000L, i % 3);
    }
    byte[] bytes = log.bytes();
    MemoryByteSource source = new MemoryByteSource(bytes, 0);
    LineIndex index = TimeFixture.index(source, ASCII, MemoryBudget.of(64L << 20));
    TimeReader reader = reader(source, ASCII);
    TimeSamples samples = TimeSamples.sample(reader, index, index.lineCount(), 256, token);
    assertEquals(1, samples.descents());
    for (long target : new long[] {0, 1500 * 1000L, day + 1500 * 1000L, 2 * day}) {
      long nanos = nanosAt(target);
      assertEquals(
          log.oracle(nanos),
          TimeSearch.locateLinear(reader, bytes.length, nanos, token, NO_PROGRESS),
          "target " + target);
    }
  }

  @Test
  void jitterOfFiveMillisecondsLandsWithinTheWindow() throws Exception {
    Random random = new Random(11);
    Log log = new Log();
    for (int i = 0; i < 5000; i++) {
      log.entry(1000 + i + random.nextInt(11) - 5, 0);
    }
    byte[] bytes = log.bytes();
    TimeReader reader = reader(new MemoryByteSource(bytes, 0), ASCII);
    for (int i = 0; i < 500; i++) {
      long target = 1000 + random.nextInt(5000);
      Stamp found = TimeSearch.locate(reader, bytes.length, nanosAt(target), token);
      long distance = Math.abs(found.nanos() - nanosAt(target));
      assertTrue(distance <= 11_000_000L, "target " + target + " landed " + distance + " ns away");
    }
  }

  @Test
  void theLinearScanReportsProgressAndStopsOnCancel() throws Exception {
    final List<Long> positions = new ArrayList<Long>();
    StringBuilder text = new StringBuilder();
    while (text.length() < 9 * (1 << 20)) {
      text.append("mock entry without a time\n");
    }
    text.append(stampAt(0)).append('\n');
    final byte[] bytes = text.toString().getBytes(StandardCharsets.US_ASCII);
    final TimeReader reader = reader(new MemoryByteSource(bytes, 0), ASCII);
    Stamp found =
        TimeSearch.locateLinear(
            reader,
            bytes.length,
            nanosAt(0),
            token,
            new ScanProgress() {
              @Override
              public void scanned(long position, long end) {
                positions.add(position);
              }
            });
    assertEquals(nanosAt(0), found.nanos());
    assertEquals(2, positions.size(), positions.toString());
    final CancellationToken canceled = new CancellationToken();
    canceled.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            TimeSearch.locateLinear(reader, bytes.length, 0, canceled, NO_PROGRESS);
          }
        });
  }

  private static MemoryByteSource source(String text) {
    return new MemoryByteSource(text.getBytes(StandardCharsets.US_ASCII), 0);
  }

  /** An ASCII log built entry by entry, with the stamps it holds in file order. */
  private static final class Log {
    final StringBuilder text = new StringBuilder();
    final List<Stamp> stamps = new ArrayList<Stamp>();

    /** Appends a stamped line at {@code BASE + millis} and its continuation lines. */
    void entry(long millis, int continuations) {
      stamps.add(new Stamp(text.length(), nanosAt(millis), 3));
      text.append(stampAt(millis)).append(" INFO mock\n");
      for (int i = 0; i < continuations; i++) {
        text.append("\tat mock.Frame.run\n");
      }
    }

    byte[] bytes() {
      return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** The go-to-time rule applied in file order. */
    Stamp oracle(long target) {
      for (int i = 0; i < stamps.size(); i++) {
        if (stamps.get(i).nanos() >= target) {
          return stamps.get(i).nanos() == target || i == 0 ? stamps.get(i) : stamps.get(i - 1);
        }
      }
      return stamps.isEmpty() ? null : stamps.get(stamps.size() - 1);
    }
  }
}
