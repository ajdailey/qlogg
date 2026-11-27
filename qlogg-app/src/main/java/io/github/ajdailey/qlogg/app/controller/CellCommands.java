package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.CellReader;
import io.github.ajdailey.qlogg.core.model.CellRecord;
import io.github.ajdailey.qlogg.core.model.ColumnRead;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The column-view commands of a view that read one record: Show Record, and the current cell's
 * value for a search or quick find seed. The current cell is the cell holding the caret's column,
 * or the selection start's. Each reads on a {@code qlogg-read-} worker inside a document entry.
 * Confined to the UI thread.
 */
final class CellCommands {
  private CellCommands() {}

  /**
   * Returns a column-view copy of a view.
   *
   * @param view the view
   * @param form what each row gives
   * @return the copy with the caret's cell, or null in text view
   */
  static RecordText.Columns columns(LineViewController view, RecordText.Form form) {
    ColumnRead read = view.owner().columns().read();
    if (read == null) {
      return null;
    }
    int cell = read.layout().cellAt(view.selection().caret().column());
    return new RecordText.Columns(read, form, cell);
  }

  /**
   * Shows the caret's record in a text window ({@link RecordText#show}); nothing in text view.
   *
   * @param view the view
   */
  static void showRecord(final LineViewController view) {
    final ColumnRead read = view.owner().columns().read();
    final long line = view.sourceLine(view.selection().caret().line());
    if (read == null || line < 0) {
      return;
    }
    final LogDocument document = view.document();
    final LineIndex index = document.index();
    new BusyWork(view.ui(), view.activity()) {
      private String title;
      private String text;
      private String failure;

      @Override
      @WorkerThread
      void work() {
        if (!document.enter()) {
          return;
        }
        try {
          CellRecord record = new CellReader(document.newReader()).record(index, line, read);
          title = UiText.SHOW_RECORD_TITLE.format(record.first() + 1);
          text = RecordText.show(record);
        } catch (IOException | UncheckedIOException e) {
          failure = UiText.COPY_READ_FAILED.format(LogText.oneLine(e.getMessage()));
        } finally {
          document.exit();
        }
      }

      @Override
      void finished(boolean completed) {
        if (view.isClosed()) {
          return;
        }
        if (text != null) {
          view.owner().host().showText(title, text);
        } else if (failure != null) {
          view.notifications().showNotice(failure);
        }
      }
    }.start(view.workers(), Workers.Kind.READ);
  }

  /**
   * In column view, reads the full value of the cell at the selection start, cut at {@link
   * TextExporter#MAX_SELECTED_CHARS}, and passes it to a listener when it is not empty.
   *
   * @param view the view
   * @param listener receives the text on the UI thread
   * @return false in text view, where nothing is read
   */
  static boolean selectedCell(final LineViewController view, final TextListener listener) {
    final ColumnRead read = view.owner().columns().read();
    if (read == null) {
      return false;
    }
    final long line = view.sourceLine(view.selection().start().line());
    final int cell = read.layout().cellAt(view.selection().start().column());
    final LogDocument document = view.document();
    final LineIndex index = document.index();
    if (line < 0 || line >= index.lineCount()) {
      return true;
    }
    new BusyWork(view.ui(), view.activity()) {
      private String value;

      @Override
      @WorkerThread
      void work() {
        if (!document.enter()) {
          return;
        }
        try {
          CellRecord record = new CellReader(document.newReader()).record(index, line, read);
          CharSequence chars = RecordText.cellValue(record, read.layout(), cell);
          value =
              chars
                  .subSequence(0, Math.min(chars.length(), TextExporter.MAX_SELECTED_CHARS))
                  .toString();
        } catch (IOException | UncheckedIOException e) {
          value = null;
        } finally {
          document.exit();
        }
      }

      @Override
      void finished(boolean completed) {
        if (!view.isClosed() && value != null && !value.isEmpty()) {
          listener.textRead(value);
        }
      }
    }.start(view.workers(), Workers.Kind.READ);
    return true;
  }
}
