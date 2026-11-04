package io.github.ajdailey.qlogg.bench;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentListener;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.LineReader;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchExpression;
import io.github.ajdailey.qlogg.core.search.SearchLimit;
import io.github.ajdailey.qlogg.core.search.SearchListener;
import io.github.ajdailey.qlogg.core.search.SearchQuery;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import io.github.ajdailey.qlogg.core.watch.FileMonitor;
import io.github.ajdailey.qlogg.core.watch.MonitorSettings;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless benchmark harness over the same core services the application builds: a memory budget
 * from the runtime, a block cache of the block-cache share and every CPU. Prints one {@code
 * key=value} line per measurement on standard output.
 *
 * <pre>
 * generate PROFILE BYTES FILE [SEED]   write a LogGenerator file
 * run FILE [SEARCH...]                 open, index, search, random reads, end of file
 * tail FILE LINES_PER_SECOND SECONDS   append to a new file and measure write-to-index latency
 * </pre>
 *
 * <p>A {@code SEARCH} is {@code literal:TEXT}, {@code literal-i:TEXT} (case folded) or {@code
 * regex:PATTERN}. Without one, {@code run} searches {@link LogGenerator#RARE_TOKEN} and {@link
 * LogGenerator#COMMON_TOKEN} as literals and a regex.
 */
public final class Harness {
  private static final int READS = 10_000;
  private static final int SCREEN_LINES = 50;
  private static final int SCREEN_COLUMNS = 200;
  private static final long BOUND_MINUTES = 30;
  private static final String DEFAULT_REGEX = "worker-1[0-5]\\] #\\d+7 ";

  private final PrintStream out;
  private final Workers workers;
  private final MemoryBudget budget;
  private final DocumentServices services;
  private final long cpuStart = cpuNanos();

  private Harness(PrintStream out) {
    this.out = out;
    workers =
        new Workers(
            Runtime.getRuntime().availableProcessors(),
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                error.printStackTrace();
              }
            });
    budget = MemoryBudget.fromRuntime();
    long cacheBytes = budget.limit(MemoryShare.BLOCK_CACHE);
    budget.charge(MemoryShare.BLOCK_CACHE, cacheBytes);
    services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(workers.cpuThreads(), budget)),
            new SearchEngine(
                workers,
                StandardMatchers.INSTANCE,
                SearchSettings.defaults(workers.cpuThreads(), budget)),
            new BlockCache(64 * 1024, cacheBytes),
            budget,
            StandardCharsets.ISO_8859_1);
  }

  /**
   * Runs one command.
   *
   * @param args the command and its arguments
   * @throws Exception on any failure, which ends the run with a stack trace
   */
  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println(
          "usage: generate PROFILE BYTES FILE [SEED] | run FILE [SEARCH...]"
              + " | tail FILE LINES_PER_SECOND SECONDS");
      System.exit(2);
    }
    switch (args[0]) {
      case "generate" -> generate(args);
      case "run" -> {
        Harness harness = new Harness(System.out);
        try {
          harness.run(Path.of(args[1]), Arrays.copyOfRange(args, 2, args.length));
        } finally {
          harness.workers.close();
        }
      }
      case "tail" -> {
        Harness harness = new Harness(System.out);
        try {
          harness.tail(Path.of(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
        } finally {
          harness.workers.close();
        }
      }
      default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
    }
  }

  private static void generate(String[] args) throws IOException {
    LogGenerator.Profile profile = LogGenerator.Profile.of(args[1]);
    long bytes = Long.parseLong(args[2]);
    long seed = args.length > 4 ? Long.parseLong(args[4]) : 1;
    long start = System.nanoTime();
    long written = new LogGenerator(profile, seed).write(Path.of(args[3]), bytes);
    System.out.println("generate.bytes=" + written);
    System.out.println("generate.ms=" + millis(System.nanoTime() - start));
  }

  private void run(Path file, String[] searches) throws IOException, InterruptedException {
    out.println("file=" + file);
    out.println("file.bytes=" + Files.size(file));
    out.println("heap.max=" + Runtime.getRuntime().maxMemory());
    out.println("cpus=" + Runtime.getRuntime().availableProcessors());
    long start = System.nanoTime();
    LogDocument document = LogDocument.open(file, null, services);
    out.println("open.ms=" + millis(System.nanoTime() - start));
    out.println("encoding=" + document.encoding());
    try {
      index(document);
      List<MutableLineSet> kept = new ArrayList<MutableLineSet>();
      if (searches.length == 0) {
        searches =
            new String[] {
              "literal:" + LogGenerator.RARE_TOKEN,
              "literal:" + LogGenerator.COMMON_TOKEN,
              "regex:" + DEFAULT_REGEX
            };
      }
      for (String search : searches) {
        kept.add(search(document, search));
      }
      randomReads(document);
      endOfFile(document);
      heap("heap.afterGc");
      long results = 0;
      for (MutableLineSet set : kept) {
        results += set.size();
      }
      out.println("results.kept=" + results);
      shares();
    } finally {
      document.close();
    }
    out.println("cpu.ms=" + millis(cpuNanos() - cpuStart));
    out.println("rss.peak=" + rssPeak());
  }

  private void index(LogDocument document) throws IOException, InterruptedException {
    final CountDownLatch done = new CountDownLatch(1);
    final AtomicReference<OperationOutcome> outcome = new AtomicReference<OperationOutcome>();
    document.addListener(
        new DocumentListener() {
          @Override
          public void indexProgressed(IndexProgress progress) {
            // Only the end is timed.
          }

          @Override
          public void indexFinished(OperationOutcome result, IOException failure) {
            outcome.set(result);
            done.countDown();
          }

          @Override
          public void fileChanged(FileChange change) {}

          @Override
          public void closed() {}
        });
    long cpu = cpuNanos();
    long start = System.nanoTime();
    document.startIndexing();
    if (!done.await(BOUND_MINUTES, TimeUnit.MINUTES)) {
      throw new IOException("Indexing did not finish");
    }
    long elapsed = System.nanoTime() - start;
    LineIndex index = document.index();
    out.println("index.outcome=" + outcome.get());
    out.println("index.ms=" + millis(elapsed));
    out.println("index.cpu.ms=" + millis(cpuNanos() - cpu));
    out.println(
        "index.mbPerSecond=" + format(index.indexedBytes() / 1e6 / Math.max(1e-9, elapsed / 1e9)));
    out.println("index.lines=" + index.lineCount());
    out.println("index.sparse=" + index.sparse());
    long indexBytes = budget.reserved(MemoryShare.LINE_INDEX);
    out.println("index.bytes=" + indexBytes);
    out.println(
        "index.bytesPerLine=" + format((double) indexBytes / Math.max(1, index.lineCount())));
    heap("heap.afterIndex");
  }

  private MutableLineSet search(LogDocument document, String spec) throws InterruptedException {
    int colon = spec.indexOf(':');
    String kind = spec.substring(0, colon);
    String pattern = spec.substring(colon + 1);
    MatchMode mode = kind.equals("regex") ? MatchMode.REGEX : MatchMode.LITERAL;
    boolean caseSensitive = !kind.equals("literal-i");
    MutableLineSet results = new MutableLineSet(budget);
    final CountDownLatch done = new CountDownLatch(1);
    final AtomicReference<OperationOutcome> outcome = new AtomicReference<OperationOutcome>();
    final AtomicReference<SearchLimit> limit = new AtomicReference<SearchLimit>();
    SearchQuery query =
        new SearchQuery(
            new SearchExpression.Term(pattern, mode), caseSensitive, false, false, null);
    long cpu = cpuNanos();
    long start = System.nanoTime();
    OperationHandle handle =
        services
            .search()
            .search(
                document,
                query,
                results,
                new SearchListener() {
                  @Override
                  public void progressed(long searchedLines, long totalLines, long matchCount) {
                    // Only the end is timed.
                  }

                  @Override
                  public void finished(
                      OperationOutcome result,
                      SearchLimit reached,
                      long skipped,
                      Exception failure) {
                    outcome.set(result);
                    limit.set(reached);
                    done.countDown();
                  }
                });
    if (!done.await(BOUND_MINUTES, TimeUnit.MINUTES)) {
      handle.cancel();
      throw new IllegalStateException("Search did not finish: " + spec);
    }
    String key = "search[" + spec + "]";
    out.println(key + ".outcome=" + outcome.get() + " limit=" + limit.get());
    out.println(key + ".ms=" + millis(System.nanoTime() - start));
    out.println(key + ".cpu.ms=" + millis(cpuNanos() - cpu));
    out.println(key + ".matches=" + results.size());
    out.println(key + ".resultBytes=" + results.retainedBytes());
    return results;
  }

  private void randomReads(LogDocument document) throws IOException {
    LineIndex index = document.index();
    long lines = index.lineCount();
    LineReader reader = document.newReader();
    SplittableRandom random = new SplittableRandom(7);
    long[] nanos = new long[READS];
    long start = System.nanoTime();
    for (int i = 0; i < READS; i++) {
      long first = random.nextLong(Math.max(1, lines - SCREEN_LINES));
      long t = System.nanoTime();
      reader.read(index, first, SCREEN_LINES, 0, SCREEN_COLUMNS);
      nanos[i] = System.nanoTime() - t;
    }
    long total = System.nanoTime() - start;
    Arrays.sort(nanos);
    out.println("readLines.screens=" + READS + "x" + SCREEN_LINES);
    out.println("readLines.p50.us=" + format(nanos[READS / 2] / 1e3));
    out.println("readLines.p99.us=" + format(nanos[READS * 99 / 100] / 1e3));
    out.println("readLines.max.us=" + format(nanos[READS - 1] / 1e3));
    out.println("readLines.total.ms=" + millis(total));
  }

  private void endOfFile(LogDocument document) throws IOException {
    LineIndex index = document.index();
    long first = Math.max(0, index.lineCount() - SCREEN_LINES);
    long start = System.nanoTime();
    document.newReader().read(index, first, SCREEN_LINES, 0, SCREEN_COLUMNS);
    out.println("endOfFile.us=" + format((System.nanoTime() - start) / 1e3));
  }

  private void tail(Path file, int linesPerSecond, int seconds)
      throws IOException, InterruptedException {
    final int total = linesPerSecond * seconds;
    final long[] written = new long[total];
    final long[] seen = new long[total];
    LogGenerator generator = new LogGenerator(LogGenerator.Profile.ASCII_SHORT, 3);
    try (OutputStream log = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
      log.write(generator.nextLine());
    }
    LogDocument document = LogDocument.open(file, null, services);
    final LogDocument shown = document;
    final long[] covered = {0};
    final CountDownLatch first = new CountDownLatch(1);
    document.addListener(
        new DocumentListener() {
          @Override
          public void indexProgressed(IndexProgress progress) {
            record(shown, covered, seen, total);
          }

          @Override
          public void indexFinished(OperationOutcome outcome, IOException failure) {
            record(shown, covered, seen, total);
            first.countDown();
          }

          @Override
          public void fileChanged(FileChange change) {
            if (change != FileChange.APPENDED && change != FileChange.UNCHANGED) {
              out.println("tail.change=" + change);
            }
          }

          @Override
          public void closed() {}
        });
    FileMonitor monitor = new FileMonitor(workers, MonitorSettings.DEFAULTS);
    try {
      document.startIndexing();
      first.await(1, TimeUnit.MINUTES);
      document.startWatching(monitor);
      long periodNanos = TimeUnit.MILLISECONDS.toNanos(10);
      int perBatch = Math.max(1, linesPerSecond / 100);
      long start = System.nanoTime();
      int next = 0;
      try (OutputStream log = Files.newOutputStream(file, StandardOpenOption.APPEND)) {
        while (next < total) {
          int end = Math.min(total, next + perBatch);
          long now = System.nanoTime();
          for (int i = next; i < end; i++) {
            written[i] = now;
            log.write(generator.nextLine());
          }
          log.flush();
          next = end;
          long due = start + (long) (next / perBatch) * periodNanos;
          long wait = due - System.nanoTime();
          if (wait > 0) {
            TimeUnit.NANOSECONDS.sleep(wait);
          }
        }
      }
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (seen[total - 1] == 0 && System.nanoTime() < deadline) {
        TimeUnit.MILLISECONDS.sleep(10);
      }
    } finally {
      document.close();
    }
    long[] latency = new long[total];
    int missing = 0;
    for (int i = 0; i < total; i++) {
      if (seen[i] == 0) {
        missing++;
        latency[i] = Long.MAX_VALUE;
      } else {
        latency[i] = seen[i] - written[i];
      }
    }
    Arrays.sort(latency);
    out.println("tail.linesPerSecond=" + linesPerSecond);
    out.println("tail.lines=" + total);
    out.println("tail.missing=" + missing);
    out.println("tail.p50.ms=" + format(latency[total / 2] / 1e6));
    out.println("tail.p99.ms=" + format(latency[total * 99 / 100] / 1e6));
    out.println("tail.max.ms=" + format(latency[total - 1 - missing] / 1e6));
  }

  /** Stamps the lines the index now covers; line 0 is the one written before indexing. */
  private static void record(LogDocument document, long[] covered, long[] seen, int total) {
    long now = System.nanoTime();
    synchronized (covered) {
      long lines = document.index().lineCount() - 1;
      for (long i = covered[0]; i < Math.min(lines, total); i++) {
        seen[(int) i] = now;
      }
      covered[0] = Math.max(covered[0], Math.min(lines, total));
    }
  }

  private void heap(String key) {
    System.gc();
    long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    long max = Runtime.getRuntime().maxMemory();
    out.println(key + "=" + used + " (" + format(100.0 * used / max) + "% of max)");
  }

  private void shares() {
    for (MemoryShare share : MemoryShare.values()) {
      out.println(
          "share."
              + share.name().toLowerCase(Locale.ROOT)
              + "="
              + budget.reserved(share)
              + "/"
              + budget.limit(share));
    }
  }

  private static long cpuNanos() {
    return ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toNanos();
  }

  /** {@code VmHWM} from {@code /proc/self/status}, or "n/a" off Linux. */
  private static String rssPeak() {
    Path status = Path.of("/proc/self/status");
    if (!Files.isReadable(status)) {
      return "n/a";
    }
    try {
      for (String line : Files.readAllLines(status, StandardCharsets.US_ASCII)) {
        if (line.startsWith("VmHWM:")) {
          return line.substring(6).trim();
        }
      }
    } catch (IOException e) {
      return "n/a";
    }
    return "n/a";
  }

  private static long millis(long nanos) {
    return TimeUnit.NANOSECONDS.toMillis(nanos);
  }

  private static String format(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }
}
