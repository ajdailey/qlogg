package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.LineReader;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.RecordLines;
import io.github.ajdailey.qlogg.core.search.RecordScan;
import io.github.ajdailey.qlogg.core.search.SortedLines;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a file tab's {@link RecordScan} for a multi-line column format: one pass on a {@code
 * qlogg-index-} worker inside a document entry, reading through the indexing buffers, then a pass
 * over each growth. {@link #request} starts a pass when the index grew past the last pass's end;
 * one runs at a time, and a request during a pass runs another when it ends. Confined to the UI
 * thread, except the pass.
 *
 * <p>While a pass runs, a tick every {@link #PROGRESS_MILLIS} ms flushes the rows and shows
 * "Reading records N%". A {@code RESULTS} refusal stops the scan for good with one notice; the
 * lines from it on stay one row each. In a rotated set with a CSV or TSV header, a member's first
 * line that equals line 0 is a repeated header ({@link #headers}). {@link #detach} cancels the pass
 * and empties the record set, on the pass's thread when one runs.
 */
final class RecordScanner {
  /** Least interval between progress notices and row flushes while a pass runs. */
  static final int PROGRESS_MILLIS = 250;

  private static final Logger LOG = LoggerFactory.getLogger(RecordScanner.class);
  private static final LineSet NO_HEADERS = new SortedLines(new long[0]);

  private final DocumentController owner;
  private final ColumnSchema schema;
  private final RecordScan scan;
  private final AtomicBoolean active = new AtomicBoolean();
  private final AtomicBoolean released = new AtomicBoolean();
  private volatile boolean detached;
  private volatile LineSet headers = NO_HEADERS;
  private CancellationToken running;
  private boolean again;
  private boolean refusalShown;
  private long scannedTo = -1;

  /**
   * Creates the scanner of a tab; {@link #request} starts it.
   *
   * @param owner the file tab
   * @param schema a multi-line format's schema
   */
  RecordScanner(DocumentController owner, ColumnSchema schema) {
    this.owner = owner;
    this.schema = schema;
    LogDocument document = owner.document();
    this.scan = new RecordScan(schema, document.encoding(), owner.budget());
  }

  /** Returns the schema the scan reads records of. */
  ColumnSchema schema() {
    return schema;
  }

  /** Returns the records classified so far. */
  RecordLines snapshot() {
    return scan.snapshot();
  }

  /** Returns the lines classified so far. */
  long classifiedLines() {
    return scan.classifiedLines();
  }

  /**
   * Reports whether the records cover an index's complete lines, or never will: the scan stopped at
   * a refusal.
   */
  boolean settled(LineIndex index) {
    long complete = index.lastLineComplete() ? index.lineCount() : index.lineCount() - 1;
    return scan.refused() || scan.classifiedLines() >= complete;
  }

  /** Returns the record first lines after line 0 that repeat the header. */
  LineSet headers() {
    return headers;
  }

  /** Starts a pass over the indexed lines past the last one, unless one runs or none grew. */
  void request() {
    if (detached || scan.refused()) {
      return;
    }
    if (running != null) {
      again = true;
      return;
    }
    final LogDocument document = owner.document();
    final LineIndex index = document.index();
    if (index.indexedBytes() <= scannedTo) {
      return;
    }
    scannedTo = index.indexedBytes();
    final CancellationToken token = new CancellationToken();
    running = token;
    active.set(true);
    boolean started =
        owner
            .workers()
            .tryStart(
                Workers.Kind.INDEX,
                new Runnable() {
                  @Override
                  public void run() {
                    pass(document, index, token);
                  }
                });
    if (!started) {
      running = null;
      finished();
      return;
    }
    owner.ui().timerExec(PROGRESS_MILLIS, tick(token, index.indexedBytes()));
  }

  @WorkerThread
  private void pass(LogDocument document, LineIndex index, final CancellationToken token) {
    boolean admitted = document.enter();
    try {
      if (admitted) {
        document.scanRecords(scan, index, token);
        findHeaders(document, index);
      }
    } catch (CancellationException e) {
      // Detach or a closing document.
    } catch (IOException | UncheckedIOException e) {
      LOG.warn(
          "Reading the records of {} failed: {}",
          LogText.oneLine(document.name()),
          LogText.oneLine(e.getMessage()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      if (admitted) {
        document.exit();
      }
      finished();
      owner
          .ui()
          .asyncExec(
              new Runnable() {
                @Override
                public void run() {
                  passEnded(token);
                }
              });
    }
  }

  /** Marks the pass ended; after {@link #detach}, empties the set here. */
  private void finished() {
    active.set(false);
    if (detached) {
      release();
    }
  }

  /**
   * Collects the members' first lines that equal line 0, for a rotated set whose format has a
   * header. Reads one line per member.
   */
  @WorkerThread
  private void findHeaders(LogDocument document, LineIndex index) throws IOException {
    LineSet starts = document.memberStarts(index);
    if (!schema.header() || starts == null || starts.size() < 2) {
      return;
    }
    RecordLines records = scan.snapshot();
    LineReader reader = document.newReader();
    String header = reader.decodedLine(index, 0).toString();
    long[] found = new long[(int) starts.size()];
    int count = 0;
    for (long i = 1; i < starts.size(); i++) {
      long start = starts.select(i);
      if (start < records.end()
          && records.starts(start)
          && header.contentEquals(reader.decodedLine(index, start))) {
        found[count++] = start;
      }
    }
    headers = new SortedLines(Arrays.copyOf(found, count));
  }

  private void passEnded(CancellationToken token) {
    if (running != token) {
      return;
    }
    running = null;
    if (detached) {
      return;
    }
    owner.rowsChanged();
    if (scan.refused() && !refusalShown) {
      refusalShown = true;
      owner
          .notifications()
          .showNotice(UiText.NOTICE_RECORDS_MEMORY_LIMIT.format(scan.classifiedLines() + 1));
    }
    if (again) {
      again = false;
      request();
    }
  }

  /** Flushes the rows and shows the progress while the pass of {@code token} runs. */
  private Runnable tick(final CancellationToken token, final long totalBytes) {
    return new Runnable() {
      @Override
      public void run() {
        if (running != token || detached) {
          return;
        }
        int percent = (int) Math.min(100, scan.scannedBytes() * 100 / Math.max(1, totalBytes));
        owner.notifications().showNotice(UiText.NOTICE_RECORDS_PROGRESS.format(percent));
        owner.rowsChanged();
        owner.ui().timerExec(PROGRESS_MILLIS, this);
      }
    };
  }

  /** Cancels a running pass and empties the record set once no pass runs. */
  void detach() {
    detached = true;
    if (running != null) {
      running.cancel();
    }
    if (!active.get()) {
      release();
    }
  }

  private void release() {
    if (released.compareAndSet(false, true)) {
      scan.lines().clear();
    }
  }
}
