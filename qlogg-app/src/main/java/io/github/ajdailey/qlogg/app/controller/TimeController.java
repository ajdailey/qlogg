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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedChannelException;
import java.time.ZoneId;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One document's time state and its go to time. Confined to the UI thread and never blocks it.
 *
 * <p>The state is the timestamp format (none disables the timestamp commands), the zone of local
 * times, the display zone and the tab's offset in nanoseconds, which every time shown adds. A
 * format or zone change makes both views read their times again; an offset change only reformats
 * them.
 *
 * <p>{@link #goToTime} runs on a {@code qlogg-search-} worker inside {@link LogDocument#enter},
 * holding the {@link io.github.ajdailey.qlogg.app.view.ActivityIndicator} active until its result
 * posts. It searches the index snapshot taken at the start. An order check of {@link
 * #ORDER_SAMPLES} {@link TimeSamples}, charged to {@link MemoryShare#HIGHLIGHT} and kept per index
 * generation and format, picks {@link TimeSearch#locate}, or {@link TimeSearch#locateLinear} with
 * progress notices at most every {@link #PROGRESS_MILLIS} ms when a sample descends. A refused
 * charge skips the check and bisects. A newer request, {@code Esc} ({@link #cancel}) and {@link
 * #detach} cancel the running one, and a result whose generation is not the newest is dropped. The
 * found line turns follow off and shows through {@link DocumentController#showLine}; the filtered
 * view's caret moves to the row of that line or the next listed one.
 */
public final class TimeController {
  /** Samples of the order check. */
  public static final int ORDER_SAMPLES = 256;

  /** Least interval between progress notices of a linear scan. */
  public static final int PROGRESS_MILLIS = 250;

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
    final long current = ++generation;
    final LineIndex index = document.document().index();
    final boolean indexing = document.state().indexing();
    final TimestampFormat searched = format;
    final TimeContext searchedContext = context;
    final int searchedFormat = formatGeneration;
    final TimeSamples cached =
        samplesIndex == index.generation() && samplesFormat == formatGeneration ? samples : null;
    document.activity().setActive();
    boolean started =
        document
            .workers()
            .tryStart(
                Workers.Kind.SEARCH,
                new Runnable() {
                  @Override
                  public void run() {
                    search(
                        new Request(
                            current,
                            token,
                            index,
                            indexing,
                            searched,
                            searchedContext,
                            searchedFormat,
                            cached,
                            target));
                  }
                });
    if (!started) {
      running = null;
      document.activity().setIdle();
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
    dropSamples();
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
      long target) {}

  /**
   * The outcome of one go to time.
   *
   * @param line the found line, or -1
   * @param notice the notice to show, or null
   * @param samples order-check samples taken by this search, or null
   * @param charge bytes reserved for {@code samples}
   */
  private record Found(
      long line, UiText notice, String failure, TimeSamples samples, long charge) {}

  /** Runs on a worker; posts back on every outcome, which ends the activity. */
  @WorkerThread
  private void search(final Request request) {
    long line = -1;
    UiText notice = null;
    String failure = null;
    TimeSamples taken = null;
    long charge = 0;
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
        Stamp stamp =
            ordered
                ? TimeSearch.locate(reader, end, request.target(), request.token())
                : TimeSearch.locateLinear(
                    reader, end, request.target(), request.token(), progress(request));
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
    } catch (CancellationException | ClosedChannelException e) {
      request.token().cancel();
    } catch (IOException e) {
      failure = LogText.oneLine(e.getMessage());
    } catch (UncheckedIOException e) {
      failure = LogText.oneLine(e.getCause().getMessage());
    } finally {
      if (admitted) {
        file.exit();
      }
      final Found found = new Found(line, notice, failure, taken, charge);
      document
          .ui()
          .asyncExec(
              new Runnable() {
                @Override
                public void run() {
                  found(request, found);
                }
              });
    }
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
                    if (running == request.token() && !detached) {
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
      document.showLine(found.line());
      document.filtered().showNearest(found.line());
    }
    if (found.notice() != null) {
      document.notifications().showNotice(found.notice().text());
    }
  }
}
