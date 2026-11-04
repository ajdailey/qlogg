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
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Arrays;
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
 * reserve from {@link MemoryShare#RESULTS}. The pool is trimmed when a search ends and before a
 * long line's reservation is refused. A long line that {@code IO_BUFFERS} refuses with nothing in
 * flight, or that is longer than {@link #MAX_LINE_BYTES}, is skipped: it is not matched, the search
 * continues after it, and {@code finished} reports the count of skipped lines. When {@code RESULTS}
 * refuses a chunk's matches, the search keeps its results and ends {@code COMPLETED} with {@link
 * SearchLimit#MEMORY}; reaching {@code maxResults} ends it with {@link SearchLimit#MAX_RESULTS}.
 *
 * <p>A regex that overflows the worker's stack on a line ends the search {@code FAILED} with a
 * {@link PatternTooComplexException} naming that line; the pool thread survives.
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
   * @param trimAtEnd true trims the pool when a search ends; false keeps every buffer it grew to
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

  /** One chunk's bytes and, once matched, its matching lines. */
  private static final class Chunk {
    final long firstLine;
    final int lines;
    final ByteBuffer buffer;
    final boolean pooled;
    final long reservedBytes;
    final CountDownLatch done = new CountDownLatch(1);
    volatile Matched matched;

    Chunk(long firstLine, int lines, ByteBuffer buffer, boolean pooled, long reservedBytes) {
      this.firstLine = firstLine;
      this.lines = lines;
      this.buffer = buffer;
      this.pooled = pooled;
      this.reservedBytes = reservedBytes;
    }
  }

  /** A worker's result: the matching lines in ascending order, or the exception it ended with. */
  private record Matched(long[] hits, int count, RuntimeException failure) {}

  /** A worker ended with an exception the workers' failure handler received. */
  private static final class WorkerFailure extends Exception {
    private static final long serialVersionUID = 1L;

    WorkerFailure() {
      super("A search worker failed");
    }
  }

  /** A matcher and its line view, reused by whichever worker thread takes them. */
  private record Worker(LineMatcher matcher, LineText text) {}

  private final class Operation implements OperationHandle, Runnable {
    private final ByteSource source;
    private final LineIndex index;
    private final SearchQuery query;
    private final TextEncoding encoding;
    private final long first;
    private final long end;
    private final MutableLineSet results;
    private final SearchListener listener;
    private final CancellationToken token = new CancellationToken();
    private final CountDownLatch done = new CountDownLatch(1);
    private final ArrayDeque<Chunk> inFlight = new ArrayDeque<Chunk>();
    private final ConcurrentLinkedQueue<Worker> idle = new ConcurrentLinkedQueue<Worker>();
    private final byte[] lineFeed;
    private final byte[] carriageReturn;
    private long searched;
    private long matched;
    private long skipped;
    private SearchLimit limit = SearchLimit.NONE;

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
      return new Worker(
          matchers.create(query, encoding, token), new LineText(encoding, token, query.hideAnsi()));
    }

    private void searchAll() throws IOException, InterruptedException, WorkerFailure {
      long next = first;
      long lastPublish = System.nanoTime();
      while (next < end || !inFlight.isEmpty()) {
        token.check();
        if (next < end && inFlight.size() < settings.maxChunksInFlight()) {
          Chunk chunk = read(next);
          if (chunk != null) {
            submit(chunk);
            next += chunk.lines;
            continue;
          }
          if (inFlight.isEmpty()) {
            skipped++;
            next++;
            searched = next - first;
            continue;
          }
        }
        Chunk head = inFlight.peek();
        awaitChunk(head);
        inFlight.poll();
        if (!merge(head)) {
          return;
        }
        if (System.nanoTime() - lastPublish >= PUBLISH_INTERVAL_MILLIS * 1_000_000L) {
          listener.progressed(searched, end - first, matched);
          lastPublish = System.nanoTime();
        }
      }
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
      long stop = Math.min(end, line + settings.chunkLines());
      long finish = index.lineEnd(stop - 1);
      if (finish - start > chunkBytes) {
        stop = Math.max(line + 1, Math.min(stop, index.lineAt(start + chunkBytes)));
        finish = index.lineEnd(stop - 1);
      }
      long span = finish - start;
      int lines = (int) (stop - line);
      if (span <= chunkBytes) {
        ByteBuffer buffer = inFlight.isEmpty() ? buffers.acquire(token) : buffers.tryAcquire();
        if (buffer == null) {
          return null;
        }
        boolean filled = false;
        try {
          fill(buffer, start, (int) span);
          filled = true;
        } finally {
          if (!filled) {
            buffers.release(buffer);
          }
        }
        return new Chunk(line, lines, buffer, true, 0);
      }
      long cost = span * LONG_LINE_BYTES_PER_BYTE;
      if (span > MAX_LINE_BYTES || !reserveLong(cost)) {
        return null;
      }
      boolean filled = false;
      try {
        ByteBuffer heap = ByteBuffer.allocate((int) span);
        fillLong(heap, start);
        filled = true;
        return new Chunk(line, lines, heap, false, cost);
      } finally {
        if (!filled) {
          settings.budget().release(MemoryShare.IO_BUFFERS, cost);
        }
      }
    }

    /** Reserves a long line's buffer, trimming the pool once before refusing. */
    private boolean reserveLong(long cost) {
      MemoryBudget budget = settings.budget();
      if (budget.reserve(MemoryShare.IO_BUFFERS, cost)) {
        return true;
      }
      return buffers.trim() > 0 && budget.reserve(MemoryShare.IO_BUFFERS, cost);
    }

    private void fill(ByteBuffer buffer, long position, int length) throws IOException {
      buffer.clear().limit(length);
      while (buffer.hasRemaining()) {
        if (source.read(position + buffer.position(), buffer) < 0) {
          throw new IOException("File is shorter than its line index");
        }
      }
      buffer.flip();
    }

    /** Reads a long line through a pooled direct buffer, slice by slice. */
    private void fillLong(ByteBuffer heap, long position) throws IOException, InterruptedException {
      ByteBuffer slice = buffers.acquire(token);
      try {
        while (heap.hasRemaining()) {
          token.check();
          int length = Math.min(slice.capacity(), heap.remaining());
          fill(slice, position + heap.position(), length);
          heap.put(slice);
        }
        heap.flip();
      } finally {
        buffers.release(slice);
      }
    }

    private void submit(final Chunk chunk) {
      inFlight.add(chunk);
      try {
        workers.executeCpu(
            new Runnable() {
              @Override
              public void run() {
                match(chunk);
              }
            });
      } catch (RejectedExecutionException e) {
        recycle(chunk);
        chunk.done.countDown();
        throw e;
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
        chunk.matched = new Matched(null, 0, e);
      } finally {
        recycle(chunk);
        if (worker != null) {
          worker.text().trim();
          idle.offer(worker);
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
      int length = bytes.limit();
      int unit = lineFeed.length;
      LineText text = worker.text();
      LineMatcher matcher = worker.matcher();
      long[] hits = new long[16];
      int count = 0;
      int position = 0;
      for (int i = 0; i < chunk.lines; i++) {
        if (i % CHECK_LINES == 0) {
          token.check();
        }
        int lineEnd = length;
        int next = length;
        if (i < chunk.lines - 1) {
          lineEnd = findUnit(bytes, position, length, lineFeed);
          if (lineEnd < 0) {
            throw new UncheckedIOException(new IOException("File changed after it was indexed"));
          }
          next = lineEnd + unit;
        }
        int textEnd = lineEnd;
        if (lineEnd - position >= unit && unitAt(bytes, lineEnd - unit, carriageReturn)) {
          textEnd = lineEnd - unit;
        }
        text.set(bytes, position, textEnd);
        boolean hit;
        try {
          hit = matcher.matches(text);
        } catch (StackOverflowError e) {
          throw new PatternTooComplexException(chunk.firstLine + i);
        }
        if (hit) {
          if (count == hits.length) {
            hits = Arrays.copyOf(hits, count * 2);
          }
          hits[count++] = chunk.firstLine + i;
        }
        position = next;
      }
      return new Matched(hits, count, null);
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
      searched = chunk.firstLine + chunk.lines - first;
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
      if (trimAtEnd) {
        buffers.trim();
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Returns the offset of the first unit equal to {@code unit} at an aligned offset, or -1. */
  private static int findUnit(ByteBuffer bytes, int from, int to, byte[] unit) {
    int width = unit.length;
    if (width == 1) {
      byte b = unit[0];
      for (int i = from; i < to; i++) {
        if (bytes.get(i) == b) {
          return i;
        }
      }
      return -1;
    }
    for (int i = from; i + width <= to; i += width) {
      if (unitAt(bytes, i, unit)) {
        return i;
      }
    }
    return -1;
  }

  private static boolean unitAt(ByteBuffer bytes, int at, byte[] unit) {
    for (int k = 0; k < unit.length; k++) {
      if (bytes.get(at + k) != unit[k]) {
        return false;
      }
    }
    return true;
  }
}
