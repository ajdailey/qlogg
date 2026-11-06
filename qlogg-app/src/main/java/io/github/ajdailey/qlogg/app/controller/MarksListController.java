package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.view.DocumentViewListener;
import io.github.ajdailey.qlogg.app.view.DocumentViewState;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.LineReader;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.MarkComments;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The marks list of a window: the shown document's first {@link #MAX_ROWS} marks, each with its
 * comment and the first {@link #TEXT_CHARS} chars of its line. It follows the shown tab and reads
 * again when the marks or comments change. Confined to the UI thread.
 *
 * <p>Rows are built on a {@code qlogg-read-} worker under a {@link LogDocument#enter} entry, one
 * read at a time; a change during a read starts one more when it returns, and a result for any
 * document but the listed one is dropped. Each row reserves {@link #ROW_BYTES} plus 2 bytes per
 * char from {@link MemoryShare#RESULTS} while it is listed; rows past a refusal are left out, as
 * past {@link #MAX_ROWS}.
 */
public final class MarksListController {
  /** Most rows listed. */
  public static final int MAX_ROWS = 10_000;

  /** Chars of each line's text listed. */
  public static final int TEXT_CHARS = 160;

  /** Bytes charged per row besides its chars. */
  static final long ROW_BYTES = 96;

  private static final Logger LOG = LoggerFactory.getLogger(MarksListController.class);

  /**
   * One listed mark.
   *
   * @param line the 0-based line
   * @param comment the mark's comment, or empty
   * @param text the line's first chars, control chars shown as spaces
   */
  public record Row(long line, String comment, String text) {}

  /** Receives changes on the UI thread. */
  public interface Listener {
    /** The rows, the document or the total changed. */
    void rowsChanged();
  }

  /** What a worker read. */
  record Snapshot(List<Row> rows, long total, long charged) {}

  private final ApplicationController application;
  private final Listener listener;
  private final ApplicationListener applicationListener =
      new ApplicationListener() {
        @Override
        public void documentOpened(DocumentController opened) {
          // The shown tab changes through activeDocumentChanged.
        }

        @Override
        public void documentClosed(DocumentController closed) {
          if (closed == document) {
            bind(application.activeDocument());
          }
        }

        @Override
        public void activeDocumentChanged(DocumentController shown) {
          bind(shown);
        }

        @Override
        public void documentReplaced(DocumentController closed, DocumentController replacement) {
          if (closed == document) {
            bind(replacement);
          }
        }
      };
  private final DocumentViewListener documentListener =
      new DocumentViewListener() {
        @Override
        public void stateChanged(DocumentViewState state) {
          // Rows depend on marks and comments only.
        }

        @Override
        public void contentChanged() {
          if (document != null
              && (document.document().marks().version() != marksVersion
                  || document.document().markComments().version() != commentsVersion)) {
            refresh();
          }
        }
      };
  private DocumentController document;
  private List<Row> rows = List.of();
  private long total;
  private long charged;
  private long marksVersion = -1;
  private long commentsVersion = -1;
  private CancellationToken reading;
  private boolean readInFlight;
  private boolean readWanted;
  private boolean closed;

  /**
   * Lists the shown document's marks and follows the shown tab until {@link #close}.
   *
   * @param application the window's controller
   * @param listener receives changes
   */
  public MarksListController(ApplicationController application, Listener listener) {
    this.application = application;
    this.listener = listener;
    application.addListener(applicationListener);
    bind(application.activeDocument());
  }

  /**
   * Returns the listed document.
   *
   * @return its controller, or null when no tab shows
   */
  public DocumentController document() {
    return document;
  }

  /**
   * Returns the listed rows.
   *
   * @return the rows, ascending by line
   */
  public List<Row> rows() {
    return rows;
  }

  /**
   * Returns the document's mark count as of the last read.
   *
   * @return marks, at least the row count
   */
  public long total() {
    return total;
  }

  /**
   * Reports whether a read is running.
   *
   * @return true until the rows match the marks
   */
  public boolean reading() {
    return readInFlight;
  }

  /**
   * Shows a line in the listed document's main view.
   *
   * @param line a 0-based line
   */
  public void goTo(long line) {
    if (document != null && !document.isClosed()) {
      document.showLine(line);
    }
  }

  /**
   * Removes the marks of lines and their comments.
   *
   * @param lines 0-based lines
   */
  public void remove(long[] lines) {
    if (document != null) {
      document.removeMarks(lines);
    }
  }

  /**
   * Sets the comment of a marked line; empty text removes the comment and keeps the mark.
   *
   * @param line a 0-based line
   * @param text the comment
   */
  public void setComment(long line, String text) {
    if (document != null) {
      document.setMarkComment(line, text);
    }
  }

  /** Stops following the window and releases the rows' reservation. */
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    application.removeListener(applicationListener);
    unbind();
  }

  private void bind(DocumentController shown) {
    if (closed || shown == document) {
      return;
    }
    unbind();
    document = shown != null && !shown.isClosed() ? shown : null;
    if (document != null) {
      document.addListener(documentListener);
      refresh();
    }
    listener.rowsChanged();
  }

  private void unbind() {
    if (reading != null) {
      reading.cancel();
    }
    if (document != null) {
      document.removeListener(documentListener);
      MemoryBudget budget = document.budget();
      budget.release(MemoryShare.RESULTS, charged);
    }
    document = null;
    charged = 0;
    rows = List.of();
    total = 0;
    marksVersion = -1;
    commentsVersion = -1;
  }

  private void refresh() {
    if (closed || document == null) {
      return;
    }
    if (readInFlight) {
      readWanted = true;
      return;
    }
    final DocumentController target = document;
    final LogDocument log = target.document();
    final MemoryBudget budget = target.budget();
    marksVersion = log.marks().version();
    commentsVersion = log.markComments().version();
    final CancellationToken token = new CancellationToken();
    reading = token;
    readInFlight =
        target
            .workers()
            .tryStart(
                Workers.Kind.READ,
                new Runnable() {
                  @Override
                  public void run() {
                    Snapshot snapshot = null;
                    try {
                      snapshot = read(log, budget, token);
                    } finally {
                      final Snapshot result = snapshot;
                      target
                          .ui()
                          .asyncExec(
                              new Runnable() {
                                @Override
                                public void run() {
                                  read(target, budget, result);
                                }
                              });
                    }
                  }
                });
  }

  /** Applies a worker's rows when they belong to the listed document; runs on the UI thread. */
  private void read(DocumentController target, MemoryBudget budget, Snapshot snapshot) {
    readInFlight = false;
    if (target != document || closed) {
      if (snapshot != null) {
        budget.release(MemoryShare.RESULTS, snapshot.charged());
      }
    } else if (snapshot != null) {
      budget.release(MemoryShare.RESULTS, charged);
      charged = snapshot.charged();
      rows = snapshot.rows();
      total = snapshot.total();
      listener.rowsChanged();
    }
    if (readWanted) {
      readWanted = false;
      refresh();
    }
  }

  /**
   * Lists the marks with their comments and text; null when the document closed or the token was
   * canceled, which it checks before each row.
   */
  @WorkerThread
  static Snapshot read(LogDocument log, MemoryBudget budget, CancellationToken token) {
    if (!log.enter()) {
      return null;
    }
    List<Row> out = new ArrayList<Row>();
    long reserved = 0;
    try {
      MutableLineSet marks = log.marks();
      MarkComments comments = log.markComments();
      LineIndex index = log.index();
      LineReader reader = log.newReader();
      long[] lines = new long[MAX_ROWS];
      int count = marks.fill(0, lines);
      StringBuilder text = new StringBuilder();
      boolean readable = true;
      for (int i = 0; i < count; i++) {
        if (token.isCanceled()) {
          return null;
        }
        long line = lines[i];
        String comment = comments.get(line);
        text.setLength(0);
        if (readable && line < index.lineCount()) {
          try {
            reader.appendText(index, line, 0, TEXT_CHARS, text);
          } catch (ClosedChannelException e) {
            return null;
          } catch (IOException | UncheckedIOException e) {
            readable = false;
            LOG.warn(
                "Cannot read {} for the marks list: {}",
                LogText.oneLine(log.path()),
                LogText.oneLine(e.getMessage()));
          }
        }
        Row row = new Row(line, comment == null ? "" : comment, shown(text));
        long cost = ROW_BYTES + 2L * (row.comment().length() + row.text().length());
        if (!budget.reserve(MemoryShare.RESULTS, cost)) {
          break;
        }
        reserved += cost;
        out.add(row);
      }
      long done = reserved;
      reserved = 0;
      return new Snapshot(List.copyOf(out), marks.size(), done);
    } finally {
      budget.release(MemoryShare.RESULTS, reserved);
      log.exit();
    }
  }

  /** Cuts text to {@link #TEXT_CHARS} chars with tabs and control chars as spaces. */
  private static String shown(StringBuilder text) {
    int length = Math.min(text.length(), TEXT_CHARS);
    char[] out = new char[length];
    for (int i = 0; i < length; i++) {
      char c = text.charAt(i);
      out[i] = c < 0x20 || (c >= 0x7f && c < 0xa0) ? ' ' : c;
    }
    return new String(out);
  }
}
