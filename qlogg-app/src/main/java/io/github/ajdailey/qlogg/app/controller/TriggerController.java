package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.LineReader;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.MarkComments;
import io.github.ajdailey.qlogg.core.model.Triggers;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies a document's keyword triggers to the complete lines appended after its first indexing
 * pass ends. A first pass, a reload and a reopen start a new controller, so they never fire
 * triggers. Confined to the UI thread.
 *
 * <p>One scan runs at a time on a {@code qlogg-search-} worker, in batches of {@link
 * Triggers#BATCH_LINES} lines, until it reaches the indexed end; each batch posts its result.
 * Growth that arrives while a scan runs is scanned when it ends. While no rule acts, growth is
 * skipped, so a rule added later acts only on lines appended after it. A mark or comment the budget
 * or {@link MarkComments#MAX_COMMENTS} refuses shows one notice per scan. Quiet lines are kept in a
 * {@link MutableLineSet} charged to {@code RESULTS} until the document closes; when the share
 * refuses them, quiet lines count toward the tab dot again for that document, with a notice.
 */
public final class TriggerController {
  /** Longest rule pattern a notice shows. */
  static final int SHOWN_PATTERN_CHARS = 60;

  private static final Logger LOG = LoggerFactory.getLogger(TriggerController.class);

  private final DocumentController owner;
  private final LogDocument document;
  private final MutableLineSet quiet;
  private final List<TriggerListener> listeners = new ArrayList<TriggerListener>();
  private List<HighlightRule> rules = List.of();
  private Triggers triggers = Triggers.NONE;
  private boolean started;
  private long scanned = -1;
  private CancellationToken running;
  private boolean refused;
  private boolean quietLost;
  private boolean detached;

  TriggerController(DocumentController owner, LogDocument document) {
    this.owner = owner;
    this.document = document;
    this.quiet = new MutableLineSet(owner.budget());
  }

  /**
   * Adds a listener.
   *
   * @param listener called on the UI thread after each batch
   */
  public void addListener(TriggerListener listener) {
    listeners.add(listener);
  }

  /**
   * Removes a listener.
   *
   * @param listener a listener added before
   */
  public void removeListener(TriggerListener listener) {
    listeners.remove(listener);
  }

  /**
   * Reports whether the tab dot waits for the scan: a quiet rule acts and its lines are tracked.
   *
   * @return true when appended lines may be quiet
   */
  public boolean defersDot() {
    return triggers.quiet() && !quietLost;
  }

  /**
   * Returns the line the next scan starts at.
   *
   * @return the 0-based line; every complete appended line before it is scanned; -1 before the
   *     first indexing pass ends
   */
  public long scannedTo() {
    return scanned;
  }

  /**
   * Reports whether a scan runs.
   *
   * @return true from the start of a scan until its end is applied
   */
  public boolean scanning() {
    return running != null;
  }

  /**
   * Reports whether a quiet rule matched a scanned line.
   *
   * @param line a 0-based line
   * @return true for a quiet line
   */
  public boolean isQuiet(long line) {
    return quiet.contains(line);
  }

  /**
   * Replaces the rules; a running scan keeps the rules it started with.
   *
   * @param priorityOrder the active sets' rules, highest priority first
   */
  void setRules(List<HighlightRule> priorityOrder) {
    if (!priorityOrder.equals(rules)) {
      rules = List.copyOf(priorityOrder);
      triggers = Triggers.of(rules);
    }
  }

  /** Marks the first indexing pass as started. */
  void started() {
    started = true;
  }

  /**
   * Takes a flush: the first one after the first pass ends sets where scans start; later ones start
   * a scan over growth.
   *
   * @param indexing true while an indexing pass runs
   */
  void flushed(boolean indexing) {
    if (detached || !started) {
      return;
    }
    long complete = completeLines(document.index());
    if (scanned < 0) {
      if (!indexing) {
        scanned = complete;
      }
      return;
    }
    if (running != null || complete <= scanned) {
      return;
    }
    if (triggers.isEmpty()) {
      scanned = complete;
      return;
    }
    start(scanned);
  }

  private static long completeLines(LineIndex index) {
    long count = index.lineCount();
    return count > 0 && !index.lastLineComplete() ? count - 1 : count;
  }

  private void start(final long from) {
    final CancellationToken token = new CancellationToken();
    final Triggers with = triggers;
    running = token;
    refused = false;
    boolean ok =
        owner
            .workers()
            .tryStart(
                Workers.Kind.SEARCH,
                new Runnable() {
                  @Override
                  public void run() {
                    scan(with, from, token);
                  }
                });
    if (!ok) {
      running = null;
    }
  }

  /** Scans from a line to the indexed end in batches; posts each batch and the end. */
  @WorkerThread
  private void scan(Triggers with, long from, final CancellationToken token) {
    if (!document.enter()) {
      postEnd(token);
      return;
    }
    try {
      LineReader reader = document.newReader();
      Path path = document.inMemory() ? null : document.path();
      long next = from;
      while (!token.isCanceled()) {
        LineIndex index = document.index();
        long end = completeLines(index);
        if (next >= end) {
          break;
        }
        final Triggers.Batch batch =
            with.scan(
                reader,
                index,
                path,
                next,
                end,
                document.marks(),
                document.markComments(),
                quiet,
                token);
        owner
            .ui()
            .asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    applied(batch, token);
                  }
                });
        next = batch.to();
      }
    } catch (ClosedChannelException e) {
      // The document closed during the scan.
    } catch (IOException | UncheckedIOException e) {
      LOG.warn(
          "Cannot read {} for highlighter triggers: {}",
          LogText.oneLine(document.path()),
          LogText.oneLine(e.getMessage()));
    } finally {
      document.exit();
      postEnd(token);
    }
  }

  private void postEnd(final CancellationToken token) {
    owner
        .ui()
        .asyncExec(
            new Runnable() {
              @Override
              public void run() {
                ended(token);
              }
            });
  }

  private void applied(Triggers.Batch batch, CancellationToken token) {
    if (token != running || detached) {
      return;
    }
    scanned = batch.to();
    for (String failure : batch.failures()) {
      LOG.warn("Highlighter comment template left unexpanded: {}", LogText.oneLine(failure));
    }
    refused |= batch.refused();
    if (batch.quietRefused() && !quietLost) {
      quietLost = true;
      quiet.clear();
      owner.notifications().showNotice(UiText.NOTICE_TRIGGER_QUIET_LIMIT.text());
    }
    if (batch.marked() > 0) {
      owner.marksChanged();
    }
    if (batch.stopRule() != null) {
      owner.followStoppedByTrigger(shown(batch.stopRule()), batch.stopLine());
    }
    for (TriggerListener listener : new ArrayList<TriggerListener>(listeners)) {
      listener.linesScanned(batch.from(), batch.to(), batch.loudLines());
    }
  }

  private void ended(CancellationToken token) {
    if (token != running) {
      return;
    }
    running = null;
    if (detached) {
      // The worker may have added quiet lines after detach.
      quiet.clear();
      return;
    }
    if (refused) {
      refused = false;
      owner
          .notifications()
          .showNotice(
              UiText.NOTICE_TRIGGER_MARKS_REFUSED.format(
                  owner.state().title(), MarkComments.MAX_COMMENTS));
    }
    flushed(false);
  }

  /** Cuts a pattern for a notice. */
  static String shown(String pattern) {
    if (pattern.length() <= SHOWN_PATTERN_CHARS) {
      return pattern;
    }
    return pattern.substring(0, SHOWN_PATTERN_CHARS - 3) + "...";
  }

  /** Cancels a running scan and releases the quiet lines. */
  void detach() {
    detached = true;
    if (running != null) {
      running.cancel();
    }
    quiet.clear();
  }
}
