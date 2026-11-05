package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.view.OverviewListener;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.OverviewBins;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.time.SpreadMode;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.time.TimeSamples;
import io.github.ajdailey.qlogg.core.time.TimeSpread;
import io.github.ajdailey.qlogg.core.time.TimestampFormat;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The overview bar beside the main view: matching and marked lines counted per pixel row of the bar
 * ({@link OverviewBins}), and a click that shows the line under the pointer. The bar spreads the
 * main view's rows over its height, so a collapsed entry takes one row's share and its hidden lines
 * count in that row's pixel. {@link #refresh} maps each pixel row to its first line through {@link
 * DocumentController#sourceLine} on the UI thread, a search of the fold map that reads no file.
 * Bins are computed on a {@code qlogg-search-} worker from the shown results and the marks; at most
 * one computation runs, and a change during it starts one more when it ends. A result whose
 * generation is not the newest is dropped. The bins are charged to {@link MemoryShare#HIGHLIGHT};
 * bins the share refuses are not shown. Nothing is computed while the bar is hidden. Confined to
 * the UI thread.
 *
 * <p>With a {@link SpreadMode} other than off and a timestamp format, the same computation samples
 * a stamp at each row boundary after the bins and shades the rows ({@link TimeSpread}). The samples
 * are charged to {@link MemoryShare#HIGHLIGHT} while they are read and the levels while they are
 * kept; a refusal or a read failure shows no spread. A new height, mode, format or zone recomputes
 * at the next computation; growth or a fold change, which shifts the rows' lines, recomputes no
 * sooner than {@link #GROWTH_MILLIS} after the previous spread.
 */
public final class OverviewController {
  /** Least interval between spreads computed for growth. */
  public static final int GROWTH_MILLIS = 2000;

  private static final Logger LOG = LoggerFactory.getLogger(OverviewController.class);

  private final DocumentController owner;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MemoryBudget budget;
  private final List<OverviewListener> listeners = new ArrayList<OverviewListener>();
  private OverviewBins bins = OverviewBins.EMPTY;
  private long charged;
  private int height;
  private boolean visible;
  private boolean inFlight;
  private boolean wanted;
  private boolean closed;
  private long generation;
  private CancellationToken running;
  // Time spread.
  private SpreadMode spreadMode = SpreadMode.OFF;
  private TimeSpread spread = TimeSpread.EMPTY;
  private long spreadCharge;
  private boolean spreadStale;
  private long spreadLines = -1;
  private long spreadIndex = -1;
  private long foldChanges;
  private long spreadFolds;
  private boolean spreadWaiting;
  private long spreadWait;

  OverviewController(
      DocumentController owner, Workers workers, UiDispatcher ui, MemoryBudget budget) {
    this.owner = owner;
    this.workers = workers;
    this.ui = ui;
    this.budget = budget;
  }

  /**
   * Returns the latest bins.
   *
   * @return the bins; {@link OverviewBins#EMPTY} until the first computation ends
   */
  public OverviewBins bins() {
    return bins;
  }

  /**
   * Returns the latest time spread.
   *
   * @return the levels; {@link TimeSpread#EMPTY} when none shows
   */
  public TimeSpread spread() {
    return spread;
  }

  /**
   * Sets the time-spread mode; off drops the spread at once.
   *
   * @param mode the mode
   */
  void setSpreadMode(SpreadMode mode) {
    if (mode == spreadMode) {
      return;
    }
    spreadMode = mode;
    spreadStale = true;
    if (mode == SpreadMode.OFF) {
      dropSpread();
    }
    refresh();
  }

  /** The format or the zone changed, so every stamp reads differently. */
  void timeFormatChanged() {
    spreadStale = true;
    if (owner.time().format() == null) {
      dropSpread();
    }
    refresh();
  }

  /**
   * Adds a listener.
   *
   * @param listener called on the UI thread
   */
  public void addListener(OverviewListener listener) {
    listeners.add(listener);
  }

  /**
   * Removes a listener.
   *
   * @param listener a listener added earlier
   */
  public void removeListener(OverviewListener listener) {
    listeners.remove(listener);
  }

  /**
   * Reports the bar's size and whether it shows; a visible bar of a new height recomputes.
   *
   * @param pixels the bar's height
   * @param shown false stops computing
   */
  public void setGeometry(int pixels, boolean shown) {
    boolean changed = pixels != height || shown != visible;
    spreadStale |= pixels != height;
    height = Math.max(0, pixels);
    visible = shown;
    if (changed) {
      refresh();
    }
  }

  /**
   * Stops computing once no bar listens, as when the tab is hidden; {@link #setGeometry} starts it
   * again. A tab moved to another window has its new bar listening by then or soon after.
   */
  public void barRemoved() {
    if (listeners.isEmpty()) {
      visible = false;
    }
  }

  /**
   * Shows the line under a pixel row of the bar in the main view.
   *
   * @param y the pointer's row in the bar
   */
  public void jumpTo(int y) {
    long rows = owner.viewport().lineCount();
    if (height <= 0 || rows == 0) {
      return;
    }
    owner.showLine(owner.sourceLine(Math.min(rows - 1, OverviewBins.lineAt(y, rows, height))));
  }

  /** Recomputes after the collapsed entries changed, which moves lines between pixel rows. */
  void foldsChanged() {
    foldChanges++;
    refresh();
  }

  /** Recomputes after the results, the marks or the line count changed. */
  void refresh() {
    if (closed || !visible || height <= 0) {
      return;
    }
    if (inFlight) {
      wanted = true;
      return;
    }
    final LineSet matches = owner.search().shownResults();
    final LineSet marks = owner.marks();
    final long lineCount = owner.viewport().lineCount();
    final long[] lines = pixelLines(lineCount, height);
    final SpreadRequest sampling = spreadRequest(lineCount);
    final long started = ++generation;
    final CancellationToken token = new CancellationToken();
    inFlight =
        workers.tryStart(
            Workers.Kind.SEARCH,
            new Runnable() {
              @Override
              public void run() {
                compute(matches, marks, lines, token, started, sampling);
              }
            });
    if (inFlight) {
      running = token;
    }
  }

  /**
   * Returns each pixel row's first line, then the line after the last row: {@code {0}} without
   * rows.
   */
  private long[] pixelLines(long rows, int pixels) {
    if (rows == 0) {
      return new long[] {0};
    }
    long[] lines = new long[pixels + 1];
    for (int y = 0; y < pixels; y++) {
      lines[y] = owner.sourceLine(OverviewBins.lineAt(y, rows, pixels));
    }
    lines[pixels] = owner.endOfRow(rows - 1);
    return lines;
  }

  /** Returns what to sample in this computation, or null when the spread stands. */
  private SpreadRequest spreadRequest(long lineCount) {
    TimestampFormat format = owner.time().format();
    if (spreadMode == SpreadMode.OFF || format == null) {
      return null;
    }
    if (lineCount == 0) {
      dropSpread();
      return null;
    }
    LineIndex index = owner.document().index();
    boolean grown =
        lineCount != spreadLines || index.generation() != spreadIndex || foldChanges != spreadFolds;
    if (!spreadStale && (!grown || spreadWaiting)) {
      return null;
    }
    spreadStale = false;
    spreadLines = lineCount;
    spreadIndex = index.generation();
    spreadFolds = foldChanges;
    return new SpreadRequest(index, format, owner.time().context(), spreadMode);
  }

  /** Runs on a worker; posts back on every outcome so {@code inFlight} always clears. */
  @WorkerThread
  private void compute(
      LineSet matches,
      LineSet marks,
      long[] lines,
      CancellationToken token,
      final long started,
      final SpreadRequest sampling) {
    OverviewBins result = null;
    TimeSpread levels = null;
    boolean admitted = owner.document().enter();
    try {
      if (admitted) {
        result = OverviewBins.compute(matches, marks, lines, token);
        if (sampling != null) {
          levels = sample(sampling, lines, token);
        }
      }
    } catch (CancellationException e) {
      result = null;
    } finally {
      if (admitted) {
        owner.document().exit();
      }
      final OverviewBins done = result;
      final TimeSpread shaded = levels;
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              computed(done, started, sampling != null, shaded);
            }
          });
    }
  }

  /** Runs inside the document; a refusal or a read failure returns null. */
  @WorkerThread
  private TimeSpread sample(SpreadRequest request, long[] lines, CancellationToken token) {
    long bytes = TimeSamples.bytesFor(lines.length);
    if (!budget.reserve(MemoryShare.HIGHLIGHT, bytes)) {
      return null;
    }
    try {
      LogDocument file = owner.document();
      TimeSamples samples =
          TimeSamples.sample(
              file.newTimeReader(request.format().parser(request.context())),
              request.index(),
              lines,
              token);
      return TimeSpread.of(samples, lines, request.mode());
    } catch (IOException | UncheckedIOException e) {
      LOG.debug("Cannot read the time spread: {}", LogText.oneLine(e.getMessage()));
      return null;
    } finally {
      budget.release(MemoryShare.HIGHLIGHT, bytes);
    }
  }

  private void computed(OverviewBins result, long started, boolean sampled, TimeSpread levels) {
    inFlight = false;
    running = null;
    if (closed) {
      return;
    }
    if (result != null && started == generation) {
      budget.release(MemoryShare.HIGHLIGHT, charged);
      charged = 0;
      if (budget.reserve(MemoryShare.HIGHLIGHT, result.retainedBytes())) {
        charged = result.retainedBytes();
        bins = result;
      } else {
        bins = OverviewBins.EMPTY;
      }
      if (sampled && !spreadStale) {
        keepSpread(levels);
      }
      for (OverviewListener listener : new ArrayList<OverviewListener>(listeners)) {
        listener.binsChanged();
      }
    }
    if (sampled) {
      waitForGrowth();
    }
    if (wanted) {
      wanted = false;
      refresh();
    }
  }

  /** Replaces the spread; levels the share refuses show none. */
  private void keepSpread(TimeSpread levels) {
    budget.release(MemoryShare.HIGHLIGHT, spreadCharge);
    spreadCharge = 0;
    spread = TimeSpread.EMPTY;
    if (levels != null && budget.reserve(MemoryShare.HIGHLIGHT, levels.retainedBytes())) {
      spreadCharge = levels.retainedBytes();
      spread = levels;
    }
  }

  /** Clears the spread and repaints. */
  private void dropSpread() {
    budget.release(MemoryShare.HIGHLIGHT, spreadCharge);
    spreadCharge = 0;
    if (spread != TimeSpread.EMPTY) {
      spread = TimeSpread.EMPTY;
      for (OverviewListener listener : new ArrayList<OverviewListener>(listeners)) {
        listener.binsChanged();
      }
    }
  }

  /** Holds growth off for {@link #GROWTH_MILLIS}, then computes whatever grew meanwhile. */
  private void waitForGrowth() {
    spreadWaiting = true;
    final long waiting = ++spreadWait;
    ui.timerExec(
        GROWTH_MILLIS,
        new Runnable() {
          @Override
          public void run() {
            if (waiting == spreadWait) {
              spreadWaiting = false;
              refresh();
            }
          }
        });
  }

  /** Stops computing and returns the bins' and the spread's charges. */
  void detach() {
    closed = true;
    listeners.clear();
    if (running != null) {
      running.cancel();
    }
    budget.release(MemoryShare.HIGHLIGHT, charged);
    charged = 0;
    bins = OverviewBins.EMPTY;
    budget.release(MemoryShare.HIGHLIGHT, spreadCharge);
    spreadCharge = 0;
    spread = TimeSpread.EMPTY;
  }

  /** What one spread samples, captured on the UI thread. */
  private record SpreadRequest(
      LineIndex index, TimestampFormat format, TimeContext context, SpreadMode mode) {}
}
