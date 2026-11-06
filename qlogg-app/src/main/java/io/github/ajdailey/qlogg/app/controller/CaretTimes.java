package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.view.TimeStatus;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.time.Stamp;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.time.TimeReader;
import io.github.ajdailey.qlogg.core.time.TimestampFormat;
import io.github.ajdailey.qlogg.core.time.TimestampParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One view's caret time and selection times. With a timestamp format ({@link
 * DocumentController#time}), {@link #request} reads the caret line's time and, for a selection over
 * more than one row, the times of its first and last rows' lines on a {@code qlogg-read-} worker
 * through {@link TimeReader#at}, unless the last read covered the same lines. One read runs at a
 * time; a change during it starts one more when it returns. Confined to the UI thread except for
 * the read.
 */
final class CaretTimes {
  private static final Logger LOG = LoggerFactory.getLogger(CaretTimes.class);

  private final LineViewController view;
  private final Workers workers;
  private final UiDispatcher ui;
  private final CancellationToken reads = new CancellationToken();
  private TimeStatus status = TimeStatus.NONE;
  private Key key;
  private boolean inFlight;
  private boolean wanted;

  /**
   * Creates the reader of a view's times.
   *
   * @param view the view; {@link LineViewController#fireTime} reports a change
   * @param workers runs the reads
   * @param ui the UI-thread dispatcher
   */
  CaretTimes(LineViewController view, Workers workers, UiDispatcher ui) {
    this.view = view;
    this.workers = workers;
    this.ui = ui;
  }

  /** The lines a time read covers and the format generation it parsed with. */
  private record Key(long caret, long first, long last, int format) {}

  /** The times as last read; {@link TimeStatus#NONE} without a format. */
  TimeStatus status() {
    return status;
  }

  /** Cancels the running read. */
  void cancel() {
    reads.cancel();
  }

  /** Reads the times again, even for the same lines. */
  void reread() {
    key = null;
    request();
  }

  /** Reads the times unless the last read covered the same lines; see the class comment. */
  void request() {
    if (view.isClosed()) {
      return;
    }
    TimeController time = view.owner().time();
    if (time.format() == null) {
      if (key != null || status != TimeStatus.NONE) {
        key = null;
        status = TimeStatus.NONE;
        view.fireTime();
      }
      return;
    }
    LineIndex index = view.document().index();
    long count = index.lineCount();
    Selection selection = view.selection();
    long caret = view.sourceLine(selection.caret().line());
    if (caret < 0 || caret >= count) {
      return;
    }
    long first = -1;
    long last = -1;
    if (!selection.isEmpty() && selection.start().line() != selection.end().line()) {
      first = view.sourceLine(selection.start().line());
      last = view.sourceLine(selection.end().line());
      if (first < 0 || last < 0 || last >= count) {
        first = -1;
        last = -1;
      }
    }
    final Key next = new Key(caret, first, last, time.formatGeneration());
    if (next.equals(key)) {
      return;
    }
    if (inFlight) {
      wanted = true;
      return;
    }
    key = next;
    final LineIndex snapshot = index;
    final TimestampFormat format = time.format();
    final TimeContext context = time.context();
    inFlight =
        workers.tryStart(
            Workers.Kind.READ,
            new Runnable() {
              @Override
              public void run() {
                read(snapshot, next, format, context);
              }
            });
  }

  /** Runs on a worker; posts back on every outcome, so {@code inFlight} always clears. */
  @WorkerThread
  private void read(LineIndex index, Key read, TimestampFormat format, TimeContext context) {
    LogDocument document = view.document();
    TimeStatus result = null;
    boolean admitted = document.enter();
    try {
      if (admitted) {
        TimeReader reader = document.newTimeReader(format.parser(context));
        Stamp caret = reader.at(index.lineStart(read.caret()), reads);
        Stamp first = null;
        Stamp last = null;
        if (read.first() >= 0) {
          first = reader.at(index.lineStart(read.first()), reads);
          last = reader.at(index.lineStart(read.last()), reads);
        }
        result =
            new TimeStatus(
                caret == null ? TimestampParser.NONE : caret.nanos(),
                caret == null ? 0 : caret.fractionDigits(),
                first == null ? TimestampParser.NONE : first.nanos(),
                last == null ? TimestampParser.NONE : last.nanos(),
                Math.max(
                    first == null ? 0 : first.fractionDigits(),
                    last == null ? 0 : last.fractionDigits()));
      }
    } catch (CancellationException e) {
      result = null;
    } catch (IOException | UncheckedIOException e) {
      LOG.debug(
          "Cannot read a time of {}: {}",
          LogText.oneLine(document.path()),
          LogText.oneLine(e.getMessage()));
      result = TimeStatus.NONE;
    } finally {
      if (admitted) {
        document.exit();
      }
      final TimeStatus posted = result;
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              readDone(read, posted);
            }
          });
    }
  }

  private void readDone(Key read, TimeStatus result) {
    inFlight = false;
    if (view.isClosed()) {
      return;
    }
    if (result != null && read.equals(key) && !result.equals(status)) {
      status = result;
      view.fireTime();
    }
    if (wanted) {
      wanted = false;
      request();
    }
  }
}
