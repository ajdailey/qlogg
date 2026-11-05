package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.OperationGate;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.GrowableLineIndex;
import io.github.ajdailey.qlogg.core.index.IndexListener;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.FileByteSource;
import io.github.ajdailey.qlogg.core.io.FileIdentity;
import io.github.ajdailey.qlogg.core.io.MemoryByteSource;
import io.github.ajdailey.qlogg.core.search.LineSource;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.text.EncodingDetection;
import io.github.ajdailey.qlogg.core.text.EncodingDetector;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import io.github.ajdailey.qlogg.core.watch.Baseline;
import io.github.ajdailey.qlogg.core.watch.ChangeDetector;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import io.github.ajdailey.qlogg.core.watch.FileMonitor;
import io.github.ajdailey.qlogg.core.watch.MonitorListener;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * One open log file: its validated path, detected encoding, line index, background indexing and
 * file watch. {@link #open} does blocking I/O and runs on a worker; every other method except
 * {@link #close} returns without I/O. Lines are read through a {@link LineReader} from {@link
 * #newReader}. Thread-safe.
 *
 * <p>Lifecycle: {@link #open}, {@link #addListener}, {@link #startIndexing}, optionally {@link
 * #startWatching}, then {@link #close}. Every operation on the file holds an {@link OperationGate}
 * entry ({@link #enter}, {@link #exit}) while it runs: indexing, each check of the watch, and the
 * reads, searches and scans the controllers start. {@link #close} refuses new entries, cancels
 * indexing and the watch, waits for every entry to exit and for the watch to end, and only then
 * closes the file. Callers cancel their own operations first. An operation still running at the
 * bound reads a closed file and ends on {@link java.nio.channels.ClosedChannelException}.
 *
 * <p>Growth: the watch classifies the file at the path as given ({@link ChangeDetector}), following
 * a symbolic link, so a retargeted link reads as {@code REPLACED}. {@code APPENDED} indexes the new
 * bytes from {@link GrowableLineIndex#resumeOffset}, one operation at a time; growth seen while one
 * runs starts another when it ends. {@code TRUNCATED}, {@code REPLACED} and {@code MODIFIED} are
 * reported once and end the reports, because the owner reopens the path. {@code MISSING} is
 * reported once; a file that reappears is reported as {@code REPLACED}. {@link #stopIndexing}
 * cancels indexing and leaves later growth unindexed.
 *
 * <p>In memory: {@link #ofText} holds pasted text as UTF-8 in the heap, charged to {@link
 * MemoryShare#PASTED} until {@link #close}. Such a document has no path, is never watched and never
 * grows.
 */
public final class LogDocument implements LineSource, Closeable {
  /** Default bound for {@link #close()}. */
  public static final Duration CLOSE_BOUND = Duration.ofSeconds(5);

  private static final long CANCEL_SLICE_MILLIS = 50;

  /** Time {@link #closeAll} waits past the bound for the closes to report. */
  private static final long CLOSE_GRACE_MILLIS = 1000;

  private final DocumentId id = DocumentId.next();
  private volatile boolean hideAnsi;
  private final ByteSource source;
  private final long pastedBytes;
  private final String name;
  private final EncodingDetection detection;
  private final GrowableLineIndex index;
  private final DocumentServices services;
  private final List<DocumentListener> listeners = new CopyOnWriteArrayList<DocumentListener>();
  private final OperationGate gate = new OperationGate();
  private final AtomicBoolean indexStarted = new AtomicBoolean();
  private final AtomicBoolean indexRunning = new AtomicBoolean();
  private final AtomicBoolean extendWanted = new AtomicBoolean();
  private final AtomicBoolean watchStarted = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final MutableLineSet marks;
  private final MarkComments markComments;
  private volatile OperationHandle indexing;
  private volatile OperationHandle watching;
  private volatile IndexProgress progress = new IndexProgress(0, 0, 0);
  private volatile boolean loadingStopped;
  private volatile boolean stale;
  private volatile boolean missing;
  private volatile Tail tail = Tail.NONE;
  private volatile Head head;
  private volatile long fileBytes;
  private volatile long modifiedMillis;

  private LogDocument(
      ByteSource source,
      EncodingDetection detection,
      GrowableLineIndex index,
      DocumentServices services,
      String name,
      long pastedBytes) {
    this.source = source;
    this.name = name;
    this.pastedBytes = pastedBytes;
    this.detection = detection;
    this.index = index;
    this.services = services;
    this.marks = new MutableLineSet(services.budget());
    this.markComments = new MarkComments(services.budget());
    this.fileBytes = source.identity().size();
    this.modifiedMillis = source.identity().modifiedMillis();
    FileIdentity identity = source.identity();
    this.head =
        new Head((int) Math.min(identity.size(), FileIdentity.HEAD_BYTES), identity.headDigest());
  }

  /**
   * Validates and opens a file and detects its encoding from the first 64 KiB. The document keeps
   * the path as given for its name, its watch and the owner's reopen. Indexing starts with {@link
   * #startIndexing}. Blocking; never call it on the UI thread.
   *
   * @param path the path as given
   * @param forcedEncoding the charset to use, or null to detect it
   * @param services shared services
   * @return the open document; the caller closes it
   * @throws IOException when the path is rejected, the file cannot be read, or the line index share
   *     cannot hold an empty index
   */
  public static LogDocument open(Path path, Charset forcedEncoding, DocumentServices services)
      throws IOException {
    FileByteSource source = FileByteSource.open(path);
    boolean opened = false;
    try {
      ByteBuffer head = readHead(source);
      EncodingDetection detection =
          forcedEncoding == null
              ? EncodingDetector.detect(head, services.fallbackCharset())
              : EncodingDetector.forced(forcedEncoding, head);
      GrowableLineIndex index =
          new GrowableLineIndex(detection.encoding(), services.budget(), source, services.cache());
      Path file = source.path().getFileName();
      String title = file == null ? source.path().toString() : file.toString();
      LogDocument document = new LogDocument(source, detection, index, services, title, 0);
      opened = true;
      return document;
    } finally {
      if (!opened) {
        source.close();
      }
    }
  }

  /**
   * Creates a document over text held in the heap, encoded as UTF-8 and charged to {@link
   * MemoryShare#PASTED} until {@link #close}. Indexing starts with {@link #startIndexing}; {@link
   * #startWatching} does nothing.
   *
   * @param name the name the document shows, for example in its tab
   * @param text the text
   * @param maxBytes the largest UTF-8 size accepted
   * @param services shared services
   * @return the document; the caller closes it
   * @throws IOException when the text exceeds {@code maxBytes}, the share cannot hold it, or the
   *     line index share cannot hold an empty index
   */
  public static LogDocument ofText(
      String name, String text, long maxBytes, DocumentServices services) throws IOException {
    if (text.length() > maxBytes) {
      throw new PastedTextException(text.length(), maxBytes);
    }
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > maxBytes) {
      throw new PastedTextException(bytes.length, maxBytes);
    }
    if (!services.budget().reserve(MemoryShare.PASTED, bytes.length)) {
      throw new PastedTextException(bytes.length, services.budget().available(MemoryShare.PASTED));
    }
    boolean opened = false;
    try {
      MemoryByteSource source = new MemoryByteSource(bytes, System.currentTimeMillis());
      EncodingDetection detection =
          EncodingDetector.forced(StandardCharsets.UTF_8, readHead(source));
      GrowableLineIndex index =
          new GrowableLineIndex(detection.encoding(), services.budget(), source, services.cache());
      LogDocument document =
          new LogDocument(source, detection, index, services, name, bytes.length);
      opened = true;
      return document;
    } finally {
      if (!opened) {
        services.budget().release(MemoryShare.PASTED, bytes.length);
      }
    }
  }

  /** Pasted text larger than the cap or the share. */
  public static final class PastedTextException extends IOException {
    private static final long serialVersionUID = 1L;

    private final long size;
    private final long limit;

    PastedTextException(long size, long limit) {
      super("Pasted text of " + size + " bytes exceeds the limit of " + limit + " bytes");
      this.size = size;
      this.limit = limit;
    }

    /**
     * Returns the text's size.
     *
     * @return UTF-8 bytes, or chars when the text was refused before encoding
     */
    public long size() {
      return size;
    }

    /**
     * Returns the limit that refused it.
     *
     * @return bytes
     */
    public long limit() {
      return limit;
    }
  }

  private static ByteBuffer readHead(ByteSource source) throws IOException {
    int want = (int) Math.min(source.size(), EncodingDetector.SAMPLE_BYTES);
    ByteBuffer head = ByteBuffer.allocate(want);
    while (head.hasRemaining()) {
      if (source.read(head.position(), head) < 0) {
        break;
      }
    }
    head.flip();
    return head;
  }

  /**
   * Returns the identifier.
   *
   * @return the identifier
   */
  public DocumentId id() {
    return id;
  }

  /**
   * Returns the path as given, absolute and normalized. A symbolic link stays a link: the watch
   * follows it, and a reopen after a retarget opens the new target.
   *
   * @return the path, or null for a document {@link #inMemory}
   */
  public Path path() {
    return source.path();
  }

  /**
   * Returns the real path of the file this document opened.
   *
   * @return the real path at open, or null for a document {@link #inMemory}
   */
  public Path realPath() {
    return source instanceof FileByteSource file ? file.realPath() : null;
  }

  /**
   * Returns the name the document shows.
   *
   * @return the file name, or the name given to {@link #ofText}
   */
  public String name() {
    return name;
  }

  /**
   * Reports whether the content is held in the heap ({@link #ofText}).
   *
   * @return true for pasted text
   */
  public boolean inMemory() {
    return source.path() == null;
  }

  /**
   * Returns the encoding and the rule that chose it.
   *
   * @return the detection
   */
  public EncodingDetection detection() {
    return detection;
  }

  @Override
  public TextEncoding encoding() {
    return detection.encoding();
  }

  @Override
  public LineIndex index() {
    return index.snapshot();
  }

  @Override
  public ByteSource bytes() {
    return source;
  }

  /**
   * Returns the current state.
   *
   * @return the status
   */
  public DocumentStatus status() {
    IndexProgress latest = progress;
    LineIndex snapshot = index.snapshot();
    long total = latest.totalBytes();
    boolean running = indexRunning.get();
    int percent = 100;
    if (running) {
      percent = total > 0 ? (int) Math.min(100, snapshot.indexedBytes() * 100 / total) : 0;
    }
    return new DocumentStatus(
        snapshot.lineCount(),
        total,
        percent,
        running,
        snapshot.sparse(),
        closed.get(),
        fileBytes,
        modifiedMillis,
        missing,
        loadingStopped);
  }

  /**
   * Returns the marked lines, charged to the {@code RESULTS} share; {@link #close} empties them.
   *
   * @return the marks
   */
  public MutableLineSet marks() {
    return marks;
  }

  /**
   * Returns the comments of the marks, charged to the {@code RESULTS} share; {@link #close} empties
   * them.
   *
   * @return the comments
   */
  public MarkComments markComments() {
    return markComments;
  }

  /**
   * Creates a reader for this document's lines; one thread uses it at a time.
   *
   * @return the reader
   */
  public LineReader newReader() {
    return new LineReader(
        source,
        services.cache(),
        detection.encoding(),
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return hideAnsi;
          }
        });
  }

  /**
   * Reports whether readers remove ANSI color sequences.
   *
   * @return the flag
   */
  public boolean hideAnsi() {
    return hideAnsi;
  }

  /**
   * Makes every reader of this document remove ANSI color sequences, or keep them, from its next
   * decode on. Searches started later match the same text ({@link
   * io.github.ajdailey.qlogg.core.search.SearchQuery#hideAnsi}).
   *
   * @param hide true to remove them
   */
  public void setHideAnsi(boolean hide) {
    hideAnsi = hide;
  }

  /**
   * Admits an operation on the file; {@link #close} waits for it to {@link #exit}. Lock-free.
   *
   * @return false once the document is closing; the operation then does not touch the file
   */
  public boolean enter() {
    return gate.enter();
  }

  /** Ends an operation admitted by {@link #enter}. */
  public void exit() {
    gate.exit();
  }

  /**
   * Adds a listener.
   *
   * @param listener receives events on worker threads
   */
  public void addListener(DocumentListener listener) {
    listeners.add(listener);
  }

  /**
   * Removes a listener.
   *
   * @param listener a listener added earlier
   */
  public void removeListener(DocumentListener listener) {
    listeners.remove(listener);
  }

  /**
   * Starts indexing the file from the start to its current size on a worker. Returns at once.
   *
   * @throws IllegalStateException when indexing was started before or the document is closed
   * @throws RejectedExecutionException after the workers close
   */
  public void startIndexing() {
    if (closed.get() || !indexStarted.compareAndSet(false, true)) {
      throw new IllegalStateException("Indexing already started or document closed");
    }
    if (!gate.enter()) {
      throw new IllegalStateException("Document closed");
    }
    indexRunning.set(true);
    boolean started = false;
    try {
      indexing = services.indexer().index(source, index, index.resumeOffset(), new IndexEvents());
      started = true;
    } finally {
      if (!started) {
        indexRunning.set(false);
        gate.exit();
      }
    }
  }

  /**
   * Cancels indexing and leaves later growth unindexed while this document is open.
   *
   * @return true when an indexing operation was running
   */
  public boolean stopIndexing() {
    loadingStopped = true;
    boolean running = indexRunning.get();
    cancel(indexing);
    return running;
  }

  /**
   * Starts watching the path on a {@code qlogg-follow-} thread; a second call does nothing. Returns
   * at once.
   *
   * @param monitor the file monitor
   */
  public void startWatching(FileMonitor monitor) {
    if (inMemory() || closed.get() || !watchStarted.compareAndSet(false, true)) {
      return;
    }
    try {
      watching =
          monitor.watch(
              source.path(),
              new MonitorListener() {
                @Override
                public void possiblyChanged(Path path) {
                  check(path);
                }
              });
    } catch (RejectedExecutionException e) {
      return;
    }
    if (closed.get()) {
      cancel(watching);
    }
  }

  /** Indexes the bytes the file gained, now or when the running operation ends. */
  private void extendIndex() {
    extendWanted.set(true);
    tryExtend();
  }

  private void tryExtend() {
    if (loadingStopped
        || stale
        || !extendWanted.get()
        || !indexRunning.compareAndSet(false, true)) {
      return;
    }
    if (!gate.enter()) {
      indexRunning.set(false);
      return;
    }
    extendWanted.set(false);
    boolean started = false;
    try {
      indexing = services.indexer().index(source, index, index.resumeOffset(), new IndexEvents());
      started = true;
    } catch (RejectedExecutionException | IllegalStateException e) {
      // The workers are closing, or the index storage was refused: growth stays unindexed.
      loadingStopped = true;
    } finally {
      if (!started) {
        indexRunning.set(false);
        gate.exit();
      }
    }
  }

  /** One indexing operation's events; the operation holds a gate entry until its end. */
  private final class IndexEvents implements IndexListener {
    @Override
    public void progressed(IndexProgress latest) {
      progress = latest;
      for (DocumentListener listener : listeners) {
        listener.indexProgressed(latest);
      }
    }

    @Override
    public void finished(OperationOutcome outcome, IOException failure) {
      try {
        if (outcome == OperationOutcome.COMPLETED) {
          recordTail();
        }
        indexRunning.set(false);
        for (DocumentListener listener : listeners) {
          listener.indexFinished(outcome, failure);
        }
      } finally {
        gate.exit();
      }
      if (outcome == OperationOutcome.COMPLETED) {
        tryExtend();
      }
    }
  }

  /**
   * The indexed tail a {@code MODIFIED} check compares against.
   *
   * @param end the indexed bytes the digest ends at
   * @param modified the modification time taken before the digest, or -1 for none
   * @param digest CRC32C of the last bytes before {@code end}
   */
  private record Tail(long end, long modified, long digest) {
    static final Tail NONE = new Tail(-1, -1, 0);
  }

  /**
   * The head a check compares against.
   *
   * @param bytes the bytes from offset 0 the digest covers
   * @param digest CRC32C of those bytes
   */
  private record Head(int bytes, long digest) {}

  /**
   * Takes the modification time, then the digest of the indexed tail, and extends the head digest
   * over the indexed bytes until it covers {@link FileIdentity#HEAD_BYTES}.
   */
  private void recordTail() {
    if (inMemory()) {
      return;
    }
    try {
      long modified = Files.getLastModifiedTime(source.path()).toMillis();
      long end = index.snapshot().indexedBytes();
      Head recorded = head;
      if (recorded.bytes() < FileIdentity.HEAD_BYTES && end > recorded.bytes()) {
        int bytes = (int) Math.min(end, FileIdentity.HEAD_BYTES);
        head = new Head(bytes, ChangeDetector.headDigest(source, bytes));
      }
      tail = new Tail(end, modified, ChangeDetector.tailDigest(source, end));
    } catch (IOException e) {
      tail = Tail.NONE;
    }
  }

  /** Runs on the watch's thread. */
  private void check(Path path) {
    if (stale || !gate.enter()) {
      return;
    }
    try {
      changed(ChangeDetector.classify(baseline(), path));
    } catch (IOException e) {
      // Unreadable for now, for example while a writer replaces it; the next check retries.
    } finally {
      gate.exit();
    }
  }

  /**
   * While indexing runs, the covered bytes are at least the size at open: before the first progress
   * event the progress total is 0, and a check would then report the whole file as appended. The
   * tail record takes part only when it ends where the index does: an indexing pass publishes its
   * last snapshot before it records the new tail, and a check in between would otherwise compare
   * the new end of the file with the previous pass's digest and report {@code MODIFIED}.
   */
  private Baseline baseline() {
    long covered = index.snapshot().indexedBytes();
    if (indexRunning.get()) {
      covered = Math.max(covered, Math.max(progress.totalBytes(), source.identity().size()));
    }
    Tail recorded = tail;
    if (recorded.end() != covered) {
      recorded = Tail.NONE;
    }
    Head start = head;
    return new Baseline(
        source.identity(),
        covered,
        start.bytes(),
        start.digest(),
        recorded.modified(),
        recorded.digest());
  }

  private void changed(ChangeDetector.Observation seen) {
    FileChange change = seen.change();
    if (change == FileChange.MISSING) {
      if (!missing) {
        missing = true;
        fire(FileChange.MISSING);
      }
      return;
    }
    boolean info = seen.size() != fileBytes || seen.modifiedMillis() != modifiedMillis;
    fileBytes = seen.size();
    modifiedMillis = seen.modifiedMillis();
    if (missing) {
      missing = false;
      change = FileChange.REPLACED;
    }
    switch (change) {
      case APPENDED -> {
        extendIndex();
        fire(FileChange.APPENDED);
      }
      case TRUNCATED, REPLACED, MODIFIED -> {
        stale = true;
        fire(change);
      }
      default -> {
        if (info) {
          fire(FileChange.UNCHANGED);
        }
      }
    }
  }

  private void fire(FileChange change) {
    for (DocumentListener listener : listeners) {
      listener.fileChanged(change);
    }
  }

  /** Closes with {@link #CLOSE_BOUND}. */
  @Override
  public void close() {
    close(CLOSE_BOUND);
  }

  /**
   * Refuses new operations, cancels indexing and the watch, waits up to the bound for every
   * operation holding an entry to exit, then releases the index, closes the file and drops its
   * blocks from the shared {@code BlockCache}; listeners then receive {@code closed()}. Blocking;
   * never call it on the UI thread. A second call does nothing and returns true.
   *
   * @param bound the longest wait for the operations
   * @return false when an operation outlived the bound, which leaves the index reservation held, or
   *     the file failed to close
   */
  public boolean close(Duration bound) {
    if (!closed.compareAndSet(false, true)) {
      return true;
    }
    gate.close();
    boolean clean = awaitOperations(bound);
    if (clean) {
      index.close();
    }
    marks.clear();
    markComments.clear();
    try {
      source.close();
    } catch (IOException e) {
      clean = false;
    }
    services.cache().invalidate(source);
    services.budget().release(MemoryShare.PASTED, pastedBytes);
    for (DocumentListener listener : listeners) {
      listener.closed();
    }
    return clean;
  }

  /**
   * Closes documents at the same time, each on its own {@code qlogg-lifecycle-} worker with {@code
   * bound}, and waits up to {@code bound} plus one second for all of them; a document the workers
   * refuse closes on the calling thread. Blocking; never call it on the UI thread.
   *
   * @param documents the documents
   * @param workers starts the closes
   * @param bound the bound of each close
   * @return the documents whose close outlived its bound or did not end within the wait
   */
  public static List<LogDocument> closeAll(
      Collection<LogDocument> documents, Workers workers, Duration bound) {
    final List<LogDocument> late = new CopyOnWriteArrayList<LogDocument>();
    final CountDownLatch done = new CountDownLatch(documents.size());
    final List<LogDocument> pending = new CopyOnWriteArrayList<LogDocument>(documents);
    for (final LogDocument document : documents) {
      final Duration each = bound;
      Runnable close =
          new Runnable() {
            @Override
            public void run() {
              try {
                if (!document.close(each)) {
                  late.add(document);
                }
              } finally {
                pending.remove(document);
                done.countDown();
              }
            }
          };
      if (!workers.tryStart(Workers.Kind.LIFECYCLE, close)) {
        close.run();
      }
    }
    try {
      if (!done.await(bound.toMillis() + CLOSE_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
        for (LogDocument document : pending) {
          if (!late.contains(document)) {
            late.add(document);
          }
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      late.addAll(pending);
    }
    return new ArrayList<LogDocument>(late);
  }

  /** Cancels indexing and the watch each slice, so an operation started meanwhile stops too. */
  private boolean awaitOperations(Duration bound) {
    long deadline = System.nanoTime() + bound.toNanos();
    try {
      while (true) {
        cancel(indexing);
        OperationHandle watch = watching;
        cancel(watch);
        long left = (deadline - System.nanoTime()) / 1_000_000L;
        long slice = Math.max(0, Math.min(left, CANCEL_SLICE_MILLIS));
        if (gate.awaitIdle(slice) && (watch == null || watch.await(slice))) {
          return true;
        }
        if (left <= 0) {
          return false;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static void cancel(OperationHandle handle) {
    if (handle != null) {
      handle.cancel();
    }
  }
}
