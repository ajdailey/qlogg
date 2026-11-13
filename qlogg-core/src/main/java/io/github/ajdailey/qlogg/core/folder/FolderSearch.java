package io.github.ajdailey.qlogg.core.folder;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
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
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.model.DocumentListener;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.LineReader;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.search.SearchLimit;
import io.github.ajdailey.qlogg.core.search.SearchListener;
import io.github.ajdailey.qlogg.core.text.EncodingDetector;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.PatternSyntaxException;

/**
 * Searches the files of a folder without opening a tab for each. A coordinator on a {@code
 * qlogg-search-} virtual thread validates the folder ({@link SafePaths#logDirectory}), walks it,
 * then takes the files one at a time in path order: {@link LogDocument#open} with encoding
 * detection, a full index, a {@link io.github.ajdailey.qlogg.core.search.SearchEngine} search into
 * a {@link MutableLineSet}, a read of each matching line, and close. File content stays on disk;
 * the heap holds one file's index and result set at a time, plus the listed lines.
 *
 * <p>Walk: subfolders only when {@code recursive}; a file name must match {@code names} and not
 * {@code exclude} ({@link NamePattern#excludes}). A symbolic link to a file is searched, as an open
 * follows it; a link to a folder is not entered. A folder whose real path the walk has entered is
 * skipped: Windows reports a directory junction as a folder, and one naming an ancestor would recur
 * until the path is too long. A file or folder the walk cannot read is listed {@code UNREADABLE}.
 *
 * <p>A file that does not open or index is listed {@code UNREADABLE}; one with a one-byte code unit
 * and a NUL byte in its first {@link EncodingDetector#SAMPLE_BYTES} is listed {@code NOT_TEXT}
 * (compressed rotations such as {@code app.log.2.gz}); a search failure on one file lists it {@code
 * FAILED} and the walk goes on. A regex that does not compile ends the whole search {@code FAILED}.
 * An {@link AccessDeniedException} lists the file or folder {@code ACCESS_DENIED} in place of
 * {@code UNREADABLE}.
 *
 * <p>Memory: each walked file reserves {@link #ENTRY_BYTES} plus 2 bytes per path char from {@code
 * RESULTS}, each entered folder the same until the walk ends, and each listed line {@link
 * #HIT_BYTES} plus 2 bytes per char; the line text is cut at {@link #MAX_TEXT_CHARS}. {@link
 * FolderResults} holds these reservations until it closes. A refused reservation, in this class or
 * in the file's search, first calls {@link FolderListener#resultsRefused}: on true the reservation
 * is retried, or the file searched again. Otherwise it stops the search: the current file keeps the
 * lines listed so far, {@link FolderResults#limited} turns true and the search ends {@code
 * COMPLETED} with {@code MEMORY}. A run indexes through its own {@link Indexer} of {@link
 * #INDEX_BLOCK_BYTES} blocks, which keeps its buffer pool until the run ends: a pool trimmed after
 * each file frees its direct memory only at a later GC. Listed lines are read through the run's own
 * small cache, not the shared {@code BlockCache}.
 *
 * <p>Cancellation is checked at every walked entry, every 50 ms while a file indexes or searches,
 * and at every listed line; the current file's indexing and search are canceled and the file closed
 * before {@code finished}.
 */
public final class FolderSearch {
  /** Chars of a matching line kept for the listing. */
  public static final int MAX_TEXT_CHARS = 512;

  /** {@code RESULTS} bytes per walked file, plus 2 per path char. */
  public static final long ENTRY_BYTES = 96;

  /**
   * {@code RESULTS} bytes per listed line, plus 2 per char: line number, string and array slack.
   */
  public static final long HIT_BYTES = 80;

  private static final long SLICE_MILLIS = 50;
  private static final long PUBLISH_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
  private static final int FILL_LINES = 4096;

  /** Block size of the run's own indexer, a quarter of the default to keep its pool small. */
  public static final int INDEX_BLOCK_BYTES = 1024 * 1024;

  /** Block size of the run's own read cache for listed lines. */
  static final int LIST_BLOCK_BYTES = 8 * 1024;

  /** Cap of the run's own read cache for listed lines. */
  static final int LIST_CACHE_BYTES = 64 * 1024;

  private final DocumentServices services;

  /**
   * Creates a folder search over shared services.
   *
   * @param services the workers, indexer, search engine, block cache and budget
   */
  public FolderSearch(DocumentServices services) {
    this.services = services;
  }

  /**
   * Starts a search. Returns at once.
   *
   * @param query what to search
   * @param results receives the files; the caller closes it
   * @param listener receives progress and the end
   * @return the handle
   * @throws RejectedExecutionException after the workers close
   */
  public OperationHandle start(FolderQuery query, FolderResults results, FolderListener listener) {
    Run run = new Run(query, results, listener);
    services.workers().start(Workers.Kind.SEARCH, run);
    return run;
  }

  /** The coordinator's counters; read and written only on the coordinator thread. */
  private static final class Tally {
    long walkReserved;
    int filesDone;
    int filesTotal = -1;
    long bytesDone;
    long bytesTotal;
    Path current;
    long currentSize;
    long lastPublish;
  }

  /** A walked file and its reservation. */
  private record Candidate(Path path, long size, long reserved) {}

  private final class Run implements OperationHandle, Runnable {
    private final CancellationToken token = new CancellationToken();
    private final CountDownLatch done = new CountDownLatch(1);
    private final FolderQuery query;
    private final FolderResults results;
    private final FolderListener listener;
    private final MemoryBudget budget = services.budget();
    private final List<Candidate> candidates = new ArrayList<Candidate>();
    private final ByteBuffer head = ByteBuffer.allocate(EncodingDetector.SAMPLE_BYTES);
    private final BlockCache listCache = new BlockCache(LIST_BLOCK_BYTES, LIST_CACHE_BYTES);
    private final Indexer indexer =
        new Indexer(
            services.workers(),
            new IndexSettings(
                INDEX_BLOCK_BYTES,
                2 * Math.max(1, services.workers().cpuThreads()),
                services.budget()),
            false);
    private final DocumentServices runServices =
        new DocumentServices(
            services.workers(),
            indexer,
            services.search(),
            services.cache(),
            services.budget(),
            services.fallbackCharset(),
            services.heldLock());
    private final Tally tally = new Tally();
    private volatile int indexPercent;
    private volatile int searchPercent;

    Run(FolderQuery query, FolderResults results, FolderListener listener) {
      this.query = query;
      this.results = results;
      this.listener = listener;
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
        walk(SafePaths.logDirectory(query.folder()));
        tally.filesTotal = candidates.size();
        for (int i = 0; i < candidates.size() && !results.limited(); i++) {
          token.check();
          Candidate file = candidates.get(i);
          candidates.set(i, null);
          tally.walkReserved -= file.reserved();
          searchFile(file);
          tally.filesDone++;
          tally.bytesDone += file.size();
          tally.current = null;
          publish(false);
        }
        outcome = OperationOutcome.COMPLETED;
      } catch (CancellationException | RejectedExecutionException e) {
        outcome = OperationOutcome.CANCELED;
      } catch (InterruptedException e) {
        outcome = OperationOutcome.CANCELED;
        Thread.currentThread().interrupt();
      } catch (IOException | PatternSyntaxException e) {
        failure = e;
      } catch (UncheckedIOException e) {
        failure = e.getCause();
      } finally {
        indexer.trim();
        budget.release(MemoryShare.RESULTS, tally.walkReserved);
        tally.walkReserved = 0;
        candidates.clear();
        tally.current = null;
        publish(true);
        SearchLimit limit =
            outcome == OperationOutcome.COMPLETED && results.limited()
                ? SearchLimit.MEMORY
                : SearchLimit.NONE;
        try {
          listener.finished(outcome, limit, failure);
        } finally {
          done.countDown();
        }
      }
    }

    /** Lists the candidates; the entered folders' real paths are held until the walk ends. */
    private void walk(Path root) throws IOException {
      final Set<Path> visited = new HashSet<Path>();
      final long[] visitedReserved = new long[1];
      try {
        walkTree(root, visited, visitedReserved);
      } finally {
        budget.release(MemoryShare.RESULTS, visitedReserved[0]);
      }
    }

    private void walkTree(Path root, final Set<Path> visited, final long[] visitedReserved)
        throws IOException {
      Files.walkFileTree(
          root,
          EnumSet.noneOf(FileVisitOption.class),
          query.recursive() ? Integer.MAX_VALUE : 1,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              token.check();
              Path real = dir;
              try {
                real = dir.toRealPath();
              } catch (IOException e) {
                // The walk reports the folder when it cannot read it.
              }
              if (visited.contains(real)) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              long cost = entryBytes(real);
              if (!reserve(cost)) {
                results.setLimited();
                return FileVisitResult.TERMINATE;
              }
              visitedReserved[0] += cost;
              visited.add(real);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              token.check();
              Path name = file.getFileName();
              if (name == null || !wanted(name)) {
                return FileVisitResult.CONTINUE;
              }
              long size;
              if (attrs.isRegularFile()) {
                size = attrs.size();
              } else if (attrs.isSymbolicLink()) {
                try {
                  BasicFileAttributes target =
                      Files.readAttributes(file, BasicFileAttributes.class);
                  if (!target.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                  }
                  size = target.size();
                } catch (IOException e) {
                  return unreadable(file, e);
                }
              } else {
                return FileVisitResult.CONTINUE;
              }
              long cost = entryBytes(file);
              if (!reserve(cost)) {
                results.setLimited();
                return FileVisitResult.TERMINATE;
              }
              tally.walkReserved += cost;
              candidates.add(new Candidate(file, size, cost));
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
              token.check();
              if (failure instanceof FileSystemLoopException) {
                return FileVisitResult.CONTINUE;
              }
              Path name = file.getFileName();
              if (name != null && !wanted(name) && !Files.isDirectory(file)) {
                return FileVisitResult.CONTINUE;
              }
              return unreadable(file, failure);
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) {
              return failure == null ? FileVisitResult.CONTINUE : unreadable(dir, failure);
            }
          });
      candidates.sort(
          new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
              return a.path().compareTo(b.path());
            }
          });
      tally.bytesTotal = 0;
      for (Candidate candidate : candidates) {
        tally.bytesTotal += candidate.size();
      }
    }

    /** Reports whether a file name matches {@code names} and not {@code exclude}. */
    private boolean wanted(Path name) {
      return query.names().matches(name) && !query.exclude().excludes(name);
    }

    /** Reserves {@code RESULTS} bytes, asking the listener to free some on each refusal. */
    private boolean reserve(long cost) {
      while (!budget.reserve(MemoryShare.RESULTS, cost)) {
        if (!listener.resultsRefused()) {
          return false;
        }
      }
      return true;
    }

    /** Lists a file or folder the walk could not read; stops the walk when the share refuses. */
    private FileVisitResult unreadable(Path path, IOException failure) {
      long cost = entryBytes(path);
      if (!reserve(cost)) {
        results.setLimited();
        return FileVisitResult.TERMINATE;
      }
      results.add(unread(path, failure, cost));
      return FileVisitResult.CONTINUE;
    }

    private void searchFile(Candidate file) throws InterruptedException {
      tally.current = file.path();
      tally.currentSize = file.size();
      indexPercent = 0;
      searchPercent = 0;
      boolean handedOver = false;
      try {
        LogDocument document;
        try {
          document = LogDocument.open(file.path(), null, runServices, false);
        } catch (IOException e) {
          results.add(unread(file.path(), e, file.reserved()));
          handedOver = true;
          return;
        }
        try {
          FileHits hits = search(document, file);
          results.add(hits);
          handedOver = true;
        } finally {
          document.close();
          listCache.invalidate(document.bytes());
        }
      } finally {
        if (!handedOver) {
          budget.release(MemoryShare.RESULTS, file.reserved());
        }
      }
    }

    /** Checks, indexes and searches one open file; returns its entry. */
    private FileHits search(LogDocument document, Candidate file) throws InterruptedException {
      try {
        if (!text(document)) {
          return FileHits.skipped(file.path(), FileHits.Status.NOT_TEXT, null, file.reserved());
        }
        document.setHideAnsi(query.query().hideAnsi());
        index(document);
      } catch (IOException e) {
        return unread(file.path(), e, file.reserved());
      }
      indexPercent = 100;
      if (!document.enter()) {
        throw new CancellationException("Document closed");
      }
      MutableLineSet set = new MutableLineSet(budget);
      try {
        Ending ending = searchOnce(document, set);
        while (ending.outcome == OperationOutcome.COMPLETED
            && ending.limit == SearchLimit.MEMORY
            && listener.resultsRefused()) {
          set.clear();
          searchPercent = 0;
          ending = searchOnce(document, set);
        }
        return switch (ending.outcome) {
          case CANCELED -> throw new CancellationException("Search canceled");
          case FAILED -> failed(file, ending.failure);
          case COMPLETED -> list(document, set, file, ending.limit);
        };
      } finally {
        set.clear();
        document.exit();
      }
    }

    /** Searches an indexed file into {@code set}; returns how the search ended. */
    private Ending searchOnce(LogDocument document, MutableLineSet set)
        throws InterruptedException {
      Ending ending = new Ending();
      OperationHandle handle = services.search().search(document, query.query(), set, ending);
      try {
        while (!handle.await(SLICE_MILLIS)) {
          token.check();
          publish(false);
        }
      } finally {
        if (!handle.isDone()) {
          handle.cancel();
          handle.await(LogDocument.CLOSE_BOUND.toMillis());
        }
      }
      return ending;
    }

    private FileHits failed(Candidate file, Exception failure) {
      if (failure instanceof PatternSyntaxException syntax) {
        throw syntax;
      }
      String detail = failure == null ? null : LogText.oneLine(failure.getMessage());
      return FileHits.skipped(file.path(), FileHits.Status.FAILED, detail, file.reserved());
    }

    /** Reads the text of each matching line, reserving as it goes. */
    private FileHits list(
        LogDocument document, MutableLineSet set, Candidate file, SearchLimit limit)
        throws InterruptedException {
      boolean complete = limit == SearchLimit.NONE;
      if (limit == SearchLimit.MEMORY) {
        results.setLimited();
      }
      long matches = set.size();
      int capacity = (int) Math.min(matches, 64);
      long[] lines = new long[capacity];
      String[] texts = new String[capacity];
      int count = 0;
      long reserved = file.reserved();
      LineReader reader = document.newReader(listCache);
      LineIndex index = document.index();
      long[] batch = new long[FILL_LINES];
      long from = 0;
      try {
        listing:
        while (true) {
          int n = set.fill(from, batch);
          for (int k = 0; k < n; k++) {
            token.check();
            if (Thread.interrupted()) {
              throw new InterruptedException();
            }
            CharSequence line = reader.decodedLine(index, batch[k]);
            String text = line.subSequence(0, Math.min(line.length(), MAX_TEXT_CHARS)).toString();
            long cost = HIT_BYTES + 2L * text.length();
            if (!reserve(cost)) {
              complete = false;
              results.setLimited();
              break listing;
            }
            reserved += cost;
            if (count == lines.length) {
              int grown = Math.max(16, (int) Math.min(matches, (long) count * 2));
              lines = Arrays.copyOf(lines, grown);
              texts = Arrays.copyOf(texts, grown);
            }
            lines[count] = batch[k];
            texts[count] = text;
            count++;
          }
          if (n < batch.length) {
            break;
          }
          from = batch[n - 1] + 1;
        }
      } catch (IOException e) {
        budget.release(MemoryShare.RESULTS, reserved - file.reserved());
        return unread(file.path(), e, file.reserved());
      } catch (CancellationException | InterruptedException e) {
        budget.release(MemoryShare.RESULTS, reserved - file.reserved());
        throw e;
      }
      return FileHits.searched(file.path(), matches, lines, texts, count, complete, reserved);
    }

    /** Reads the head; false for a NUL byte in a file with a one-byte code unit. */
    private boolean text(LogDocument document) throws IOException {
      if (document.encoding().unitWidth() != 1) {
        return true;
      }
      head.clear();
      head.limit((int) Math.min(head.capacity(), document.bytes().size()));
      while (head.hasRemaining()) {
        if (document.bytes().read(head.position(), head) < 0) {
          break;
        }
      }
      for (int i = 0; i < head.position(); i++) {
        if (head.get(i) == 0) {
          return false;
        }
      }
      return true;
    }

    /** Indexes the whole file; throws when indexing fails. */
    private void index(LogDocument document) throws IOException, InterruptedException {
      final CountDownLatch indexed = new CountDownLatch(1);
      final OperationOutcome[] outcome = new OperationOutcome[1];
      final IOException[] failure = new IOException[1];
      document.addListener(
          new DocumentListener() {
            @Override
            public void indexProgressed(IndexProgress progress) {
              long total = progress.totalBytes();
              indexPercent =
                  total <= 0 ? 0 : (int) Math.min(100, progress.indexedBytes() * 100 / total);
            }

            @Override
            public void indexFinished(OperationOutcome result, IOException cause) {
              outcome[0] = result;
              failure[0] = cause;
              indexed.countDown();
            }

            @Override
            public void fileChanged(FileChange change) {
              // Not watched.
            }

            @Override
            public void closed() {
              // Closed by the search.
            }
          });
      try {
        document.startIndexing();
      } catch (IllegalStateException e) {
        throw new CancellationException("Document closed");
      }
      while (!indexed.await(SLICE_MILLIS, TimeUnit.MILLISECONDS)) {
        token.check();
        publish(false);
      }
      if (outcome[0] == OperationOutcome.CANCELED) {
        throw new CancellationException("Indexing canceled");
      }
      if (outcome[0] == OperationOutcome.FAILED) {
        throw failure[0] != null ? failure[0] : new IOException("Indexing failed");
      }
    }

    /** Calls the listener, at most every 100 ms unless forced. */
    private void publish(boolean force) {
      long now = System.nanoTime();
      if (!force && now - tally.lastPublish < PUBLISH_NANOS) {
        return;
      }
      tally.lastPublish = now;
      int percent = tally.current == null ? 0 : (indexPercent + searchPercent) / 2;
      long partial = tally.current == null ? 0 : tally.currentSize * percent / 100;
      listener.progressed(
          new FolderProgress(
              tally.filesDone,
              tally.filesTotal,
              tally.current,
              percent,
              tally.bytesDone + partial,
              tally.bytesTotal,
              results.matches()));
    }

    /** One file's search end; written on the search thread, read after the handle is done. */
    private final class Ending implements SearchListener {
      private volatile OperationOutcome outcome = OperationOutcome.CANCELED;
      private volatile SearchLimit limit = SearchLimit.NONE;
      private volatile Exception failure;

      @Override
      public void progressed(long searchedLines, long totalLines, long matchCount) {
        searchPercent =
            totalLines <= 0 ? 100 : (int) Math.min(100, searchedLines * 100 / totalLines);
      }

      @Override
      public void finished(
          OperationOutcome result, SearchLimit stop, long skippedLines, Exception cause) {
        limit = stop;
        failure = cause;
        outcome = result;
      }
    }
  }

  private static long entryBytes(Path path) {
    return ENTRY_BYTES + 2L * path.toString().length();
  }

  /** Lists a file or folder that could not be read: {@code ACCESS_DENIED} or {@code UNREADABLE}. */
  private static FileHits unread(Path path, IOException failure, long retainedBytes) {
    if (failure instanceof AccessDeniedException) {
      return FileHits.skipped(path, FileHits.Status.ACCESS_DENIED, null, retainedBytes);
    }
    return FileHits.skipped(path, FileHits.Status.UNREADABLE, reason(failure), retainedBytes);
  }

  /** One line naming why a file could not be read; never the file's content. */
  private static String reason(IOException failure) {
    if (failure instanceof FileSystemException system) {
      String reason = system.getReason();
      return LogText.oneLine(reason != null ? reason : failure.getClass().getSimpleName());
    }
    String message = failure.getMessage();
    return LogText.oneLine(message != null ? message : failure.getClass().getSimpleName());
  }
}
