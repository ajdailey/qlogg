package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.ReadBuffers;
import io.github.ajdailey.qlogg.core.search.Chunk.Matched;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.PatternSyntaxException;

/**
 * Searches documents in the background. Each search runs a coordinator on a {@code qlogg-search-}
 * virtual thread that cuts its line range into chunks of whole lines (at most {@code chunkLines}
 * lines or {@code chunkBytes} bytes; a longer line is a chunk of its own), reads each chunk's bytes
 * in one positional read and submits it to the CPU pool. A worker splits the chunk at LF units,
 * drops a trailing CR from each line and returns the matching line numbers. The coordinator merges
 * them into the result set in chunk order, so results stay in line order whatever order chunks
 * finish in, and no match spans two chunks.
 *
 * <p>Memory: a chunk reads into a pooled direct buffer of {@code chunkBytes} reserved from {@link
 * MemoryShare#IO_BUFFERS}, or, for a longer line, a heap buffer for which {@link
 * #LONG_LINE_BYTES_PER_BYTE} bytes per line byte are reserved there until it is matched. Results
 * reserve from {@link MemoryShare#RESULTS}. The pool is trimmed before a long line's reservation is
 * refused, and when a search ends with less of {@code IO_BUFFERS} free than a full pool ({@code
 * maxChunksInFlight} x {@code chunkBytes}); with more free, the next search reuses the buffers
 * instead of allocating them again. A long line that {@code IO_BUFFERS} refuses with nothing in
 * flight, or that is longer than {@link #MAX_LINE_BYTES}, is skipped: it is not matched, the search
 * continues after it, and {@code finished} reports the count of skipped lines. When {@code RESULTS}
 * refuses a chunk's matches, the search keeps its results and ends {@code COMPLETED} with {@link
 * SearchLimit#MEMORY}; reaching {@code maxResults} ends it with {@link SearchLimit#MAX_RESULTS}.
 *
 * <p>A range search ({@link SearchQuery#range}) matches each line against the start and the end
 * expression in the workers; the coordinator walks the chunks in line order, keeping whether a
 * range is open across chunk boundaries, and adds the lines of each range (with {@code inverse},
 * every other line). A line that opens a range is not tested against the end, so a range spans at
 * least two lines unless it is cut by the end of the scope. A skipped line inside an open range is
 * part of it. Partitioning cannot change the result: the state passes from chunk to chunk.
 *
 * <p>A restricted search ({@link SearchQuery#restriction}) tests member lines only. A chunk starts
 * at a member and ends at the last member within {@code chunkLines} lines and {@code chunkBytes}
 * bytes. A chunk of more than one line is read on a {@code qlogg-search-} virtual thread, so
 * several chunks' reads are in flight at once, as runs: spans of whole lines from a member to a
 * member, each one positional read into the chunk's buffer. A run takes the next member when it
 * starts at most {@code gapBytes} after the previous member's end; a longer gap is not read. The
 * worker walks each run's lines at LF and tests the members.
 *
 * <p>A record search ({@link SearchQuery#records}) tests records instead of lines, restricted or
 * not: a chunk ends before a record start ({@link SearchedLines#cut}), or holds one record longer
 * than the chunk bounds, read as a long line is. The worker hands the matcher each record's span,
 * from its first line's first byte to its last line's end before the LF and any CR, internal line
 * feeds included; a hit adds the record's first line. A record that starts in the scope is read
 * whole, past the scope's end if it runs on.
 *
 * <p>A regex that overflows the worker's stack on a line ends the search {@code FAILED} with a
 * {@link PatternTooComplexException} naming that line; the pool thread survives. A term naming a
 * column the query cannot test ends it {@code FAILED} with a {@link ColumnException}.
 *
 * <p>Cancellation is checked before each chunk, every {@link #CHECK_LINES} lines in a worker and
 * every {@link LineText#CHECK_INTERVAL} chars a regex reads. A read on a closed source also ends a
 * search as {@code CANCELED}. The listener's {@code finished} call comes before the handle reports
 * done.
 */
public final class SearchEngine {
  /** Least interval between progress calls. */
  public static final long PUBLISH_INTERVAL_MILLIS = 100;

  /** Lines a worker matches between cancellation checks. */
  public static final int CHECK_LINES = 1024;

  /** {@code IO_BUFFERS} bytes reserved per byte of a line longer than {@code chunkBytes}. */
  public static final int LONG_LINE_BYTES_PER_BYTE = 3;

  /** Bytes of the longest line a search can hold in one heap buffer; a longer line is skipped. */
  public static final long MAX_LINE_BYTES = Integer.MAX_VALUE - 8L;

  private static final long WAIT_SLICE_MILLIS = 50;
  private static final long DRAIN_BOUND_MILLIS = 5000;

  private final Workers workers;
  private final MatcherFactory matchers;
  private final SearchSettings settings;
  private final ReadBuffers buffers;
  private final boolean trimAtEnd;

  /**
   * Creates an engine with its own buffer pool.
   *
   * @param workers runs coordinators and matching
   * @param matchers compiles queries
   * @param settings chunk sizes, in-flight bound, result limit and budget
   */
  public SearchEngine(Workers workers, MatcherFactory matchers, SearchSettings settings) {
    this(workers, matchers, settings, true);
  }

  /**
   * Creates an engine with its own buffer pool.
   *
   * @param workers runs coordinators and matching
   * @param matchers compiles queries
   * @param settings chunk sizes, in-flight bound, result limit and budget
   * @param trimAtEnd true trims the pool when a search ends with the share short of a full pool;
   *     false keeps every buffer it grew to
   */
  SearchEngine(
      Workers workers, MatcherFactory matchers, SearchSettings settings, boolean trimAtEnd) {
    this.workers = workers;
    this.matchers = matchers;
    this.settings = settings;
    this.buffers =
        new ReadBuffers(settings.chunkBytes(), settings.maxChunksInFlight(), settings.budget());
    this.trimAtEnd = trimAtEnd;
  }

  /**
   * Returns the matcher factory.
   *
   * @return the factory
   */
  public MatcherFactory matchers() {
    return matchers;
  }

  /**
   * Returns the settings.
   *
   * @return the settings
   */
  public SearchSettings settings() {
    return settings;
  }

  /**
   * Starts a search over the lines of the source's current index snapshot, limited to the query's
   * scope. Matching lines are added to {@code results} in ascending order as chunks merge.
   *
   * @param source the document; the caller owns and closes it
   * @param query the query
   * @param results receives the matching lines; one search writes a set at a time
   * @param listener receives progress and the outcome on the search thread
   * @return the handle
   * @throws RejectedExecutionException after the workers close
   */
  public OperationHandle search(
      LineSource source, SearchQuery query, MutableLineSet results, SearchListener listener) {
    return search(source, source.index(), query, results, listener);
  }

  /**
   * Starts a search over the lines of a given index snapshot of the source, limited to the query's
   * scope, as {@link #search(LineSource, SearchQuery, MutableLineSet, SearchListener)} does.
   *
   * @param source the document; the caller owns and closes it
   * @param index a snapshot of the source's index; the range is cut to its line count
   * @param query the query
   * @param results receives the matching lines; one search writes a set at a time
   * @param listener receives progress and the outcome on the search thread
   * @return the handle
   * @throws RejectedExecutionException after the workers close
   */
  public OperationHandle search(
      LineSource source,
      LineIndex index,
      SearchQuery query,
      MutableLineSet results,
      SearchListener listener) {
    long count = index.lineCount();
    long first = 0;
    long end = count;
    if (query.scope() != null) {
      first = Math.min(query.scope().first(), count);
      end = Math.min(query.scope().end(), count);
    }
    Operation operation = new Operation(source, index, query, first, end, results, listener);
    workers.start(Workers.Kind.SEARCH, operation);
    return operation;
  }

  /**
   * True when {@code IO_BUFFERS} has fewer bytes free than a full pool of this engine's buffers.
   */
  private boolean shareShort() {
    return settings.budget().available(MemoryShare.IO_BUFFERS)
        < (long) settings.maxChunksInFlight() * settings.chunkBytes();
  }

  /** A worker ended with an exception the workers' failure handler received. */
  private static final class WorkerFailure extends Exception {
    private static final long serialVersionUID = 1L;

    WorkerFailure() {
      super("A search worker failed");
    }
  }

  /**
   * A matcher, the range end's matcher or null, and their line view, reused by whichever worker
   * thread takes them.
   */
  private record Worker(LineMatcher matcher, LineMatcher end, LineText text) {}

  private final class Operation implements OperationHandle, Runnable {
    private final ByteSource source;
    private final LineIndex index;
    private final SearchQuery query;
    private final TextEncoding encoding;
    private final long first;
    private final long end;
    private final MutableLineSet results;
    private final SearchListener listener;
    private final SearchedLines tested;
    private final ChunkReader reader;
    private final long last;
    private final CancellationToken token = new CancellationToken();
    private final CountDownLatch done = new CountDownLatch(1);
    private final Queue<Chunk> inFlight = new ArrayDeque<Chunk>();
    private final Queue<Worker> idle = new ConcurrentLinkedQueue<Worker>();
    private final byte[] lineFeed;
    private final byte[] carriageReturn;
    private long searched;
    private long matched;
    private long skipped;
    private SearchLimit limit = SearchLimit.NONE;
    private boolean open;
    private boolean openBeforeLast;

    Operation(
        LineSource lines,
        LineIndex index,
        SearchQuery query,
        long first,
        long end,
        MutableLineSet results,
        SearchListener listener) {
      this.source = lines.bytes();
      this.index = index;
      this.query = query;
      this.encoding = lines.encoding();
      this.first = first;
      this.end = end;
      this.results = results;
      this.listener = listener;
      this.lineFeed = encoding.lineFeed();
      this.carriageReturn = encoding.carriageReturn();
      this.open = query.rangeOpen();
      this.tested = SearchedLines.of(query, index, settings.gapBytes(), lineFeed.length);
      this.reader = new ChunkReader(source, tested, buffers, token);
      this.last = tested.last(end);
    }

    @Override
    public void cancel() {
      token.cancel();
    }

    @Override
    public boolean isDone() {
      return done.getCount() == 0;
    }

    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
      return done.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void run() {
      OperationOutcome outcome = OperationOutcome.FAILED;
      Exception failure = null;
      try {
        idle.add(newWorker());
        searchAll();
        outcome = OperationOutcome.COMPLETED;
      } catch (CancellationException | RejectedExecutionException | ClosedChannelException e) {
        outcome = OperationOutcome.CANCELED;
      } catch (InterruptedException e) {
        outcome = OperationOutcome.CANCELED;
        Thread.currentThread().interrupt();
      } catch (UncheckedIOException e) {
        if (e.getCause() instanceof ClosedChannelException) {
          outcome = OperationOutcome.CANCELED;
        } else {
          failure = e.getCause();
        }
      } catch (IOException
          | PatternSyntaxException
          | PatternTooComplexException
          | WorkerFailure e) {
        failure = e;
      } finally {
        drain();
        try {
          listener.progressed(searched, end - first, matched);
          if (outcome == OperationOutcome.COMPLETED
              && limit == SearchLimit.NONE
              && query.range()
              && end > first) {
            listener.rangeOpenBeforeLast(openBeforeLast);
          }
          listener.finished(
              outcome,
              outcome == OperationOutcome.COMPLETED ? limit : SearchLimit.NONE,
              skipped,
              failure);
        } finally {
          done.countDown();
        }
      }
    }

    private Worker newWorker() {
      LineText text = new LineText(encoding, token, query.hideAnsi());
      if (!query.range()) {
        return new Worker(matchers.create(query, encoding, token), null, text);
      }
      return new Worker(
          matchers.create(query.lineSearch(query.expression()), encoding, token),
          matchers.create(query.lineSearch(query.rangeEnd()), encoding, token),
          text);
    }

    private void searchAll() throws IOException, InterruptedException, WorkerFailure {
      long next = tested.first(first, end);
      long lastPublish = System.nanoTime();
      while (next < end || !inFlight.isEmpty()) {
        token.check();
        if (next < end && inFlight.size() < settings.maxChunksInFlight()) {
          Chunk chunk = read(next);
          if (chunk != null) {
            submit(chunk);
            next = tested.first(next + chunk.lines, end);
            continue;
          }
          if (inFlight.isEmpty()) {
            skipped++;
            if (query.range() && !skippedInRange(next)) {
              return;
            }
            next = tested.first(next + 1, end);
            searched = next - first;
            continue;
          }
        }
        Chunk head = inFlight.peek();
        awaitChunk(head);
        inFlight.remove();
        if (!merge(head)) {
          return;
        }
        if (System.nanoTime() - lastPublish >= PUBLISH_INTERVAL_MILLIS * 1_000_000L) {
          listener.progressed(searched, end - first, matched);
          lastPublish = System.nanoTime();
        }
      }
      searched = end - first;
    }

    /**
     * Reads the chunk starting at a line. Returns null when no pooled buffer is free while chunks
     * are in flight, when {@code IO_BUFFERS} refuses a long line's buffer, or when the line is
     * longer than {@link #MAX_LINE_BYTES}. With nothing in flight, null means the line cannot be
     * budgeted.
     */
    private Chunk read(long line) throws IOException, InterruptedException {
      int chunkBytes = settings.chunkBytes();
      long start = index.lineStart(line);
      long stop = tested.cut(line, Math.min(end, line + settings.chunkLines()));
      long finish = index.lineEnd(stop - 1);
      if (finish - start > chunkBytes) {
        stop = Math.max(line + 1, Math.min(stop, index.lineAt(start + chunkBytes)));
        stop = tested.cut(line, stop);
        finish = index.lineEnd(stop - 1);
      }
      long span = finish - start;
      if (span <= chunkBytes) {
        ByteBuffer buffer = inFlight.isEmpty() ? buffers.acquire(token) : buffers.tryAcquire();
        if (buffer == null) {
          return null;
        }
        if (query.restriction() != null && stop - line > 1) {
          return new Chunk(line, stop, null, buffer, true, 0);
        }
        ChunkRuns runs = null;
        try {
          runs = reader.readSpan(buffer, line, stop, start, finish);
        } finally {
          if (runs == null) {
            buffers.release(buffer);
          }
        }
        return marked(new Chunk(line, stop, runs, buffer, true, 0));
      }
      long cost = span * LONG_LINE_BYTES_PER_BYTE;
      if (span > MAX_LINE_BYTES || !reserveLong(cost)) {
        return null;
      }
      boolean filled = false;
      try {
        ByteBuffer heap = ByteBuffer.allocate((int) span);
        reader.readLong(heap, start);
        filled = true;
        ChunkRuns runs = new ChunkRuns();
        runs.add(line, stop - 1, start, finish);
        return marked(new Chunk(line, stop, runs, heap, false, cost));
      } finally {
        if (!filled) {
          settings.budget().release(MemoryShare.IO_BUFFERS, cost);
        }
      }
    }

    private Chunk marked(Chunk chunk) {
      chunk.marks = tested.marks(chunk.firstLine, chunk.firstLine + chunk.lines);
      return chunk;
    }

    /** Reserves a long line's buffer, trimming the pool once before refusing. */
    private boolean reserveLong(long cost) {
      MemoryBudget budget = settings.budget();
      if (budget.reserve(MemoryShare.IO_BUFFERS, cost)) {
        return true;
      }
      return buffers.trim() > 0 && budget.reserve(MemoryShare.IO_BUFFERS, cost);
    }

    private void submit(final Chunk chunk) {
      inFlight.add(chunk);
      try {
        if (chunk.runs != null) {
          matchOnCpu(chunk);
        } else {
          workers.start(
              Workers.Kind.SEARCH,
              new Runnable() {
                @Override
                public void run() {
                  fillRuns(chunk);
                }
              });
        }
      } catch (RejectedExecutionException e) {
        recycle(chunk);
        chunk.done.countDown();
        throw e;
      }
    }

    private void matchOnCpu(final Chunk chunk) {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              match(chunk);
            }
          });
    }

    /**
     * Runs on a {@code qlogg-search-} virtual thread: reads a restricted chunk, then matches it.
     */
    private void fillRuns(Chunk chunk) {
      boolean handed = false;
      try {
        if (reader.readRuns(chunk)) {
          matchOnCpu(chunk);
          handed = true;
        }
      } catch (RejectedExecutionException e) {
        chunk.fail(new CancellationException("Workers closed"));
      } finally {
        if (!handed) {
          recycle(chunk);
          chunk.done.countDown();
        }
      }
    }

    /** Runs on a CPU worker. */
    private void match(Chunk chunk) {
      Worker worker = idle.poll();
      try {
        if (worker == null) {
          worker = newWorker();
        }
        chunk.matched = scan(chunk, worker);
      } catch (CancellationException | UncheckedIOException | PatternTooComplexException e) {
        chunk.fail(e);
      } finally {
        recycle(chunk);
        if (worker != null) {
          worker.text().trim();
          idle.add(worker);
        }
        chunk.done.countDown();
      }
    }

    private void recycle(Chunk chunk) {
      if (chunk.pooled) {
        buffers.release(chunk.buffer);
      } else {
        settings.budget().release(MemoryShare.IO_BUFFERS, chunk.reservedBytes);
      }
    }

    private Matched scan(Chunk chunk, Worker worker) {
      ByteBuffer bytes = chunk.buffer;
      ChunkRuns runs = chunk.runs;
      int width = lineFeed.length;
      LineText text = worker.text();
      LineMatcher matcher = worker.matcher();
      LineMatcher closer = worker.end();
      long[] hits = new long[16];
      int count = 0;
      long[] ends = closer == null ? null : new long[16];
      int endCount = 0;
      RecordMarks marks = chunk.marks;
      int position = 0;
      int walked = 0;
      long member = chunk.firstLine;
      long unit = -1;
      long unitLast = -1;
      int unitStart = 0;
      for (int run = 0; run < runs.count(); run++) {
        long firstLine = runs.firstLine(run);
        int lines = runs.lineCount(run);
        int length = position + runs.length(run);
        for (int i = 0; i < lines; i++) {
          if (walked++ % CHECK_LINES == 0) {
            token.check();
          }
          int lineEnd = length;
          int next = length;
          if (i < lines - 1) {
            lineEnd = findUnit(bytes, position, length, lineFeed);
            if (lineEnd < 0) {
              throw new UncheckedIOException(new IOException("File changed after it was indexed"));
            }
            next = lineEnd + width;
          }
          long line = firstLine + i;
          if (unit < 0) {
            if (line != member) {
              position = next;
              continue;
            }
            unit = line;
            unitStart = position;
            unitLast = marks == null ? line : marks.end(line) - 1;
          }
          if (line < unitLast && i < lines - 1) {
            position = next;
            continue;
          }
          member = tested.next(line);
          int textEnd = lineEnd;
          if (lineEnd - position >= width && unitAt(bytes, lineEnd - width, carriageReturn)) {
            textEnd = lineEnd - width;
          }
          text.set(bytes, unitStart, textEnd);
          boolean hit;
          boolean closes = false;
          try {
            hit = matcher.matches(text);
            if (closer != null) {
              closes = closer.matches(text);
            }
          } catch (StackOverflowError e) {
            throw new PatternTooComplexException(unit);
          }
          if (hit) {
            if (count == hits.length) {
              hits = Arrays.copyOf(hits, count * 2);
            }
            hits[count++] = unit;
          }
          if (closes) {
            if (endCount == ends.length) {
              ends = Arrays.copyOf(ends, endCount * 2);
            }
            ends[endCount++] = line;
          }
          unit = -1;
          position = next;
        }
      }
      return new Matched(hits, count, ends, endCount, null);
    }

    private void awaitChunk(Chunk chunk) throws InterruptedException {
      while (!chunk.done.await(WAIT_SLICE_MILLIS, TimeUnit.MILLISECONDS)) {
        token.check();
        if (workers.isClosed()) {
          throw new CancellationException("Workers closed");
        }
      }
    }

    /** Adds a chunk's matches; false when a limit stops the search. */
    private boolean merge(Chunk chunk) throws WorkerFailure {
      Matched result = chunk.matched;
      if (result == null) {
        throw new WorkerFailure();
      }
      if (result.failure() instanceof CancellationException canceled) {
        throw canceled;
      }
      if (result.failure() instanceof UncheckedIOException failed) {
        throw failed;
      }
      if (result.failure() instanceof PatternTooComplexException complex) {
        throw complex;
      }
      if (query.range()) {
        result = ranged(chunk, result);
      }
      int take = result.count();
      long max = settings.maxResults();
      boolean capped = max > 0 && matched + take > max;
      if (capped) {
        take = (int) (max - matched);
      }
      if (!results.addSorted(result.hits(), take)) {
        limit = SearchLimit.MEMORY;
        return false;
      }
      matched += take;
      if (capped) {
        limit = SearchLimit.MAX_RESULTS;
        long stop = take > 0 ? result.hits()[take - 1] + 1 : chunk.firstLine;
        searched = stop - first;
        return false;
      }
      searched = Math.min(end, chunk.firstLine + chunk.lines) - first;
      return true;
    }

    /**
     * Turns a chunk's start and end lines into the lines of its ranges, or with {@code inverse} the
     * lines outside them, carrying {@link #open} across chunks.
     */
    private Matched ranged(Chunk chunk, Matched found) {
      long[] starts = found.hits();
      long[] ends = found.ends();
      boolean inverse = query.inverse();
      long stop = chunk.firstLine + chunk.lines;
      long[] lines = new long[chunk.runs.lines()];
      int n = 0;
      int s = 0;
      int e = 0;
      long line = chunk.firstLine;
      while (line < stop) {
        if (open) {
          while (e < found.endCount() && ends[e] < line) {
            e++;
          }
          boolean closes = e < found.endCount();
          long close = closes ? ends[e] : stop - 1;
          if (last >= line && last <= close) {
            openBeforeLast = true;
          }
          if (!inverse) {
            n = tested.add(lines, n, line, close + 1);
          }
          line = close + 1;
          open = !closes;
        } else {
          while (s < found.count() && starts[s] < line) {
            s++;
          }
          long opening = s < found.count() ? starts[s] : stop;
          if (last >= line && last <= Math.min(opening, stop - 1)) {
            openBeforeLast = false;
          }
          if (inverse) {
            n = tested.add(lines, n, line, opening);
          } else if (opening < stop) {
            lines[n++] = opening;
          }
          open = opening < stop;
          line = opening + 1;
        }
      }
      return new Matched(lines, n, null, 0, null);
    }

    /**
     * Adds a skipped line of a range search: inside an open range it belongs to the range, and it
     * neither opens nor closes one.
     *
     * @return false when a limit stops the search
     */
    private boolean skippedInRange(long line) {
      if (line == last) {
        openBeforeLast = open;
      }
      if (open == query.inverse()) {
        return true;
      }
      long max = settings.maxResults();
      if (max > 0 && matched >= max) {
        limit = SearchLimit.MAX_RESULTS;
        return false;
      }
      if (!results.addSorted(new long[] {line}, 1)) {
        limit = SearchLimit.MEMORY;
        return false;
      }
      matched++;
      return true;
    }

    /** Waits for chunks still being matched, bounded; their workers return the buffers. */
    private void drain() {
      long deadline = System.nanoTime() + DRAIN_BOUND_MILLIS * 1_000_000L;
      boolean interrupted = false;
      while (!inFlight.isEmpty()) {
        Chunk chunk = inFlight.poll();
        try {
          long left = Math.max(0, deadline - System.nanoTime());
          if (!chunk.done.await(left, TimeUnit.NANOSECONDS)) {
            break;
          }
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
      inFlight.clear();
      if (trimAtEnd && shareShort()) {
        buffers.trim();
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Returns the offset of the first unit equal to {@code unit} at an aligned offset, or -1. */
  static int findUnit(ByteBuffer bytes, int from, int to, byte[] unit) {
    int width = unit.length;
    int at = from;
    while (true) {
      int found = ByteScan.indexOf(bytes, at, to - width + 1, unit[0]);
      if (found < 0 || width == 1) {
        return found;
      }
      if ((found - from) % width == 0 && unitAt(bytes, found, unit)) {
        return found;
      }
      at = found + 1;
    }
  }

  static boolean unitAt(ByteBuffer bytes, int at, byte[] unit) {
    for (int k = 0; k < unit.length; k++) {
      if (bytes.get(at + k) != unit[k]) {
        return false;
      }
    }
    return true;
  }
}
