package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
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
import io.github.ajdailey.qlogg.core.rotate.LineMap;
import io.github.ajdailey.qlogg.core.text.EncodingDetection;
import io.github.ajdailey.qlogg.core.text.EncodingDetector;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.time.FormatDetector;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.watch.ChangeDetector;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import io.github.ajdailey.qlogg.core.watch.FileMonitor;
import io.github.ajdailey.qlogg.core.watch.MonitorListener;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
 * later growth unindexed. A completed or canceled pass records the digests at the indexed end
 * ({@link FileCheck}), so after a stopped load, growth whose last 4 KiB before the indexed end
 * changed reports {@code REPLACED}, not {@code APPENDED}.
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
  private final ByteSource source;
  private final long pastedBytes;
  private final String name;
  private final EncodingDetection detection;
  private final BuiltInFormat timeFormat;
  private final GrowableLineIndex index;
  private final FileCheck check;
  private final DocumentServices services;
  private final ContentOperations operations = new ContentOperations();
  private final AtomicBoolean watchStarted = new AtomicBoolean();
  private volatile DocumentListener events;
  private volatile IndexProgress progress = new IndexProgress(0, 0, 0);
  private volatile boolean stale;
  private volatile boolean missing;
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
    this.check = new FileCheck(source);
    this.fileBytes = source.identity().size();
    this.modifiedMillis = source.identity().modifiedMillis();
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
  public LineMap lineMap() {
    return null;
  }

  @Override
  public DocumentStatus status(boolean closed) {
    IndexProgress latest = progress;
    LineIndex snapshot = index.snapshot();
    long total = latest.totalBytes();
    boolean running = operations.indexRunning();
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
        operations.loadingStopped(),
        operations.reopenWaiting());
  }

  @Override
  public boolean enter() {
    return operations.enter();
  }

  @Override
  public void exit() {
    operations.exit();
  }

  @Override
  public void startIndexing(DocumentListener listener) {
    events = listener;
    operations.startIndexing(pass);
  }

  @Override
  public boolean stopIndexing() {
    return operations.stopIndexing();
  }

  @Override
  public void startWatching(FileMonitor monitor, DocumentListener listener) {
    if (inMemory() || operations.isClosed() || !watchStarted.compareAndSet(false, true)) {
      return;
    }
    events = listener;
    try {
      operations.watching(
          monitor.watch(
              source.path(),
              new MonitorListener() {
                @Override
                public void possiblyChanged(Path path) {
                  check(path);
                }
              }));
    } catch (RejectedExecutionException e) {
      // The workers are closing.
    }
  }

  @Override
  public boolean reopenFailed() {
    boolean first = operations.reopenFailed();
    stale = false;
    return first;
  }

  @Override
  public boolean reopenWaiting() {
    return operations.reopenWaiting();
  }

  /** Starts a pass from the resume offset to the file's current size. */
  private final ContentOperations.Pass pass =
      new ContentOperations.Pass() {
        @Override
        public void start() {
          operations.indexing(
              services.indexer().index(source, index, index.resumeOffset(), new IndexEvents()));
        }
      };

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
        boolean ended =
            outcome == OperationOutcome.COMPLETED || outcome == OperationOutcome.CANCELED;
        if (ended && !inMemory()) {
          check.record(source.path(), index.snapshot().indexedBytes());
        }
        operations.releaseIndexing();
        events.indexFinished(outcome, failure);
      } finally {
        operations.exit();
      }
      if (outcome == OperationOutcome.COMPLETED) {
        operations.tryExtend(pass, stale);
      }
    }
  }

  /** Runs on the watch's thread. */
  private void check(Path path) {
    if (stale || !operations.enter()) {
      return;
    }
    try {
      changed(check.classify(path, covered()));
    } catch (IOException e) {
      // Unreadable for now, for example while a writer replaces it; the next check retries.
    } finally {
      operations.exit();
    }
  }

  /**
   * While indexing runs, the covered bytes are at least the size at open: before the first progress
   * event the progress total is 0, and a check would then report the whole file as appended.
   */
  private long covered() {
    long covered = index.snapshot().indexedBytes();
    if (operations.indexRunning()) {
      covered = Math.max(covered, Math.max(progress.totalBytes(), source.identity().size()));
    }
    return covered;
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
        operations.clearReopenWaiting();
        operations.extend(pass, stale);
        events.fileChanged(FileChange.APPENDED);
      }
      case TRUNCATED, REPLACED, MODIFIED -> {
        stale = true;
        events.fileChanged(change);
      }
      default -> {
        if (operations.clearReopenWaiting() || info) {
          events.fileChanged(FileChange.UNCHANGED);
        }
      }
    }
  }

  @Override
  public boolean close(Duration bound) {
    boolean clean = operations.close(bound);
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
}
