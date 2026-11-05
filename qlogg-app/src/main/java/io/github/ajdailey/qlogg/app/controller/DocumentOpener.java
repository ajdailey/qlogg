package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.PositionStore;
import io.github.ajdailey.qlogg.app.config.Session;
import io.github.ajdailey.qlogg.app.config.SessionStore;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.MarkComments;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens, pastes, reopens and restores documents for one window on {@code qlogg-index-} workers and
 * hands each opened {@link LogDocument} to an {@link OpenListener} on the UI thread.
 *
 * <p>Claim protocol: the worker adds each opened document to {@code unclaimed}, then checks {@link
 * #close}'s flag and closes the document itself when it is set. The UI-side task delivers the
 * document only while the flag is clear and only when it removes it from {@code unclaimed}. After
 * {@link #close}, {@link #closeUnclaimed} on a worker closes what is left. Whichever of the worker,
 * the UI task and the sweep removes a document owns it, so a document opened after the window
 * closes is never leaked. Open and restore loops stop before their next file once closed.
 *
 * <p>Every method except {@link #closeUnclaimed} runs on the UI thread. Listener calls run on the
 * UI thread and never after {@link #close}.
 */
final class DocumentOpener {
  /** Receives the outcome of each open on the UI thread, while the window is open. */
  interface OpenListener {
    /** A file or pasted text opened; the listener owns the document and must close it. */
    void opened(LogDocument document, Opening opening);

    /** A file did not open. */
    void openFailed(String message);

    /** Pasted text did not open. */
    void pasteFailed(String message);

    /** A tab's file reopened; the listener owns the document and must close it. */
    void reopened(
        DocumentController old, LogDocument document, FileChange change, boolean marksComplete);

    /** A tab's file did not reopen. */
    void reopenFailed(DocumentController old, String message);

    /** A restored tab carries a Rename Tab name. */
    void restoredName(Path path, String name);

    /** The session's active tab opened; called after every restored tab's {@link #opened}. */
    void restoredActive(Path path);
  }

  /**
   * How an opened document is shown.
   *
   * @param line the 1-based line to go to, or 0
   * @param follow true to follow
   * @param marksCopied true when session marks were added
   * @param marksComplete false when the {@code RESULTS} share refused part of them
   * @param handed true when another launch handed the file over
   * @param timeOffset the restored tab's time offset in nanoseconds, else 0
   */
  record Opening(
      long line,
      boolean follow,
      boolean marksCopied,
      boolean marksComplete,
      boolean handed,
      long timeOffset) {}

  private static final Logger LOG = LoggerFactory.getLogger(DocumentOpener.class);

  private static final int MARK_COPY_BATCH = 4096;

  private final Workers workers;
  private final UiDispatcher ui;
  private final ConfigStore config;
  private final OpenListener listener;
  private final Set<LogDocument> unclaimed = ConcurrentHashMap.newKeySet();
  // Set on the UI thread before the close sweep; read by workers after adding to unclaimed.
  private volatile boolean closed;

  /**
   * Creates an opener.
   *
   * @param workers the thread owner
   * @param ui the UI-thread dispatcher
   * @param config the settings store; its {@code PositionStore} holds forced encodings
   * @param listener receives the opened documents
   */
  DocumentOpener(Workers workers, UiDispatcher ui, ConfigStore config, OpenListener listener) {
    this.workers = workers;
    this.ui = ui;
    this.config = config;
    this.listener = listener;
  }

  /**
   * Opens files one after another on one worker, each with its remembered forced encoding.
   *
   * @param files the files, in the order given
   * @param follow true to follow each one
   * @param handed true when another launch handed the files over
   * @param services the services the documents open with
   */
  void open(
      List<FileRequest> files,
      final boolean follow,
      final boolean handed,
      final DocumentServices services) {
    if (closed || files.isEmpty()) {
      return;
    }
    final List<FileRequest> requests = List.copyOf(files);
    boolean started =
        workers.tryStart(
            Workers.Kind.INDEX,
            new Runnable() {
              @Override
              public void run() {
                for (FileRequest request : requests) {
                  if (closed) {
                    break;
                  }
                  load(request.path(), request.line(), follow, null, null, 0, services, handed);
                }
              }
            });
    if (!started) {
      LOG.warn("Cannot open {} files: workers are closed", requests.size());
    }
  }

  /**
   * Encodes and charges text on a worker, at most {@code limit} UTF-8 bytes; a refusal reports
   * {@link OpenListener#pasteFailed} with {@link UiText#NOTICE_CLIPBOARD_TOO_LARGE}.
   *
   * @param name the tab's name
   * @param text the text
   * @param limit the largest text in UTF-8 bytes
   * @param services the services the document opens with
   */
  void paste(
      final String name, final String text, final long limit, final DocumentServices services) {
    if (closed) {
      return;
    }
    boolean started =
        workers.tryStart(
            Workers.Kind.INDEX,
            new Runnable() {
              @Override
              public void run() {
                pasted(name, text, limit, services);
              }
            });
    if (!started) {
      LOG.debug("Clipboard not opened: workers are closed");
    }
  }

  /**
   * Reopens a tab's file on a worker. A reload ({@code change} null) copies the old document's
   * marks and their comments into the new one on the worker.
   *
   * @param old the tab's controller
   * @param change the change on disk, or null for a reload
   * @param services the services the document opens with
   * @return false when no worker started, so no listener call follows
   */
  boolean reopen(
      final DocumentController old, final FileChange change, final DocumentServices services) {
    if (closed) {
      return false;
    }
    final Path path = old.document().path();
    return workers.tryStart(
        Workers.Kind.INDEX,
        new Runnable() {
          @Override
          public void run() {
            try {
              final LogDocument document = openRemembered(path, services);
              if (!register(document)) {
                return;
              }
              final boolean marksComplete =
                  change != null || copyMarksAndComments(old.document(), document);
              deliver(
                  document,
                  new Runnable() {
                    @Override
                    public void run() {
                      listener.reopened(old, document, change, marksComplete);
                    }
                  });
            } catch (IOException e) {
              final String message = LogText.oneLine(e.getMessage());
              LOG.warn("Cannot reload {}: {}", LogText.oneLine(path), message);
              post(
                  new Runnable() {
                    @Override
                    public void run() {
                      listener.reopenFailed(old, message);
                    }
                  });
            }
          }
        });
  }

  /**
   * Opens a session's tabs one after another on one worker, each with its follow state and marks,
   * then reports the names and the active tab. An entry that is not a path is logged and skipped.
   *
   * @param session the session
   * @param services the services the documents open with
   */
  void restore(Session session, final DocumentServices services) {
    if (closed || session.tabs().isEmpty()) {
      return;
    }
    final List<Path> paths = new ArrayList<Path>();
    final List<Session.Tab> tabs = new ArrayList<Session.Tab>();
    for (Session.Tab tab : session.tabs()) {
      try {
        paths.add(Path.of(tab.path()));
        tabs.add(tab);
      } catch (InvalidPathException e) {
        LOG.warn("Session entry is not a path: {}", LogText.oneLine(e.getReason()));
      }
    }
    final Session.Tab shown = session.active() >= 0 ? session.tabs().get(session.active()) : null;
    boolean started =
        workers.tryStart(
            Workers.Kind.INDEX,
            new Runnable() {
              @Override
              public void run() {
                LogDocument activeDocument = null;
                for (int i = 0; i < tabs.size() && !closed; i++) {
                  Session.Tab tab = tabs.get(i);
                  long[] marks = tab.marks().length > 0 ? tab.marks() : null;
                  LogDocument document =
                      load(
                          paths.get(i),
                          0,
                          tab.follow(),
                          marks,
                          tab.comments(),
                          tab.timeOffset(),
                          services,
                          false);
                  if (document != null && !tab.name().isEmpty()) {
                    final Path opened = document.path();
                    final String name = tab.name();
                    post(
                        new Runnable() {
                          @Override
                          public void run() {
                            listener.restoredName(opened, name);
                          }
                        });
                  }
                  if (tab == shown) {
                    activeDocument = document;
                  }
                }
                if (activeDocument != null) {
                  final Path target = activeDocument.path();
                  post(
                      new Runnable() {
                        @Override
                        public void run() {
                          listener.restoredActive(target);
                        }
                      });
                }
              }
            });
    if (!started) {
      LOG.warn("Session not restored: workers are closed");
    }
  }

  /**
   * Stops delivering documents: running opens close what they open from now on and start no further
   * file. Call on the UI thread before {@link #closeUnclaimed}.
   */
  void close() {
    closed = true;
  }

  /**
   * Reports whether {@link #close} ran.
   *
   * @return true once closed
   */
  boolean isClosed() {
    return closed;
  }

  /**
   * Closes documents a worker opened but the listener never received; runs on a worker after {@link
   * #close}.
   */
  @WorkerThread
  void closeUnclaimed() {
    for (LogDocument document : new ArrayList<LogDocument>(unclaimed)) {
      if (unclaimed.remove(document)) {
        document.close(LogDocument.CLOSE_BOUND);
      }
    }
  }

  /** Counts documents opened but not yet delivered or closed. */
  int unclaimedCount() {
    return unclaimed.size();
  }

  /**
   * Opens a file and adds session marks and their comments; runs on a worker.
   *
   * @param marks session mark ranges, or null
   * @param comments the marks' comments, or null
   * @param timeOffset the restored tab's time offset, or 0
   * @return the document, or null when it did not open or the window closed
   */
  @WorkerThread
  private LogDocument load(
      Path path,
      long line,
      boolean follow,
      long[] marks,
      List<MarkComments.Comment> comments,
      long timeOffset,
      DocumentServices services,
      boolean handed) {
    try {
      final LogDocument document = openRemembered(path, services);
      if (!register(document)) {
        return null;
      }
      boolean marksComplete = marks == null || SessionStore.addRanges(marks, document.marks());
      if (marks != null && comments != null) {
        marksComplete &= document.markComments().addAll(comments, document.marks());
      }
      final Opening opening =
          new Opening(line, follow, marks != null, marksComplete, handed, timeOffset);
      deliver(
          document,
          new Runnable() {
            @Override
            public void run() {
              listener.opened(document, opening);
            }
          });
      return document;
    } catch (IOException e) {
      final String message = LogText.oneLine(e.getMessage());
      LOG.warn("Cannot open {}: {}", LogText.oneLine(path), message);
      post(
          new Runnable() {
            @Override
            public void run() {
              listener.openFailed(message);
            }
          });
      return null;
    }
  }

  @WorkerThread
  private void pasted(String name, String text, long limit, DocumentServices services) {
    try {
      final LogDocument document = LogDocument.ofText(name, text, limit, services);
      if (!register(document)) {
        return;
      }
      final Opening opening = new Opening(0, false, false, true, false, 0);
      deliver(
          document,
          new Runnable() {
            @Override
            public void run() {
              listener.opened(document, opening);
            }
          });
    } catch (IOException e) {
      final String message =
          e instanceof LogDocument.PastedTextException refused
              ? UiText.NOTICE_CLIPBOARD_TOO_LARGE.format(
                  mebibytes(refused.size()), mebibytes(refused.limit()))
              : LogText.oneLine(e.getMessage());
      LOG.warn("Clipboard text not opened: {}", message);
      post(
          new Runnable() {
            @Override
            public void run() {
              listener.pasteFailed(message);
            }
          });
    }
  }

  /**
   * Adds an opened document to {@code unclaimed}; when the window closed meanwhile, closes it
   * instead, unless the sweep already removed it. Runs on the worker that opened it.
   *
   * @return false when the caller drops the document
   */
  @WorkerThread
  private boolean register(LogDocument document) {
    unclaimed.add(document);
    if (!closed) {
      return true;
    }
    if (unclaimed.remove(document)) {
      document.close(LogDocument.CLOSE_BOUND);
    }
    return false;
  }

  /** Posts the delivery of a document; it runs only when the UI task claims the document. */
  private void deliver(final LogDocument document, final Runnable claimed) {
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (!closed && unclaimed.remove(document)) {
              claimed.run();
            }
          }
        });
  }

  /** Posts a listener call that runs only while the window is open. */
  private void post(final Runnable call) {
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (!closed) {
              call.run();
            }
          }
        });
  }

  /**
   * Opens a file with its remembered encoding override; a remembered timestamp format skips the
   * format detection.
   */
  @WorkerThread
  private LogDocument openRemembered(Path path, DocumentServices services) throws IOException {
    PositionStore.Entry entry;
    try {
      entry = config.positions().get(SafePaths.logPath(path).given());
    } catch (IOException e) {
      entry = PositionStore.Entry.NONE;
    }
    return LogDocument.open(
        path, forcedEncoding(path, entry), services, entry.timeFormat().isEmpty());
  }

  /**
   * Returns a file's remembered encoding override, or null for detection. A remembered name this
   * runtime does not support logs a WARN naming it and gives null.
   */
  private static Charset forcedEncoding(Path path, PositionStore.Entry entry) {
    Charset charset = entry.charset();
    if (charset == null && !entry.encoding().isEmpty()) {
      LOG.warn(
          "Remembered encoding {} of {} is not supported; detecting the encoding",
          LogText.oneLine(entry.encoding()),
          LogText.oneLine(path));
    }
    return charset;
  }

  /**
   * Copies the marks, then the comments of the marks copied; false when the budget refused part.
   */
  static boolean copyMarksAndComments(LogDocument from, LogDocument to) {
    boolean marks = copyMarks(from.marks(), to.marks());
    List<MarkComments.Comment> comments = from.markComments().list(0, MarkComments.MAX_COMMENTS);
    return to.markComments().addAll(comments, to.marks()) && marks;
  }

  /**
   * Copies marks into a reopened document; runs on a worker.
   *
   * @return false when the {@code RESULTS} share refused part of them, which keeps the earlier part
   */
  static boolean copyMarks(MutableLineSet from, MutableLineSet to) {
    long[] batch = new long[MARK_COPY_BATCH];
    long next = 0;
    while (true) {
      int count = from.fill(next, batch);
      if (count == 0) {
        return true;
      }
      if (!to.addSorted(batch, count)) {
        return false;
      }
      if (count < batch.length) {
        return true;
      }
      next = batch[count - 1] + 1;
    }
  }

  private static double mebibytes(long bytes) {
    return bytes / (double) (1L << 20);
  }
}
