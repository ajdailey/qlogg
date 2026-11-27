package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.OperationGate;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.index.GrowableLineIndex;
import io.github.ajdailey.qlogg.core.index.IndexCapacityException;
import io.github.ajdailey.qlogg.core.index.IndexListener;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.FileByteSource;
import io.github.ajdailey.qlogg.core.io.FileIdentity;
import io.github.ajdailey.qlogg.core.io.MemoryByteSource;
import io.github.ajdailey.qlogg.core.io.ReopeningByteSource;
import io.github.ajdailey.qlogg.core.text.EncodingDetection;
import io.github.ajdailey.qlogg.core.text.EncodingDetector;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.time.FormatDetector;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.watch.Baseline;
import io.github.ajdailey.qlogg.core.watch.ChangeDetector;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import io.github.ajdailey.qlogg.core.watch.FileMonitor;
import io.github.ajdailey.qlogg.core.watch.MonitorListener;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One file, or pasted text, behind a {@link LogDocument}.
 *
 * <p>Growth: the watch classifies the file at the path as given ({@link ChangeDetector}), following
 * a symbolic link, so a retargeted link reads as {@code REPLACED}. {@code APPENDED} indexes the new
 * bytes from {@link GrowableLineIndex#resumeOffset}, one operation at a time; growth seen while one
 * runs starts another when it ends. {@code TRUNCATED}, {@code REPLACED} and {@code MODIFIED} are
 * reported once and stop the checks, because the owner reopens the path. An owner whose reopen
 * fails calls {@link #reopenFailed}, which resumes the checks, so the next one reports what it
 * finds at the path and the owner reopens again. {@code MISSING} is reported once; a file that
 * reappears is reported as {@code REPLACED}. {@link #stopIndexing} cancels indexing and leaves
 * later growth unindexed. A completed or canceled pass records the head and tail digests at the
 * indexed end, so after a stopped load, growth whose last 4 KiB before the indexed end changed
 * reports {@code REPLACED}, not {@code APPENDED}.
 *
 * <p>Keep file closed: content opened with {@code keepClosed} reads through a {@link
 * ReopeningByteSource}. Nothing holds the file between reads, so a read after a rotation or a
 * delete fails instead of returning the old content. A read that finds another file makes the next
 * check report {@code REPLACED}.
 *
 * <p>In memory: {@link #ofText} holds pasted text as UTF-8 in the heap, charged to {@link
 * MemoryShare#PASTED} until {@link #close}. Such content has no path, is never watched and never
 * grows.
 */
final class SingleFileContent implements DocumentContent {
  private static final long CANCEL_SLICE_MILLIS = 50;

  private final ByteSource source;
  private final long pastedBytes;
  private final String name;
  private final EncodingDetection detection;
  private final BuiltInFormat timeFormat;
  private final GrowableLineIndex index;
  private final DocumentServices services;
  private final OperationGate gate = new OperationGate();
  private final AtomicBoolean indexStarted = new AtomicBoolean();
  private final AtomicBoolean indexRunning = new AtomicBoolean();
  private final AtomicBoolean extendWanted = new AtomicBoolean();
  private final AtomicBoolean watchStarted = new AtomicBoolean();
  private final AtomicBoolean reopenWaiting = new AtomicBoolean();
  private volatile DocumentListener events;
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

  private SingleFileContent(
      ByteSource source,
      EncodingDetection detection,
      BuiltInFormat timeFormat,
      DocumentServices services,
      String name,
      long pastedBytes)
      throws IndexCapacityException {
    this.source = source;
    this.name = name;
    this.pastedBytes = pastedBytes;
    this.detection = detection;
    this.timeFormat = timeFormat;
    this.index =
        new GrowableLineIndex(detection.encoding(), services.budget(), source, services.cache());
    this.services = services;
    FileIdentity identity = source.identity();
    this.fileBytes = identity.size();
    this.modifiedMillis = identity.modifiedMillis();
    this.head =
        new Head((int) Math.min(identity.size(), FileIdentity.HEAD_BYTES), identity.headDigest());
  }

  /**
   * Validates and opens a file, detects its encoding from the first 64 KiB and, when asked, the
   * timestamp format from the same bytes. Blocking.
   *
   * @param path the path as given
   * @param forcedEncoding the charset to use, or null to detect it
   * @param services shared services
   * @param detectTimeFormat false when the owner has a remembered format
   * @param keepClosed true to open the file only for each read
   * @return the content; the caller closes it
   * @throws IOException when the path is rejected, the file cannot be read, the forced or fallback
   *     charset has no encoder, or the line index share cannot hold an empty index
   */
  static SingleFileContent open(
      Path path,
      Charset forcedEncoding,
      DocumentServices services,
      boolean detectTimeFormat,
      boolean keepClosed)
      throws IOException {
    FileByteSource held = FileByteSource.open(path, services.heldLock());
    boolean opened = false;
    try {
      // Read through the held channel: a rotation here must not fail the open.
      ByteBuffer head = readHead(held);
      ByteSource source = keepClosed ? ReopeningByteSource.of(held) : held;
      Charset given = forcedEncoding == null ? services.fallbackCharset() : forcedEncoding;
      EncodingDetection detection;
      try {
        detection =
            forcedEncoding == null
                ? EncodingDetector.detect(head, given)
                : EncodingDetector.forced(given, head);
      } catch (IllegalArgumentException e) {
        throw new IOException("Unsupported encoding " + given.name(), e);
      }
      BuiltInFormat timeFormat =
          detectTimeFormat ? detectTimeFormat(head, detection, source.identity()) : null;
      Path file = source.path().getFileName();
      String title = file == null ? source.path().toString() : file.toString();
      SingleFileContent content =
          new SingleFileContent(source, detection, timeFormat, services, title, 0);
      opened = true;
      return content;
    } finally {
      if (!opened || keepClosed) {
        held.close();
      }
    }
  }

  /**
   * Holds text in the heap, encoded as UTF-8 and charged to {@link MemoryShare#PASTED} until {@link
   * #close}.
   *
   * @param name the name the document shows
   * @param text the text
   * @param maxBytes the largest UTF-8 size accepted
   * @param services shared services
   * @return the content; the caller closes it
   * @throws IOException when the text exceeds {@code maxBytes}, the share cannot hold it, or the
   *     line index share cannot hold an empty index
   */
  static SingleFileContent ofText(
      String name, String text, long maxBytes, DocumentServices services) throws IOException {
    if (text.length() > maxBytes) {
      throw new LogDocument.PastedTextException(text.length(), maxBytes);
    }
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > maxBytes) {
      throw new LogDocument.PastedTextException(bytes.length, maxBytes);
    }
    if (!services.budget().reserve(MemoryShare.PASTED, bytes.length)) {
      throw new LogDocument.PastedTextException(
          bytes.length, services.budget().available(MemoryShare.PASTED));
    }
    boolean opened = false;
    try {
      MemoryByteSource source = new MemoryByteSource(bytes, System.currentTimeMillis());
      ByteBuffer head = readHead(source);
      EncodingDetection detection = EncodingDetector.forced(StandardCharsets.UTF_8, head);
      BuiltInFormat timeFormat = detectTimeFormat(head, detection, source.identity());
      SingleFileContent content =
          new SingleFileContent(source, detection, timeFormat, services, name, bytes.length);
      opened = true;
      return content;
    } finally {
      if (!opened) {
        services.budget().release(MemoryShare.PASTED, bytes.length);
      }
    }
  }

  /**
   * Detects the timestamp format of the head. The zone does not change which lines parse, so UTC
   * stands in for the document zone the owner sets later.
   */
  static BuiltInFormat detectTimeFormat(
      ByteBuffer head, EncodingDetection detection, FileIdentity identity) {
    return FormatDetector.detect(
        head, detection.encoding(), TimeContext.of(ZoneOffset.UTC, identity.modifiedMillis()));
  }

  /** Reads the first {@link EncodingDetector#SAMPLE_BYTES}, or the whole source when shorter. */
  static ByteBuffer readHead(ByteSource source) throws IOException {
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

  @Override
  public String name() {
    return name;
  }

  @Override
  public Path path() {
    return source.path();
  }

  @Override
  public Path realPath() {
    return source.identity().realPath();
  }

  @Override
  public boolean inMemory() {
    return source.path() == null;
  }

  @Override
  public boolean keepsFileClosed() {
    return source instanceof ReopeningByteSource;
  }

  @Override
  public EncodingDetection detection() {
    return detection;
  }

  @Override
  public BuiltInFormat timeFormat() {
    return timeFormat;
  }

  @Override
  public ByteSource bytes() {
    return source;
  }

  @Override
  public LineIndex index() {
    return index.snapshot();
  }

  @Override
  public DocumentStatus status(boolean closed) {
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
        closed,
        fileBytes,
        modifiedMillis,
        missing,
        loadingStopped,
        reopenWaiting.get());
  }

  @Override
  public boolean enter() {
    return gate.enter();
  }

  @Override
  public void exit() {
    gate.exit();
  }

  @Override
  public void startIndexing(DocumentListener listener) {
    if (!indexStarted.compareAndSet(false, true)) {
      throw new IllegalStateException("Indexing already started or document closed");
    }
    if (!gate.enter()) {
      throw new IllegalStateException("Document closed");
    }
    events = listener;
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

  @Override
  public boolean stopIndexing() {
    loadingStopped = true;
    boolean running = indexRunning.get();
    cancel(indexing);
    return running;
  }

  @Override
  public void startWatching(FileMonitor monitor, DocumentListener listener) {
    if (inMemory() || gate.isClosed() || !watchStarted.compareAndSet(false, true)) {
      return;
    }
    events = listener;
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
    if (gate.isClosed()) {
      cancel(watching);
    }
  }

  @Override
  public boolean reopenFailed() {
    boolean first = !reopenWaiting.getAndSet(true);
    stale = false;
    return first;
  }

  @Override
  public boolean reopenWaiting() {
    return reopenWaiting.get();
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
      events.indexProgressed(latest);
    }

    @Override
    public void finished(OperationOutcome outcome, IOException failure) {
      try {
        if (outcome == OperationOutcome.COMPLETED || outcome == OperationOutcome.CANCELED) {
          recordTail();
        }
        indexRunning.set(false);
        events.indexFinished(outcome, failure);
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
      if (source instanceof ReopeningByteSource reopening) {
        reopening.expect(
            head.bytes(),
            head.digest(),
            end,
            (int) Math.min(end, ChangeDetector.TAIL_BYTES),
            tail.digest());
      }
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
      changed(withReadsSeen(ChangeDetector.classify(baseline(), path, source)));
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

  /** A read that found another file outweighs a check that found no difference. */
  private ChangeDetector.Observation withReadsSeen(ChangeDetector.Observation seen) {
    boolean quiet = seen.change() == FileChange.UNCHANGED || seen.change() == FileChange.APPENDED;
    if (quiet && source instanceof ReopeningByteSource reopening && reopening.changed()) {
      return new ChangeDetector.Observation(
          FileChange.REPLACED, seen.size(), seen.modifiedMillis());
    }
    return seen;
  }

  private void changed(ChangeDetector.Observation seen) {
    FileChange change = seen.change();
    if (change == FileChange.MISSING) {
      if (!missing) {
        missing = true;
        events.fileChanged(FileChange.MISSING);
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
        reopenWaiting.set(false);
        extendIndex();
        events.fileChanged(FileChange.APPENDED);
      }
      case TRUNCATED, REPLACED, MODIFIED -> {
        stale = true;
        events.fileChanged(change);
      }
      default -> {
        if (reopenWaiting.getAndSet(false) || info) {
          events.fileChanged(FileChange.UNCHANGED);
        }
      }
    }
  }

  @Override
  public boolean close(Duration bound) {
    gate.close();
    boolean clean = awaitOperations(bound);
    if (clean) {
      index.close();
    }
    try {
      source.close();
    } catch (IOException e) {
      clean = false;
    }
    services.cache().invalidate(source);
    services.budget().release(MemoryShare.PASTED, pastedBytes);
    return clean;
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
