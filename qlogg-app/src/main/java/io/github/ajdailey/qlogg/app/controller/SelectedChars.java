package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.LineReader;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The char count of one view's selection inside one line, for the status bar: {@link #request}
 * reads the line on a {@code qlogg-read-} worker and counts {@link LineReader#chars} over the
 * selected columns, since a tab or a boxed control char spans several columns. One read runs at a
 * time; a change during it starts one more when it returns. Confined to the UI thread except for
 * the read.
 */
final class SelectedChars {
  private static final Logger LOG = LoggerFactory.getLogger(SelectedChars.class);

  private final LineViewController view;
  private final Workers workers;
  private final UiDispatcher ui;
  private Key key;
  private long count = -1;
  private boolean inFlight;
  private boolean wanted;

  /**
   * Creates the counter of a view's selection.
   *
   * @param view the view; {@link LineViewController#fireTime} reports a new count
   * @param workers runs the reads
   * @param ui the UI-thread dispatcher
   */
  SelectedChars(LineViewController view, Workers workers, UiDispatcher ui) {
    this.view = view;
    this.workers = workers;
    this.ui = ui;
  }

  /** The source line, the selected columns and the index snapshot a count covers. */
  private record Key(long line, long from, long to, LineIndex index) {}

  /**
   * Returns the char count of the view's selection, once read.
   *
   * @return the count, or -1 for no selection inside one line or before the read returns
   */
  long count() {
    Key current = current();
    return current != null && current.equals(key) && !inFlight ? count : -1;
  }

  /** Counts the selection unless the last read covered it. */
  void request() {
    if (view.isClosed()) {
      return;
    }
    final Key next = current();
    if (next == null || next.equals(key)) {
      return;
    }
    if (inFlight) {
      wanted = true;
      return;
    }
    key = next;
    count = -1;
    inFlight =
        workers.tryStart(
            Workers.Kind.READ,
            new Runnable() {
              @Override
              public void run() {
                read(next);
              }
            });
    if (!inFlight) {
      key = null;
    }
  }

  /** The selection inside one line as a key, or null. */
  private Key current() {
    Selection selection = view.selection();
    if (selection.isEmpty()
        || selection.start().line() != selection.end().line()
        || selection.end().column() == Long.MAX_VALUE) {
      return null;
    }
    LineIndex index = view.document().index();
    long line = view.sourceLine(selection.start().line());
    if (line < 0 || line >= index.lineCount()) {
      return null;
    }
    return new Key(line, selection.start().column(), selection.end().column(), index);
  }

  /** Runs on a worker; posts back on every outcome, so {@code inFlight} always clears. */
  @WorkerThread
  private void read(Key read) {
    LogDocument document = view.document();
    long result = -1;
    boolean admitted = document.enter();
    try {
      if (admitted) {
        CharSequence text = document.newReader().decodedLine(read.index(), read.line());
        result = LineReader.chars(text, read.from(), read.to());
      }
    } catch (IOException | UncheckedIOException e) {
      LOG.debug(
          "Cannot read a selected line of {}: {}",
          LogText.oneLine(document.path()),
          LogText.oneLine(e.getMessage()));
    } finally {
      if (admitted) {
        document.exit();
      }
      final long posted = result;
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              readDone(read, posted);
            }
          });
    }
  }

  private void readDone(Key read, long result) {
    inFlight = false;
    if (view.isClosed()) {
      return;
    }
    if (read.equals(key)) {
      count = result;
      view.fireTime();
    }
    if (wanted) {
      wanted = false;
      request();
    }
  }
}
