package io.github.ajdailey.qlogg.bench;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.FileIdentity;
import io.github.ajdailey.qlogg.core.model.DocumentListener;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.search.LineSource;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchLimit;
import io.github.ajdailey.qlogg.core.search.SearchListener;
import io.github.ajdailey.qlogg.core.search.SearchQuery;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Line sets of a derived view: {@code copy}, {@link MutableLineSet#copy} of a set of 10^7 lines
 * every {@code spacing} lines (2 fills bitmap buckets, 20 array buckets); and {@code search}, a
 * regex search of a generated {@code ascii-short} log restricted to the lines of a literal search
 * or every 50th line ({@code none} searches every line), at a {@code gapBytes} setting. Each set's
 * size and retained bytes, and the bytes one search reads, print once per trial. The file is
 * written once to {@code -Dqlogg.bench.dir} and reused; the cache is warm.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(
    value = 1,
    jvmArgsPrepend = {Harness.LAUNCHER_HEAP, Harness.LAUNCHER_GC})
public class DerivedSetProbe {
  private static final String REGEX = "worker-1[0-5]\\] #\\d+7 ";

  /** A set of 10^7 lines. */
  @State(Scope.Benchmark)
  public static class Members {
    /** Lines between members. */
    @Param({"2", "20"})
    public int spacing;

    private MutableLineSet set;

    /** Builds the set. */
    @Setup(Level.Trial)
    public void prepare() {
      set = new MutableLineSet();
      long[] batch = new long[65_536];
      long line = 0;
      for (int done = 0; done < 10_000_000; done += batch.length) {
        int n = Math.min(batch.length, 10_000_000 - done);
        for (int i = 0; i < n; i++) {
          batch[i] = line;
          line += spacing;
        }
        set.addSorted(batch, n);
      }
      System.out.println("copy.members=" + set.size() + " copy.bytes=" + set.retainedBytes());
    }
  }

  /** An indexed log and the restriction set. */
  @State(Scope.Benchmark)
  public static class Document {
    /** File size in MiB. */
    @Param({"1024"})
    public int fileMib;

    /**
     * Literal whose matches restrict the search, {@code every50} for every 50th line, or {@code
     * none}.
     */
    @Param({"none", LogGenerator.RARE_TOKEN, "every50", LogGenerator.COMMON_TOKEN})
    public String restriction;

    /** {@code SearchSettings.gapBytes}. */
    @Param({"65536"})
    public int gapBytes;

    private Workers workers;
    private SearchEngine engine;
    private LogDocument document;
    private LineSource counted;
    private final AtomicLong bytesRead = new AtomicLong();
    private MutableLineSet members;

    /**
     * Writes the file when it does not exist, opens and indexes it, and builds the restriction.
     *
     * @throws IOException when the directory is missing, the write fails or indexing fails
     * @throws InterruptedException when interrupted while indexing or searching
     */
    @Setup(Level.Trial)
    public void prepare() throws IOException, InterruptedException {
      String dir = System.getProperty("qlogg.bench.dir");
      if (dir == null) {
        throw new IOException("Set -Dqlogg.bench.dir to a scratch directory");
      }
      Path file = Path.of(dir).resolve("ascii-short-" + fileMib + "m.log");
      if (!Files.exists(file)) {
        new LogGenerator(LogGenerator.Profile.ASCII_SHORT, 1).write(file, (long) fileMib << 20);
      }
      workers =
          new Workers(
              Runtime.getRuntime().availableProcessors(),
              new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable error) {
                  error.printStackTrace();
                }
              });
      MemoryBudget budget = MemoryBudget.fromRuntime();
      SearchSettings defaults = SearchSettings.defaults(workers.cpuThreads(), budget);
      engine =
          new SearchEngine(
              workers,
              StandardMatchers.INSTANCE,
              new SearchSettings(
                  defaults.chunkLines(),
                  defaults.chunkBytes(),
                  gapBytes,
                  defaults.maxChunksInFlight(),
                  0,
                  budget));
      DocumentServices services =
          new DocumentServices(
              workers,
              new Indexer(workers, IndexSettings.defaults(workers.cpuThreads(), budget)),
              engine,
              new BlockCache(65536, 1 << 20),
              budget,
              StandardCharsets.ISO_8859_1);
      document = LogDocument.open(file, null, services);
      final CountDownLatch indexed = new CountDownLatch(1);
      document.addListener(
          new DocumentListener() {
            @Override
            public void indexProgressed(IndexProgress progress) {
              // Only the end matters.
            }

            @Override
            public void indexFinished(OperationOutcome outcome, IOException failure) {
              indexed.countDown();
            }

            @Override
            public void fileChanged(FileChange change) {}

            @Override
            public void closed() {
              // Closed in tearDown.
            }
          });
      document.startIndexing();
      if (!indexed.await(10, TimeUnit.MINUTES)) {
        throw new IOException("Indexing did not finish");
      }
      counted = counting(document, bytesRead);
      if (restriction.equals("every50")) {
        members = new MutableLineSet();
        long[] batch = new long[4096];
        int n = 0;
        for (long line = 0; line < document.index().lineCount(); line += 50) {
          batch[n++] = line;
          if (n == batch.length) {
            members.addSorted(batch, n);
            n = 0;
          }
        }
        members.addSorted(batch, n);
      } else if (!restriction.equals("none")) {
        members = search(SearchQuery.of(restriction, MatchMode.LITERAL));
      }
      if (members != null) {
        System.out.println(
            "restriction.lines="
                + document.index().lineCount()
                + " restriction.members="
                + members.size()
                + " restriction.bytes="
                + members.retainedBytes());
      }
      bytesRead.set(0);
      search(SearchQuery.of(REGEX, MatchMode.REGEX).restrictedTo(members));
      System.out.println("search.bytesRead=" + bytesRead.get());
    }

    /** Closes the document and the workers. */
    @TearDown(Level.Trial)
    public void tearDown() {
      document.close();
      workers.close();
    }

    MutableLineSet search(SearchQuery query) throws InterruptedException {
      MutableLineSet results = new MutableLineSet();
      final OperationOutcome[] outcome = new OperationOutcome[1];
      OperationHandle handle =
          engine.search(
              counted,
              query,
              results,
              new SearchListener() {
                @Override
                public void progressed(long searchedLines, long totalLines, long matchCount) {
                  // Only the end matters.
                }

                @Override
                public void finished(
                    OperationOutcome result, SearchLimit limit, long skipped, Exception failure) {
                  outcome[0] = result;
                }
              });
      if (!handle.await(TimeUnit.MINUTES.toMillis(10))
          || outcome[0] != OperationOutcome.COMPLETED) {
        throw new IllegalStateException("Search did not complete: " + outcome[0]);
      }
      return results;
    }
  }

  /** Returns the document with its reads counted. */
  private static LineSource counting(final LogDocument document, final AtomicLong read) {
    final ByteSource bytes = document.bytes();
    final ByteSource counting =
        new ByteSource() {
          @Override
          public Path path() {
            return bytes.path();
          }

          @Override
          public long size() throws IOException {
            return bytes.size();
          }

          @Override
          public int read(long position, ByteBuffer dst) throws IOException {
            int n = bytes.read(position, dst);
            if (n > 0) {
              read.addAndGet(n);
            }
            return n;
          }

          @Override
          public FileIdentity identity() {
            return bytes.identity();
          }

          @Override
          public void close() {
            // The document closes its source.
          }
        };
    return new LineSource() {
      @Override
      public LineIndex index() {
        return document.index();
      }

      @Override
      public ByteSource bytes() {
        return counting;
      }

      @Override
      public TextEncoding encoding() {
        return document.encoding();
      }
    };
  }

  /**
   * Copies the set.
   *
   * @param members the set
   * @return the copy's size
   */
  @Benchmark
  public long copy(Members members) {
    return members.set.copy(null).size();
  }

  /**
   * Runs the regex over the restriction's lines.
   *
   * @param document the log and restriction
   * @return match count
   * @throws InterruptedException when interrupted
   */
  @Benchmark
  public long search(Document document) throws InterruptedException {
    SearchQuery query = SearchQuery.of(REGEX, MatchMode.REGEX).restrictedTo(document.members);
    return document.search(query).size();
  }
}
