package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.time.ScanProgress;
import io.github.ajdailey.qlogg.core.time.Stamp;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.time.TimeReader;
import io.github.ajdailey.qlogg.core.time.TimeSamples;
import io.github.ajdailey.qlogg.core.time.TimeSearch;
import io.github.ajdailey.qlogg.core.time.TimestampFormat;
import io.github.ajdailey.qlogg.core.time.TimestampParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedChannelException;
import java.time.ZoneId;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One document's time state and its go to time. Confined to the UI thread and never blocks it.
 *
 * <p>The state is the timestamp format (none disables the timestamp commands), the zone of local
 * times, the display zone and the tab's offset in nanoseconds, which every time shown adds. A
 * format or zone change makes both views read their times again and drops a running go to time; an
 * offset change only reformats them.
 *
 * <p>{@link #goToTime} runs on a {@code qlogg-search-} worker inside {@link LogDocument#enter},
 * holding the {@link io.github.ajdailey.qlogg.app.view.ActivityIndicator} active until its result
 * posts. It searches the index snapshot taken at the start. An order check of {@link
 * #ORDER_SAMPLES} {@link TimeSamples}, charged to {@link MemoryShare#HIGHLIGHT} and kept per index
 * generation and format, picks {@link TimeSearch#locate}, or {@link TimeSearch#locateLinear} with
 * progress notices at most every {@link #PROGRESS_MILLIS} ms when a sample descends. An
 * inconsistent bisection ends in the same scan and out-of-order notice. A refused charge leaves the
 * order unknown: the scan runs without that notice. A newer request, {@code Esc} ({@link #cancel})
 * and {@link #detach} cancel the running one, and a result whose generation is not the newest is
 * dropped. The found line turns follow off and shows through {@link DocumentController#showLine};
 * the filtered view's caret moves to the row of that line or the next listed one.
 *
 * <p>{@link #syncTo} is the same search for a move another tab caused: it does not report to the
 * {@link SyncListener}, shows no position or progress notice, and ends when the tab's caret moves
 * to another line first. {@link #readCaretTime} reads the caret line's time on a {@code
 * qlogg-read-} worker; a newer read drops an older one.
 */
public final class TimeController {
  /** Receives a tab's moves while it is in a time sync group. */
  interface SyncListener {
    /**
     * The caret reached another line through something other than a sync or a restored position.
     *
     * @param source the tab
     */
    void moved(DocumentController source);

    /**
     * The tab closed; it reports nothing more.
     *
     * @param document the tab
     */
    void detached(DocumentController document);
  }

  /** Receives a caret time read. */
  interface CaretTimeListener {
    /**
     * Called on the UI thread, unless a newer read or {@link #detach} came first.
     *
     * @param shown the caret line's time with the offset applied, or {@link TimestampParser#NONE}
     */
    void caretTime(long shown);
  }

  /** Samples of the order check. */
  public static final int ORDER_SAMPLES = 256;

  /** Least interval between progress notices of a linear scan. */
  public static final int PROGRESS_MILLIS = 250;

  private static final Logger LOG = LoggerFactory.getLogger(TimeController.class);

  private final DocumentController document;
  private TimestampFormat format;
  private ZoneId zone = ZoneId.systemDefault();
  private ZoneId displayZone = ZoneId.systemDefault();
  private TimeContext context;
  private long offset;
  private int formatGeneration;
  private long generation;
  private CancellationToken running;
  private boolean detached;
  // The order check of the last go to time, kept while its index generation and format stand.
  private TimeSamples samples;
  private long samplesIndex = -1;
  private int samplesFormat = -1;
  private long samplesCharge;
  // Time sync.
  private SyncListener syncListener;
  private boolean runningSync;
  private boolean quiet;
  private long caretLine;
  private long caretReads;
  private CancellationToken caretRead;

  TimeController(DocumentController document) {
    this.document = document;
    this.context = TimeContext.of(zone, document.document().openedModifiedMillis());
  }

  /**
   * Returns the timestamp format.
   *
   * @return the format, or null when the document has none
   */
  public TimestampFormat format() {
    return format;
  }

  /**
   * Returns the zone of local times.
   *
   * @return the zone
   */
  public ZoneId zone() {
    return zone;
  }

  /**
   * Returns the zone times show in.
   *
   * @return the display zone
   */
  public ZoneId displayZone() {
    return displayZone;
  }

  /**
   * Returns the tab's time offset.
   *
   * @return nanoseconds added to every time shown
   */
  public long offset() {
    return offset;
  }

  /**
   * Returns the zone and file date the parsers use.
   *
   * @return the context
   */
  TimeContext context() {
    return context;
  }

  /**
   * Returns a number that changes with the format and the zone, so cached times of an older one are
   * read again.
   *
   * @return the generation
   */
  int formatGeneration() {
    return formatGeneration;
  }

  /**
   * Sets the format and the zones; the views read their times again.
   *
   * @param chosen the format, or null for none
   * @param localZone the zone of local times
   * @param shownZone the zone times show in
   */
  void configure(TimestampFormat chosen, ZoneId localZone, ZoneId shownZone) {
    if (chosen == format && localZone.equals(zone) && shownZone.equals(displayZone)) {
      return;
    }
    boolean reread = chosen != format || !localZone.equals(zone);
    format = chosen;
    zone = localZone;
    displayZone = shownZone;
    if (reread) {
      formatGeneration++;
      context = TimeContext.of(zone, document.document().openedModifiedMillis());
      dropSamples();
      dropRunning();
    }
    document.timeChanged(reread);
  }

  /**
   * Sets the tab's time offset; 0 clears it.
   *
   * @param nanos the offset
   */
  public void setOffset(long nanos) {
    if (nanos == offset) {
      return;
    }
    offset = nanos;
    document.timeChanged(false);
    document.host().documentStateChanged();
  }

  /**
   * Goes to the first line whose time equals a target, else the last line before it, else the first
   * stamped line. Without a format a notice shows instead.
   *
   * @param shown the target as the views show times: epoch nanoseconds with the offset applied
   */
  public void goToTime(long shown) {
    if (detached) {
      return;
    }
    if (format == null) {
      document.notifications().showNotice(UiText.NOTICE_TIME_NO_FORMAT.text());
      return;
    }
    start(shown, false);
  }

  /**
   * Goes to a time another tab's move asked for. A tab without a format stays where it is.
   *
   * @param shown the target as the views show times: epoch nanoseconds with the offset applied
   */
  void syncTo(long shown) {
    if (!detached && format != null) {
      start(shown, true);
    }
  }

  private void start(long shown, final boolean sync) {
    final long target;
    try {
      target = Math.subtractExact(shown, offset);
    } catch (ArithmeticException e) {
      return;
    }
    if (running != null) {
      running.cancel();
    }
    final CancellationToken token = new CancellationToken();
    running = token;
    runningSync = sync;
    final long current = ++generation;
    final LineIndex index = document.document().index();
    final boolean indexing = document.state().indexing();
    final TimestampFormat searched = format;
    final TimeContext searchedContext = context;
    final int searchedFormat = formatGeneration;
    final TimeSamples cached =
        samplesIndex == index.generation() && samplesFormat == formatGeneration ? samples : null;
    final Request request =
        new Request(
            current,
            token,
            index,
            indexing,
            searched,
            searchedContext,
            searchedFormat,
            cached,
            target,
            sync);
    boolean started =
        new BusyWork(document.ui(), document.activity()) {
          private Found result = Found.NONE;

          @Override
          @WorkerThread
          void work() {
            result = search(request);
          }

          @Override
          void finished(boolean completed) {
            found(request, result);
          }
        }.start(document.workers(), Workers.Kind.SEARCH);
    if (!started) {
      running = null;
    }
  }

  /** Cancels a running go to time; {@code Esc} calls it. */
  public void cancel() {
    if (running != null) {
      running.cancel();
    }
  }

  /** Cancels a running go to time, drops later results and releases the order check. */
  void detach() {
    detached = true;
    cancel();
    if (caretRead != null) {
      caretRead.cancel();
    }
    dropSamples();
    SyncListener listener = syncListener;
    syncListener = null;
    if (listener != null) {
      listener.detached(document);
    }
  }

  // Time sync.

  /**
   * Sets who hears of this tab's moves.
   *
   * @param listener the listener, or null
   */
  void setSyncListener(SyncListener listener) {
    syncListener = listener;
  }

  /**
   * Sets whether caret moves stay unreported, for a position restored rather than chosen.
   *
   * @param on true until the move ends
   */
  void setQuiet(boolean on) {
    quiet = on;
  }

  /** The main view's caret may have moved: a move to another line ends a running sync move. */
  void caretMoved() {
    long line = document.sourceLine(document.selection().caret().line());
    if (line == caretLine) {
      return;
    }
    caretLine = line;
    if (quiet) {
      return;
    }
    cancelSync();
    if (syncListener != null) {
      syncListener.moved(document);
    }
  }

  /** Cancels a running sync move without a notice. */
  void cancelSync() {
    if (runningSync) {
      dropRunning();
    }
  }

  /** Cancels a running go to time; its result posts and changes nothing. */
  private void dropRunning() {
    if (running != null) {
      running.cancel();
      generation++;
    }
  }

  /**
   * Reads the caret line's time on a worker.
   *
   * @param listener receives the time; without a format or a line, at once
   */
  void readCaretTime(final CaretTimeListener listener) {
    if (detached) {
      return;
    }
    if (caretRead != null) {
      caretRead.cancel();
      caretRead = null;
    }
    final long current = ++caretReads;
    final LineIndex index = document.document().index();
    final long line = document.sourceLine(document.selection().caret().line());
    if (format == null || line < 0 || line >= index.lineCount()) {
      listener.caretTime(TimestampParser.NONE);
      return;
    }
    final CancellationToken token = new CancellationToken();
    caretRead = token;
    final TimestampFormat read = format;
    final TimeContext readContext = context;
    boolean started =
        document
            .workers()
            .tryStart(
                Workers.Kind.READ,
                new Runnable() {
                  @Override
                  public void run() {
                    readCaret(current, token, index, line, read, readContext, listener);
                  }
                });
    if (!started) {
      caretRead = null;
    }
  }

  /** Runs on a worker; posts back on every outcome. */
  @WorkerThread
  private void readCaret(
      final long current,
      CancellationToken token,
      LineIndex index,
      long line,
      TimestampFormat read,
      TimeContext readContext,
      final CaretTimeListener listener) {
    long nanos = TimestampParser.NONE;
    LogDocument file = document.document();
    boolean admitted = file.enter();
    try {
      if (admitted) {
        TimeReader reader = file.newTimeReader(read.parser(readContext));
        Stamp stamp = reader.at(index.lineStart(line), token);
        if (stamp != null) {
          nanos = stamp.nanos();
        }
      }
    } catch (CancellationException | ClosedChannelException e) {
      nanos = TimestampParser.NONE;
    } catch (IOException | UncheckedIOException e) {
      nanos = TimestampParser.NONE;
      LOG.debug(
          "Cannot read the caret time of {}: {}",
          LogText.oneLine(file.path()),
          LogText.oneLine(e.getMessage()));
    } finally {
      if (admitted) {
        file.exit();
      }
      final long found = nanos;
      document
          .ui()
          .asyncExec(
              new Runnable() {
                @Override
                public void run() {
                  caretTimeRead(current, found, listener);
                }
              });
    }
  }

  private void caretTimeRead(long current, long nanos, CaretTimeListener listener) {
    if (detached || current != caretReads) {
      return;
    }
    caretRead = null;
    long shown = TimestampParser.NONE;
    if (nanos != TimestampParser.NONE) {
      try {
        shown = Math.addExact(nanos, offset);
      } catch (ArithmeticException e) {
        shown = TimestampParser.NONE;
      }
    }
    listener.caretTime(shown);
  }

  /**
   * Reports whether a go to time runs.
   *
   * @return true until its result posts
   */
  public boolean running() {
    return running != null;
  }

  private void dropSamples() {
    document.budget().release(MemoryShare.HIGHLIGHT, samplesCharge);
    samples = null;
    samplesCharge = 0;
    samplesIndex = -1;
    samplesFormat = -1;
  }

  /** What one go to time searches, captured on the UI thread. */
  private record Request(
      long generation,
      CancellationToken token,
      LineIndex index,
      boolean indexing,
      TimestampFormat format,
      TimeContext context,
      int formatGeneration,
      TimeSamples cached,
      long target,
      boolean sync) {}

  /**
   * The outcome of one go to time.
   *
   * @param line the found line, or -1
   * @param notice the notice to show, or null
   * @param samples order-check samples taken by this search, or null
   * @param charge bytes reserved for {@code samples}
   */
  private record Found(long line, UiText notice, String failure, TimeSamples samples, long charge) {
    /** Nothing found and nothing reserved: also the result of a search that threw. */
    static final Found NONE = new Found(-1, null, null, null, 0);
  }

  /**
   * Searches for the request's time. A search that throws releases its samples' reservation first.
   *
   * @return what the search found
   */
  @WorkerThread
  private Found search(Request request) {
    long line = -1;
    UiText notice = null;
    String failure = null;
    TimeSamples taken = null;
    long charge = 0;
    boolean ended = false;
    LogDocument file = document.document();
    boolean admitted = file.enter();
    try {
      if (admitted) {
        TimeReader reader = file.newTimeReader(request.format().parser(request.context()));
        LineIndex index = request.index();
        long end = index.indexedBytes();
        TimeSamples order = request.cached();
        if (order == null && index.lineCount() > 0) {
          long bytes = TimeSamples.bytesFor(ORDER_SAMPLES);
          if (document.budget().reserve(MemoryShare.HIGHLIGHT, bytes)) {
            charge = bytes;
            order =
                TimeSamples.sample(
                    reader, index, index.lineCount(), ORDER_SAMPLES, request.token());
            taken = order;
          }
        }
        boolean ordered = order == null || order.descents() == 0;
        boolean scan = !ordered || order == null && index.lineCount() > 0;
        Stamp stamp = null;
        if (!scan) {
          TimeSearch.Bisection bisection =
              TimeSearch.locate(reader, end, request.target(), request.token());
          stamp = bisection.stamp();
          ordered = bisection.consistent();
          scan = !ordered;
        }
        if (scan) {
          stamp =
              TimeSearch.locateLinear(
                  reader, end, request.target(), request.token(), progress(request));
        }
        if (stamp == null) {
          notice = UiText.NOTICE_TIME_NONE;
        } else {
          line = index.lineAt(stamp.offset());
          if (!ordered) {
            notice = UiText.NOTICE_TIME_OUT_OF_ORDER;
          } else if (stamp.nanos() > request.target()) {
            notice = UiText.NOTICE_TIME_BEFORE_FIRST;
          } else if (request.indexing()
              && stamp.nanos() < request.target()
              && reader.next(stamp.offset() + 1, end, request.token()) == null) {
            notice = UiText.NOTICE_TIME_INDEXED_PART;
          }
        }
      }
      ended = true;
    } catch (CancellationException | ClosedChannelException e) {
      request.token().cancel();
      ended = true;
    } catch (IOException e) {
      failure = LogText.oneLine(e.getMessage());
      ended = true;
    } catch (UncheckedIOException e) {
      failure = LogText.oneLine(e.getCause().getMessage());
      ended = true;
    } finally {
      if (admitted) {
        file.exit();
      }
      if (!ended) {
        document.budget().release(MemoryShare.HIGHLIGHT, charge);
      }
    }
    return new Found(line, notice, failure, taken, charge);
  }

  /** Posts a progress notice at most every {@link #PROGRESS_MILLIS} ms. */
  private ScanProgress progress(final Request request) {
    final AtomicLong last = new AtomicLong(System.nanoTime());
    return new ScanProgress() {
      @Override
      public void scanned(long position, long end) {
        long now = System.nanoTime();
        long before = last.get();
        if (now - before < PROGRESS_MILLIS * 1_000_000L || !last.compareAndSet(before, now)) {
          return;
        }
        final int percent = (int) (position * 100 / Math.max(1, end));
        document
            .ui()
            .asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    if (running == request.token() && !detached && !request.sync()) {
                      document
                          .notifications()
                          .showNotice(UiText.NOTICE_TIME_PROGRESS.format(percent));
                    }
                  }
                });
      }
    };
  }

  private void found(Request request, Found found) {
    document.activity().setIdle();
    if (running == request.token()) {
      running = null;
    }
    boolean current = !detached && request.generation() == generation;
    if (found.samples() != null) {
      if (current && request.formatGeneration() == formatGeneration) {
        dropSamples();
        samples = found.samples();
        samplesCharge = found.charge();
        samplesIndex = request.index().generation();
        samplesFormat = request.formatGeneration();
      } else {
        document.budget().release(MemoryShare.HIGHLIGHT, found.charge());
      }
    } else {
      document.budget().release(MemoryShare.HIGHLIGHT, found.charge());
    }
    if (!current) {
      return;
    }
    if (request.token().isCanceled()) {
      document.notifications().showNotice(UiText.NOTICE_TIME_CANCELED.text());
      return;
    }
    if (found.failure() != null) {
      document.notifications().showNotice(UiText.NOTICE_TIME_FAILED.format(found.failure()));
      return;
    }
    if (found.line() >= 0) {
      document.setFollowing(false);
      quiet = request.sync();
      if (!quiet) {
        document.recordJump();
      }
      document.showLine(found.line());
      document.filtered().showNearest(found.line());
      quiet = false;
    }
    if (found.notice() != null && !request.sync()) {
      document.notifications().showNotice(found.notice().text());
    }
  }
}
