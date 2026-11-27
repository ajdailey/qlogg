package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.OperationGate;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.ReopeningByteSource;
import io.github.ajdailey.qlogg.core.rotate.LineMap;
import io.github.ajdailey.qlogg.core.search.LineSource;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.text.EncodingDetection;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.time.TimeReader;
import io.github.ajdailey.qlogg.core.time.TimestampParser;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import io.github.ajdailey.qlogg.core.watch.FileMonitor;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.Charset;
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
 * <p>File state, indexing and the watch sit behind {@link DocumentContent}; {@link
 * SingleFileContent} describes how one file follows growth, how a document opened with {@code
 * keepClosed} reads, and pasted text ({@link #ofText}), which has no path, is never watched and
 * never grows.
 */
public final class LogDocument implements LineSource, Closeable {
  /** Default bound for {@link #close()}. */
  public static final Duration CLOSE_BOUND = Duration.ofSeconds(5);

  /** Time {@link #closeAll} waits past the bound for the closes to report. */
  private static final long CLOSE_GRACE_MILLIS = 1000;

  private final DocumentId id = DocumentId.next();
  private volatile boolean hideAnsi;
  private final DocumentContent content;
  private final long openedModifiedMillis;
  private final DocumentServices services;
  private final List<DocumentListener> listeners = new CopyOnWriteArrayList<DocumentListener>();
  private final DocumentListener events = new Events();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final MutableLineSet marks;
  private final MarkComments markComments;

  /**
   * Creates a document over open content, which it closes with itself.
   *
   * @param content the file state
   * @param services shared services
   */
  LogDocument(DocumentContent content, DocumentServices services) {
    this.content = content;
    this.openedModifiedMillis = content.bytes().identity().modifiedMillis();
    this.services = services;
    this.marks = new MutableLineSet(services.budget());
    this.markComments = new MarkComments(services.budget());
  }

  /**
   * Opens a file as {@link #open(Path, Charset, DocumentServices, boolean)} does, detecting the
   * timestamp format.
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
    return open(path, forcedEncoding, services, true);
  }

  /**
   * Validates and opens a file and detects its encoding from the first 64 KiB, then, when asked,
   * the timestamp format from the same bytes ({@link #timeFormat}). The document keeps the path as
   * given for its name, its watch and the owner's reopen. Indexing starts with {@link
   * #startIndexing}. Blocking; never call it on the UI thread.
   *
   * @param path the path as given
   * @param forcedEncoding the charset to use, or null to detect it
   * @param services shared services
   * @param detectTimeFormat false when the owner has a remembered format
   * @return the open document; the caller closes it
   * @throws IOException when the path is rejected, the file cannot be read, or the line index share
   *     cannot hold an empty index
   */
  public static LogDocument open(
      Path path, Charset forcedEncoding, DocumentServices services, boolean detectTimeFormat)
      throws IOException {
    return open(path, forcedEncoding, services, detectTimeFormat, false);
  }

  /**
   * Opens a file as {@link #open(Path, Charset, DocumentServices, boolean)} does, and holds it for
   * as long as the document is open or only for each read.
   *
   * @param path the path as given
   * @param forcedEncoding the charset to use, or null to detect it
   * @param services shared services
   * @param detectTimeFormat false when the owner has a remembered format
   * @param keepClosed true to open the file only for each read ({@link ReopeningByteSource})
   * @return the open document; the caller closes it
   * @throws IOException when the path is rejected, the file cannot be read, the forced or fallback
   *     charset has no encoder, or the line index share cannot hold an empty index
   */
  public static LogDocument open(
      Path path,
      Charset forcedEncoding,
      DocumentServices services,
      boolean detectTimeFormat,
      boolean keepClosed)
      throws IOException {
    return new LogDocument(
        SingleFileContent.open(path, forcedEncoding, services, detectTimeFormat, keepClosed),
        services);
  }

  /**
   * Opens the rotated set a file belongs to as one document: the base file and its rotated files,
   * oldest first, in one encoding. The document shows the base name and follows the set through
   * roll-over ({@link FileChange#ROLLED}, {@link #lineMap}). Blocking; never call it on the UI
   * thread.
   *
   * @param path any member's path, or the base path
   * @param forcedEncoding the charset for every member, or null to detect it
   * @param services shared services
   * @param detectTimeFormat false when the owner has a remembered format
   * @param keepClosed true to open each member only for each read ({@link ReopeningByteSource})
   * @param ignoreCase true where the file system compares names without regard to case
   * @return the open document; the caller closes it
   * @throws IOException when the path is rejected, the series has no readable member, a member
   *     cannot be opened, or the line index share cannot hold an empty index per member
   */
  public static LogDocument openRotated(
      Path path,
      Charset forcedEncoding,
      DocumentServices services,
      boolean detectTimeFormat,
      boolean keepClosed,
      boolean ignoreCase)
      throws IOException {
    return new LogDocument(
        RotatedContent.open(
            path, forcedEncoding, services, detectTimeFormat, keepClosed, ignoreCase),
        services);
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
    return new LogDocument(SingleFileContent.ofText(name, text, maxBytes, services), services);
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
    return content.path();
  }

  /**
   * Returns the real path of the file this document opened.
   *
   * @return the real path at open, or null for a document {@link #inMemory}
   */
  public Path realPath() {
    return content.realPath();
  }

  /**
   * Reports whether the file is open only for each read.
   *
   * @return true for a document opened with {@code keepClosed}
   */
  public boolean keepsFileClosed() {
    return content.keepsFileClosed();
  }

  /**
   * Returns the name the document shows.
   *
   * @return the file name, or the name given to {@link #ofText}
   */
  public String name() {
    return content.name();
  }

  /**
   * Reports whether the content is held in the heap ({@link #ofText}).
   *
   * @return true for pasted text
   */
  public boolean inMemory() {
    return content.inMemory();
  }

  /**
   * Returns the encoding and the rule that chose it.
   *
   * @return the detection
   */
  public EncodingDetection detection() {
    return content.detection();
  }

  /**
   * Returns the timestamp format detected at open.
   *
   * @return the format, or null when detection was skipped or found none
   */
  public BuiltInFormat timeFormat() {
    return content.timeFormat();
  }

  /**
   * Returns the modification time the file had at open, for the year of timestamps without one.
   *
   * @return epoch milliseconds; the paste time for pasted text
   */
  public long openedModifiedMillis() {
    return openedModifiedMillis;
  }

  @Override
  public TextEncoding encoding() {
    return content.detection().encoding();
  }

  @Override
  public LineIndex index() {
    return content.index();
  }

  @Override
  public ByteSource bytes() {
    return content.bytes();
  }

  /**
   * Returns where the old line numbers land after the last {@link FileChange#ROLLED} report.
   *
   * @return the map, or null before one and for a single file
   */
  public LineMap lineMap() {
    return content.lineMap();
  }

  /**
   * Returns the current state.
   *
   * @return the status
   */
  public DocumentStatus status() {
    return content.status(closed.get());
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
    return newReader(services.cache());
  }

  /**
   * Creates a reader for this document's lines that reads through another cache, so a scan of many
   * lines does not evict the shared cache's blocks; one thread uses it at a time.
   *
   * @param cache the cache the reader reads through
   * @return the reader
   */
  public LineReader newReader(BlockCache cache) {
    return new LineReader(
        content.bytes(),
        cache,
        content.detection().encoding(),
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return hideAnsi;
          }
        });
  }

  /**
   * Creates a timestamp reader over this document, reading through the block cache; one per worker.
   *
   * @param parser the parser, used only by the reader
   * @return the reader
   */
  public TimeReader newTimeReader(TimestampParser parser) {
    return new TimeReader(
        content.bytes(), services.cache(), content.detection().encoding(), parser);
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
    return content.enter();
  }

  /** Ends an operation admitted by {@link #enter}. */
  public void exit() {
    content.exit();
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
    if (closed.get()) {
      throw new IllegalStateException("Indexing already started or document closed");
    }
    content.startIndexing(events);
  }

  /**
   * Cancels indexing and leaves later growth unindexed while this document is open.
   *
   * @return true when an indexing operation was running
   */
  public boolean stopIndexing() {
    return content.stopIndexing();
  }

  /**
   * Starts watching the path on a {@code qlogg-follow-} thread; a second call does nothing. Returns
   * at once.
   *
   * @param monitor the file monitor
   */
  public void startWatching(FileMonitor monitor) {
    if (!closed.get()) {
      content.startWatching(monitor, events);
    }
  }

  /**
   * Records that the owner's reopen of the path failed and resumes the checks. The next check
   * reports the change again, or {@code MISSING} once while no file is at the path. {@link
   * #reopenWaiting} holds until a reopen replaces this document or a check classifies the file as
   * {@code UNCHANGED} or {@code APPENDED}.
   *
   * @return true when this failure starts a run of failures, false when one is running
   */
  public boolean reopenFailed() {
    return content.reopenFailed();
  }

  /**
   * Reports whether a failed reopen waits for the next check.
   *
   * @return true from {@link #reopenFailed} until the run of failures ends
   */
  public boolean reopenWaiting() {
    return content.reopenWaiting();
  }

  /** Forwards the content's events to the listeners. */
  private final class Events implements DocumentListener {
    @Override
    public void indexProgressed(IndexProgress progress) {
      for (DocumentListener listener : listeners) {
        listener.indexProgressed(progress);
      }
    }

    @Override
    public void indexFinished(OperationOutcome outcome, IOException failure) {
      for (DocumentListener listener : listeners) {
        listener.indexFinished(outcome, failure);
      }
    }

    @Override
    public void fileChanged(FileChange change) {
      for (DocumentListener listener : listeners) {
        listener.fileChanged(change);
      }
    }

    @Override
    public void closed() {
      for (DocumentListener listener : listeners) {
        listener.closed();
      }
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
    boolean clean = content.close(bound);
    marks.clear();
    markComments.clear();
    events.closed();
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
}
