package io.github.ajdailey.qlogg.core.time;

import static io.github.ajdailey.qlogg.core.time.TimeFixture.ASCII;
import static io.github.ajdailey.qlogg.core.time.TimeFixture.nanosAt;
import static io.github.ajdailey.qlogg.core.time.TimeFixture.reader;
import static io.github.ajdailey.qlogg.core.time.TimeFixture.stampAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.FileIdentity;
import io.github.ajdailey.qlogg.core.io.MemoryByteSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
        assertEquals(expected, located(reader, bytes.length, nanos), "target " + target);
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
    assertEquals(log.stamps.get(1), located(reader, bytes.length, nanosAt(5)));
    assertEquals(log.stamps.get(3), located(reader, bytes.length, nanosAt(7)));
    assertEquals(log.stamps.get(0), located(reader, bytes.length, nanosAt(-1000)));
    assertEquals(log.stamps.get(4), located(reader, bytes.length, nanosAt(1000)));
    assertNull(located(reader(source("no time\n"), ASCII), 8, nanosAt(0)));
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
      Stamp exact = located(reader, size, nanosAt(millis));
      assertEquals(expected * GeneratedSource.WIDTH, exact.offset(), "line " + line);
      Stamp between = located(reader, size, nanosAt(millis + 5));
      assertEquals(expected * GeneratedSource.WIDTH, between.offset(), "line " + line + " + 5 ms");
    }
    long lastLine = size / GeneratedSource.WIDTH - 1;
    long lastStamped = GeneratedSource.stamped(lastLine) ? lastLine : lastLine - 1;
    assertEquals(
        lastStamped * GeneratedSource.WIDTH, located(reader, size, Long.MAX_VALUE).offset());
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
      Stamp found = located(reader, bytes.length, nanosAt(target));
      long distance = Math.abs(found.nanos() - nanosAt(target));
      assertTrue(distance <= 11_000_000L, "target " + target + " landed " + distance + " ns away");
    }
  }

  @Test
  void jitterWithinTheToleranceStaysConsistent() throws Exception {
    Random random = new Random(13);
    Log log = new Log();
    for (int i = 0; i < 3000; i++) {
      log.entry(1000 + i * 100L + random.nextInt(901) - 450, 0);
    }
    byte[] bytes = log.bytes();
    TimeReader reader = reader(new MemoryByteSource(bytes, 0), ASCII);
    for (int i = 0; i < 500; i++) {
      long target = random.nextInt(300_000);
      Stamp found = located(reader, bytes.length, nanosAt(target));
      assertTrue(Math.abs(found.nanos() - nanosAt(target)) <= 1_000_000_000L, "target " + target);
    }
  }

  /** 1,000 entries a second apart, each 34 bytes, so the first probes start at known lines. */
  private static Log secondsWithBlock(int first, int last, int blockEntry) {
    Log log = new Log();
    for (int i = 0; i < 1000; i++) {
      log.entry((i >= first && i <= last ? blockEntry + i - first : i) * 1000L, 0);
    }
    return log;
  }

  private void assertInconsistent(Log log, int targetEntry) throws Exception {
    byte[] bytes = log.bytes();
    TimeReader reader = reader(new MemoryByteSource(bytes, 0), ASCII);
    long nanos = nanosAt(targetEntry * 1000L);
    TimeSearch.Bisection bisection = TimeSearch.locate(reader, bytes.length, nanos, token);
    assertFalse(bisection.consistent());
    assertNull(bisection.stamp());
    assertEquals(
        log.oracle(nanos),
        TimeSearch.locateLinear(reader, bytes.length, nanos, token, NO_PROGRESS));
  }

  @Test
  void aBlockOutsideTheFilesTimeAtTheFirstProbeIsInconsistent() throws Exception {
    assertInconsistent(secondsWithBlock(498, 502, 86_400), 300);
    assertInconsistent(secondsWithBlock(498, 502, -86_400), 300);
    assertInconsistent(secondsWithBlock(498, 502, -86_400), 700);
  }

  @Test
  void aProbeOutsideTheNarrowedBracketIsInconsistent() throws Exception {
    // The first probe, line 500, is over target 100; the second, line 250, is over the first.
    assertInconsistent(secondsWithBlock(248, 252, 800), 100);
    // The first probe is under target 900; the second, line 750, is under the first.
    assertInconsistent(secondsWithBlock(748, 752, 200), 900);
  }

  /** The gap the order safety leaves: a block within the file's own time that fits its bracket. */
  @Test
  void aBlockThatFitsItsBracketLandsThereAndStaysConsistent() throws Exception {
    Log log = secondsWithBlock(498, 501, 100);
    byte[] bytes = log.bytes();
    TimeReader reader = reader(new MemoryByteSource(bytes, 0), ASCII);
    long nanos = nanosAt(300_000);
    assertEquals(log.stamps.get(501), located(reader, bytes.length, nanos));
    assertEquals(
        log.stamps.get(300),
        TimeSearch.locateLinear(reader, bytes.length, nanos, token, NO_PROGRESS));
  }

  @Test
  void longRunsOfEqualStampsReturnTheFirstOfTheRun() throws Exception {
    Log log = new Log();
    for (int i = 0; i < 3000; i++) {
      log.entry(i / 1000 * 5000L, i % 7 == 0 ? 1 : 0);
    }
    byte[] bytes = log.bytes();
    TimeReader reader = reader(new MemoryByteSource(bytes, 0), ASCII);
    for (long target : new long[] {-1, 0, 1, 4999, 5000, 5001, 10_000, 10_001}) {
      long nanos = nanosAt(target);
      assertEquals(log.oracle(nanos), located(reader, bytes.length, nanos), "target " + target);
    }
  }

  @Test
  void aStretchWithoutStampsWithinTheBoundStaysExact() throws Exception {
    Log log = new Log();
    for (int i = 0; i < 200; i++) {
      log.entry(i * 10L, i == 100 ? 5000 : 0);
    }
    byte[] bytes = log.bytes();
    TimeReader reader = reader(new MemoryByteSource(bytes, 0), ASCII);
    for (long target = -2; target <= 2000; target++) {
      long nanos = nanosAt(target);
      assertEquals(log.oracle(nanos), located(reader, bytes.length, nanos), "target " + target);
    }
  }

  @Test
  void aProbeStoppedByTheBoundBetweenStampsIsInconsistent() throws Exception {
    Log log = new Log();
    for (int i = 0; i < 200; i++) {
      log.entry(i * 1000L, i == 99 ? 25_000 : 0);
    }
    byte[] bytes = log.bytes();
    TimeReader reader = reader(new MemoryByteSource(bytes, 0), ASCII);
    long nanos = nanosAt(150_000);
    assertFalse(TimeSearch.locate(reader, bytes.length, nanos, token).consistent());
    assertEquals(
        log.stamps.get(150),
        TimeSearch.locateLinear(reader, bytes.length, nanos, token, NO_PROGRESS));
  }

  @Test
  void noStampFoundInARegionThatHasOneIsInconsistent() throws Exception {
    Log log = new Log();
    for (int i = 0; i < 25_000; i++) {
      log.text.append("preamble without a time\n");
    }
    for (int i = 0; i < 50; i++) {
      log.entry(i * 1000L, 0);
    }
    byte[] bytes = log.bytes();
    TimeReader reader = reader(new MemoryByteSource(bytes, 0), ASCII);
    long nanos = nanosAt(10_000);
    assertFalse(TimeSearch.locate(reader, bytes.length, nanos, token).consistent());
    assertEquals(
        log.stamps.get(10),
        TimeSearch.locateLinear(reader, bytes.length, nanos, token, NO_PROGRESS));
  }

  /** Probes stopped by the bound with no stamp read stay consistent, so no scan follows. */
  @Test
  void aRegionWithoutStampsPastTheBoundReportsNone() throws Exception {
    Log log = new Log();
    for (int i = 0; i < 200_000; i++) {
      log.text.append("preamble without a time\n");
    }
    byte[] bytes = log.bytes();
    TimeReader reader = reader(new MemoryByteSource(bytes, 0), ASCII);
    assertNull(located(reader, bytes.length, nanosAt(0)));
  }

  /** The snapshot's end bounds every read: what grew past it, in any order, changes nothing. */
  @Test
  void bytesPastTheEndAreNotSearched() throws Exception {
    Log log = new Log();
    for (int i = 0; i < 300; i++) {
      log.entry(i * 1000L, i % 3);
    }
    int end = log.text.length() + 5;
    for (int i = 0; i < 300; i++) {
      log.entry(-86_400_000L + i * 1000L, 0);
    }
    byte[] bytes = log.bytes();
    TimeReader reader = reader(new MemoryByteSource(bytes, 0), ASCII);
    assertEquals(log.stamps.get(299), located(reader, end, Long.MAX_VALUE));
    assertEquals(log.stamps.get(100), located(reader, end, nanosAt(100_000)));
    assertEquals(log.stamps.get(0), located(reader, end, nanosAt(-1)));
  }

  @Test
  void aFileCutDuringTheSearchEndsBothSearches() throws Exception {
    Log log = new Log();
    for (int i = 0; i < 2000; i++) {
      log.entry(i * 1000L, 0);
    }
    byte[] bytes = log.bytes();
    long nanos = nanosAt(1_990_000);
    Probed source = new Probed(bytes);
    source.cutAfter = 2;
    TimeSearch.locate(reader(source, ASCII), bytes.length, nanos, token);
    // One read fills the scan's 64 KiB buffer; the target lies past it.
    source = new Probed(bytes);
    source.cutAfter = 1;
    Stamp found =
        TimeSearch.locateLinear(reader(source, ASCII), bytes.length, nanos, token, NO_PROGRESS);
    assertTrue(found.nanos() < nanos);
  }

  @Test
  void aCancelStopsABisectionBetweenProbes() throws Exception {
    Log log = new Log();
    for (int i = 0; i < 2000; i++) {
      log.entry(i * 1000L, 0);
    }
    final byte[] bytes = log.bytes();
    final CancellationToken canceled = new CancellationToken();
    Probed source = new Probed(bytes);
    source.cancelAfter = 3;
    source.canceled = canceled;
    final TimeReader reader = reader(source, ASCII);
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            TimeSearch.locate(reader, bytes.length, nanosAt(1000), canceled);
          }
        });
  }

  /** The bracket holds past 2^31: the second probe falls in a block ten days early. */
  @Test
  void aProbeOutsideItsBracketOverThreeGigabytes() throws Exception {
    long size = 3_000_000_000L / GeneratedSource.WIDTH * GeneratedSource.WIDTH;
    long middle = size * 3 / 4 / GeneratedSource.WIDTH;
    assertTrue(middle * GeneratedSource.WIDTH > 1L << 31);
    TimeReader reader =
        new TimeReader(
            new GeneratedSource(size, middle - 1000, middle + 1000, -10 * 86_400_000L),
            new BlockCache(64 * 1024, 1 << 20),
            ASCII,
            BuiltInFormat.ISO.parser(TimeFixture.UTC));
    long target = nanosAt(60_000_000L * GeneratedSource.STEP_MILLIS);
    assertFalse(TimeSearch.locate(reader, size, target, token).consistent());
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

  /** A bisection that must stay consistent. */
  private Stamp located(TimeReader reader, long end, long target) throws IOException {
    TimeSearch.Bisection bisection = TimeSearch.locate(reader, end, target, token);
    assertTrue(bisection.consistent(), "inconsistent at " + target);
    return bisection.stamp();
  }

  private static MemoryByteSource source(String text) {
    return new MemoryByteSource(text.getBytes(StandardCharsets.US_ASCII), 0);
  }

  /** Bytes that end, or a token that cancels, after a number of reads. */
  private static final class Probed implements ByteSource {
    private final MemoryByteSource bytes;
    private int reads;
    int cutAfter = Integer.MAX_VALUE;
    int cancelAfter = Integer.MAX_VALUE;
    CancellationToken canceled;

    Probed(byte[] content) {
      bytes = new MemoryByteSource(content, 0);
    }

    @Override
    public Path path() {
      return null;
    }

    @Override
    public long size() throws IOException {
      return bytes.size();
    }

    @Override
    public int read(long position, ByteBuffer dst) throws IOException {
      reads++;
      if (reads > cancelAfter) {
        canceled.cancel();
      }
      return reads > cutAfter ? -1 : bytes.read(position, dst);
    }

    @Override
    public FileIdentity identity() {
      return bytes.identity();
    }

    @Override
    public void close() throws IOException {
      bytes.close();
    }
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
