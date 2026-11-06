package io.github.ajdailey.qlogg.bench;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
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
import io.github.ajdailey.qlogg.core.io.FileByteSource;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.time.FormatDetector;
import io.github.ajdailey.qlogg.core.time.ScanProgress;
import io.github.ajdailey.qlogg.core.time.Stamp;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.time.TimeReader;
import io.github.ajdailey.qlogg.core.time.TimeSamples;
import io.github.ajdailey.qlogg.core.time.TimeSearch;
import io.github.ajdailey.qlogg.core.time.TimestampParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Timestamp parsing and lookups over a generated {@code timed} log ({@link
 * LogGenerator.Profile#TIMED}): the ISO scanner per line, format detection on the 64 KiB head, one
 * go to time by bisection, a full sequential scan on one thread, and 4,000 samples. The file is
 * written once to {@code -Dqlogg.bench.dir} and reused; the cache is warm.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(value = 1, jvmArgsPrepend = Harness.LAUNCHER_HEAP)
public class TimeProbe {
  private static final int PARSE_LINES = 1 << 16;
  private static final TimeContext UTC = new TimeContext(ZoneOffset.UTC, 2026, 11);
  private static final ScanProgress NO_PROGRESS =
      new ScanProgress() {
        @Override
        public void scanned(long position, long end) {}
      };

  /** File size in MiB. */
  @Param({"1024"})
  public int fileMib;

  private final CancellationToken token = new CancellationToken();
  private final SplittableRandom random = new SplittableRandom(5);
  private final TimestampParser parser = BuiltInFormat.ISO.parser(UTC);
  private FileByteSource source;
  private LineIndex index;
  private TimeReader reader;
  private ByteBuffer head;
  private String[] lines;
  private long first;
  private long last;

  /**
   * Writes the file when it does not exist, then indexes it and reads the first and last stamps.
   *
   * @throws IOException when the directory is missing, the write fails or indexing fails
   * @throws InterruptedException when interrupted while indexing
   */
  @Setup(Level.Trial)
  public void prepare() throws IOException, InterruptedException {
    String dir = System.getProperty("qlogg.bench.dir");
    if (dir == null) {
      throw new IOException("Set -Dqlogg.bench.dir to a scratch directory");
    }
    Path file = Path.of(dir).resolve("time-probe-" + fileMib + "m.log");
    if (!Files.exists(file)) {
      new LogGenerator(LogGenerator.Profile.TIMED, 1).write(file, (long) fileMib << 20);
    }
    source = FileByteSource.open(file);
    MemoryBudget budget = MemoryBudget.fromRuntime();
    BlockCache cache = new BlockCache(64 * 1024, budget.limit(MemoryShare.BLOCK_CACHE));
    TextEncoding encoding = TextEncoding.of(StandardCharsets.UTF_8, 0);
    index = index(source, encoding, budget, cache);
    reader = new TimeReader(source, cache, encoding, BuiltInFormat.ISO.parser(UTC));
    head = ByteBuffer.allocate(64 * 1024);
    source.read(0, head);
    head.flip();
    lines = new String(head.array(), 0, head.limit(), StandardCharsets.US_ASCII).split("\n");
    first = reader.next(0, index.indexedBytes(), token).nanos();
    last = TimeSearch.locate(reader, index.indexedBytes(), Long.MAX_VALUE, token).stamp().nanos();
  }

  /**
   * Closes the file.
   *
   * @throws IOException on a close error
   */
  @TearDown(Level.Trial)
  public void tearDown() throws IOException {
    source.close();
  }

  /**
   * Parses the head's lines, stack traces included, repeatedly.
   *
   * @return a checksum
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  @OperationsPerInvocation(PARSE_LINES)
  public long parse() {
    long sum = 0;
    for (int i = 0; i < PARSE_LINES; i++) {
      sum += parser.parse(lines[i % lines.length]);
    }
    return sum;
  }

  /**
   * Detects the format of the 64 KiB head.
   *
   * @return the format
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public BuiltInFormat detect() {
    return FormatDetector.detect(head, TextEncoding.of(StandardCharsets.UTF_8, 0), UTC);
  }

  /**
   * One go to time by bisection, to a random time between the first and last stamps.
   *
   * @return the stamp
   * @throws IOException on a read error
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public Stamp locate() throws IOException {
    long target = first + (long) (random.nextDouble() * (last - first));
    return TimeSearch.locate(reader, index.indexedBytes(), target, token).stamp();
  }

  /**
   * A sequential scan of the whole file on one thread.
   *
   * @return the last stamp
   * @throws IOException on a read error
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public Stamp linear() throws IOException {
    return TimeSearch.locateLinear(
        reader, index.indexedBytes(), Long.MAX_VALUE, token, NO_PROGRESS);
  }

  /**
   * 4,000 evenly spaced samples.
   *
   * @return descents
   * @throws IOException on a read error
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public int samples() throws IOException {
    return TimeSamples.sample(reader, index, index.lineCount(), 4000, token).descents();
  }

  private static LineIndex index(
      FileByteSource source, TextEncoding encoding, MemoryBudget budget, BlockCache cache)
      throws IOException, InterruptedException {
    final AtomicReference<OperationOutcome> outcome = new AtomicReference<OperationOutcome>();
    try (Workers workers =
            new Workers(
                Runtime.getRuntime().availableProcessors(),
                new Thread.UncaughtExceptionHandler() {
                  @Override
                  public void uncaughtException(Thread thread, Throwable error) {
                    error.printStackTrace();
                  }
                });
        GrowableLineIndex lineIndex = new GrowableLineIndex(encoding, budget, source, cache)) {
      OperationHandle handle =
          new Indexer(workers, IndexSettings.defaults(workers.cpuThreads(), budget))
              .index(
                  source,
                  lineIndex,
                  0,
                  new IndexListener() {
                    @Override
                    public void progressed(IndexProgress progress) {}

                    @Override
                    public void finished(OperationOutcome result, IOException failure) {
                      outcome.set(result);
                    }
                  });
      if (!handle.await(TimeUnit.MINUTES.toMillis(10))
          || outcome.get() != OperationOutcome.COMPLETED) {
        throw new IOException("Indexing did not complete: " + outcome.get());
      }
      return lineIndex.snapshot();
    }
  }
}
