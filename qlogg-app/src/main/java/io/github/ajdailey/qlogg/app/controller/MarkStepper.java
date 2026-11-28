package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.view.ActivityIndicator;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import java.util.concurrent.CancellationException;

/**
 * Runs a tab's next and previous mark steps ({@link MarkSteps}). A step in a tab with every line a
 * row, or over at most {@link MarkSteps#WORKER_MARKS} marks ({@link #setBound}), runs on the UI
 * thread. A larger one runs {@link MarkSteps#stable} on the CPU pool as a {@link BusyWork}, holding
 * the rows' reader ({@link RowMap#readerStarted}) so a growth waits for it, and applies its line on
 * the UI thread. A new request cancels a running step and replaces it; {@link #cancel} drops the
 * result. Confined to the UI thread.
 */
final class MarkStepper {
  /** Applies a step's line. */
  interface Target {
    /**
     * Applies a step's line on the UI thread.
     *
     * @param forward true for a next-mark step
     * @param line the mark, or -1 for none
     */
    void stepped(boolean forward, long line);
  }

  private final LogDocument document;
  private final RowMap rows;
  private final Workers workers;
  private final UiDispatcher ui;
  private final ActivityIndicator activity;
  private final Target target;
  private long bound = MarkSteps.WORKER_MARKS;
  private CancellationToken running;

  /**
   * Creates the stepper.
   *
   * @param document the marks' document
   * @param rows the tab's rows
   * @param workers runs a step over many marks
   * @param ui runs its end
   * @param activity shows it while it runs
   * @param target applies each step's line
   */
  MarkStepper(
      LogDocument document,
      RowMap rows,
      Workers workers,
      UiDispatcher ui,
      ActivityIndicator activity,
      Target target) {
    this.document = document;
    this.rows = rows;
    this.workers = workers;
    this.ui = ui;
    this.activity = activity;
    this.target = target;
  }

  /**
   * Sets the mark count above which a step runs on a worker.
   *
   * @param marks at least 0
   */
  void setBound(long marks) {
    bound = marks;
  }

  /** Reports whether a step runs on a worker. */
  boolean running() {
    return running != null;
  }

  /**
   * Steps from a line: to the first mark after it, or the last mark before it.
   *
   * @param caret the caret's 0-based line
   * @param forward true for the next mark
   */
  void request(long caret, final boolean forward) {
    cancel();
    if (!forward && caret == 0) {
      target.stepped(false, -1);
      return;
    }
    final long from = forward ? caret + 1 : caret - 1;
    final MutableLineSet marks = document.marks();
    final LineSet members = rows.members();
    if (members == null || marks.size() <= bound) {
      target.stepped(
          forward,
          forward
              ? MarkSteps.next(marks, members, from)
              : MarkSteps.previous(marks, members, from));
      return;
    }
    final CancellationToken token = new CancellationToken();
    running = token;
    rows.readerStarted();
    boolean started =
        new BusyWork(ui, activity) {
          private long line = -1;

          @Override
          @WorkerThread
          void work() {
            if (!document.enter()) {
              token.cancel();
              return;
            }
            try {
              line = MarkSteps.stable(marks, members, from, forward, token);
            } catch (CancellationException e) {
              // Replaced or canceled; nothing applies.
            } finally {
              document.exit();
            }
          }

          @Override
          void finished(boolean completed) {
            ended(token, completed, forward, line);
          }
        }.startCpu(workers);
    if (!started) {
      running = null;
      rows.readerEnded();
    }
  }

  private void ended(CancellationToken token, boolean completed, boolean forward, long line) {
    rows.readerEnded();
    if (running != token) {
      return;
    }
    running = null;
    if (completed && !token.isCanceled()) {
      target.stepped(forward, line);
    }
  }

  /** Cancels a running step; its result does not apply. */
  void cancel() {
    if (running != null) {
      running.cancel();
      running = null;
    }
  }
}
