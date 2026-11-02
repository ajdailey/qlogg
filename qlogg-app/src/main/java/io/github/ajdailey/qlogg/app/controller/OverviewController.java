package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.view.OverviewListener;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.model.OverviewBins;
import io.github.ajdailey.qlogg.core.search.LineSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * The overview bar beside the main view: matching and marked lines counted per pixel row of the bar
 * ({@link OverviewBins}), and a click that shows the line under the pointer. Bins are computed on a
 * {@code qlogg-search-} worker from the shown results and the marks; at most one computation runs,
 * and a change during it starts one more when it ends. A result whose generation is not the newest
 * is dropped. The bins are charged to {@link MemoryShare#HIGHLIGHT}; bins the share refuses are not
 * shown. Nothing is computed while the bar is hidden. Confined to the UI thread.
 */
public final class OverviewController {
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
    height = Math.max(0, pixels);
    visible = shown;
    if (changed) {
      refresh();
    }
  }

  /**
   * Shows the line under a pixel row of the bar in the main view.
   *
   * @param y the pointer's row in the bar
   */
  public void jumpTo(int y) {
    long count = owner.viewport().lineCount();
    if (height <= 0 || count == 0) {
      return;
    }
    owner.showLine(Math.min(count - 1, OverviewBins.lineAt(y, count, height)));
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
    final int rows = height;
    final long started = ++generation;
    final CancellationToken token = new CancellationToken();
    inFlight =
        workers.tryStart(
            Workers.Kind.SEARCH,
            new Runnable() {
              @Override
              public void run() {
                compute(matches, marks, lineCount, rows, token, started);
              }
            });
    if (inFlight) {
      running = token;
    }
  }

  /** Runs on a worker; posts back on every outcome so {@code inFlight} always clears. */
  private void compute(
      LineSet matches,
      LineSet marks,
      long lineCount,
      int rows,
      CancellationToken token,
      final long started) {
    OverviewBins result = null;
    boolean admitted = owner.document().enter();
    try {
      if (admitted) {
        result = OverviewBins.compute(matches, marks, lineCount, rows, token);
      }
    } catch (CancellationException e) {
      result = null;
    } finally {
      if (admitted) {
        owner.document().exit();
      }
      final OverviewBins done = result;
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              computed(done, started);
            }
          });
    }
  }

  private void computed(OverviewBins result, long started) {
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
      for (OverviewListener listener : new ArrayList<OverviewListener>(listeners)) {
        listener.binsChanged();
      }
    }
    if (wanted) {
      wanted = false;
      refresh();
    }
  }

  /** Stops computing and returns the bins' charge. */
  void detach() {
    closed = true;
    listeners.clear();
    if (running != null) {
      running.cancel();
    }
    budget.release(MemoryShare.HIGHLIGHT, charged);
    charged = 0;
    bins = OverviewBins.EMPTY;
  }
}
