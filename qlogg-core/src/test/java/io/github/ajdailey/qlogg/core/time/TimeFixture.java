package io.github.ajdailey.qlogg.core.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.GrowableLineIndex;
import io.github.ajdailey.qlogg.core.index.IndexListener;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

/** Shared values and builders for the {@code core.time} tests. */
final class TimeFixture {
  static final TimeContext UTC = new TimeContext(ZoneOffset.UTC, 2026, 3);
  static final TextEncoding ASCII = TextEncoding.of(StandardCharsets.US_ASCII, 0);
  static final LocalDateTime BASE = LocalDateTime.of(2026, 3, 1, 5, 0);
  static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

  private TimeFixture() {}

  /** Epoch nanoseconds of an ISO-8601 instant such as {@code 2026-03-01T05:00:00Z}. */
  static long nanos(String instant) {
    Instant parsed = Instant.parse(instant);
    return parsed.getEpochSecond() * 1_000_000_000L + parsed.getNano();
  }

  /** Epoch nanoseconds of {@link #BASE} plus milliseconds, in UTC. */
  static long nanosAt(long millis) {
    Instant instant = BASE.plus(Duration.ofMillis(millis)).toInstant(ZoneOffset.UTC);
    return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
  }

  /** {@link #BASE} plus milliseconds in {@code yyyy-MM-dd HH:mm:ss.SSS}. */
  static String stampAt(long millis) {
    return ISO.format(BASE.plus(Duration.ofMillis(millis)));
  }

  static BlockCache cache() {
    return new BlockCache(4096, 1 << 20);
  }

  static TimeReader reader(ByteSource source, TextEncoding encoding) {
    return new TimeReader(source, cache(), encoding, BuiltInFormat.ISO.parser(UTC));
  }

  /** Indexes a source to the end and returns the final snapshot. */
  static LineIndex index(ByteSource source, TextEncoding encoding, MemoryBudget budget)
      throws IOException, InterruptedException {
    final AtomicReference<OperationOutcome> outcome = new AtomicReference<OperationOutcome>();
    try (Workers workers =
            new Workers(
                2,
                new Thread.UncaughtExceptionHandler() {
                  @Override
                  public void uncaughtException(Thread thread, Throwable error) {
                    error.printStackTrace();
                  }
                });
        GrowableLineIndex index = new GrowableLineIndex(encoding, budget, source, cache())) {
      OperationHandle handle =
          new Indexer(workers, new IndexSettings(64 * 1024, 2, budget))
              .index(
                  source,
                  index,
                  index.resumeOffset(),
                  new IndexListener() {
                    @Override
                    public void progressed(IndexProgress progress) {}

                    @Override
                    public void finished(OperationOutcome result, IOException failure) {
                      outcome.set(result);
                    }
                  });
      assertTrue(handle.await(60_000));
      assertEquals(OperationOutcome.COMPLETED, outcome.get());
      return index.snapshot();
    }
  }
}
