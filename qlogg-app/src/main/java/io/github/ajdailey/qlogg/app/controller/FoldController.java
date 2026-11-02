package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.FoldClassifier;
import io.github.ajdailey.qlogg.core.model.FoldMap;
import io.github.ajdailey.qlogg.core.model.FoldRules;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CancellationException;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Folding of one document's main view: which entries are collapsed ({@link FoldMap}) and the
 * classification that finds them. Confined to the UI thread.
 *
 * <p>Entry bounds come from {@link FoldClassifier} on a {@code qlogg-read-} worker inside a
 * document entry, one classification at a time, only for the region the view shows. {@link #toggle}
 * classifies around one line and collapses its entry. With collapse-all on, each change of the
 * visible lines classifies the visible rows plus one screen above and below that are not classified
 * yet and collapses every entry found; an entry the user expanded stays expanded until the next
 * {@link #collapseAll}. A result from before {@link #expandAll}, {@link #collapseAll} or {@link
 * #setRules} is dropped. A toggle while a classification runs waits for it and then runs; a later
 * toggle replaces a waiting one, and expand-all or collapse-all drops it. When {@link
 * io.github.ajdailey.qlogg.core.concurrent.MemoryShare#FOLDS} refuses an entry it stays expanded,
 * and a notice shows once per document.
 */
final class FoldController {
  private static final Logger LOG = LoggerFactory.getLogger(FoldController.class);

  private final DocumentController owner;
  private final FoldMap map;
  private FoldRules rules = FoldRules.DEFAULTS;
  private boolean collapseAll;
  private long classifiedFrom = -1;
  private long classifiedTo = -1;
  private CancellationToken running;
  private boolean wanted;
  // Toggle pressed during classification.
  private long queuedToggle = -1;
  private long generation;
  private boolean limitShown;
  private boolean closed;

  FoldController(DocumentController owner, FoldMap map) {
    this.owner = owner;
    this.map = map;
  }

  /** Replaces the rules and expands every entry. */
  void setRules(FoldRules next) {
    if (next.equals(rules)) {
      return;
    }
    rules = next;
    expandAll();
  }

  /** Reports whether entries collapse as they are shown. */
  boolean collapsingAll() {
    return collapseAll;
  }

  /**
   * Expands the collapsed entry holding a line, or collapses the entry holding it once a worker has
   * found its bounds; a line in no multi-line entry shows a notice.
   */
  void toggle(long line) {
    if (closed || line < 0) {
      return;
    }
    long[] entry = map.collapsedEntry(line);
    if (entry != null) {
      final long start = entry[0];
      owner.changeFolds(
          new Runnable() {
            @Override
            public void run() {
              map.expand(start);
            }
          });
      return;
    }
    classify(line, line + 1, line);
  }

  /** Collapses every entry of the visible region now and of each region shown later. */
  void collapseAll() {
    cancel();
    collapseAll = true;
    classifiedFrom = -1;
    classifiedTo = -1;
    owner.foldsMayNeedClassifying();
  }

  /** Expands every entry and stops collapsing new ones. */
  void expandAll() {
    cancel();
    collapseAll = false;
    classifiedFrom = -1;
    classifiedTo = -1;
    owner.changeFolds(
        new Runnable() {
          @Override
          public void run() {
            map.clear();
          }
        });
  }

  /**
   * With collapse-all on, classifies the part of the region around the visible lines not classified
   * yet. The classified lines are one range that grows toward each region shown; a region away from
   * it starts a new range.
   */
  void visible(long firstLine, long lastLine, int rows) {
    if (!collapseAll || closed || firstLine < 0) {
      return;
    }
    if (running != null) {
      wanted = true;
      return;
    }
    long from = Math.max(0, firstLine - rows);
    long to = lastLine + 1 + rows;
    if (classifiedFrom >= 0 && from >= classifiedFrom && to <= classifiedTo) {
      return;
    }
    if (classifiedFrom >= 0 && to >= classifiedFrom && from <= classifiedTo) {
      if (from < classifiedFrom) {
        to = classifiedFrom;
      } else {
        from = classifiedTo;
      }
    }
    classify(from, to, -1);
  }

  /** Stops classifying and drops later results; the map's charge is returned by the owner. */
  void detach() {
    closed = true;
    cancel();
    map.clear();
  }

  private void cancel() {
    generation++;
    wanted = false;
    queuedToggle = -1;
    if (running != null) {
      running.cancel();
      running = null;
    }
  }

  /** Classifies {@code [from, to)} on a worker; {@code line >= 0} marks a toggle of that line. */
  private void classify(final long from, final long to, final long line) {
    if (running != null) {
      if (line < 0) {
        wanted = true;
      } else {
        queuedToggle = line;
      }
      return;
    }
    final LogDocument document = owner.document();
    final LineIndex index = document.index();
    final FoldRules used = rules;
    final CancellationToken token = new CancellationToken();
    final long started = generation;
    running = token;
    boolean ok =
        owner
            .workers()
            .tryStart(
                Workers.Kind.READ,
                new Runnable() {
                  @Override
                  public void run() {
                    work(document, index, used, from, to, line, token, started);
                  }
                });
    if (!ok) {
      running = null;
    }
  }

  /** Runs on a worker; posts back on every outcome, so {@code running} always clears. */
  private void work(
      LogDocument document,
      LineIndex index,
      FoldRules used,
      long from,
      long to,
      long line,
      CancellationToken token,
      long started) {
    long[] entries = null;
    String error = null;
    boolean admitted = document.enter();
    try {
      if (admitted) {
        entries =
            FoldClassifier.entries(document.newReader(), index, used.compile(), from, to, token);
      }
    } catch (CancellationException | ClosedChannelException e) {
      entries = null;
    } catch (PatternSyntaxException e) {
      error = UiText.NOTICE_FOLD_RULES.format(LogText.oneLine(e.getDescription()));
    } catch (IOException | UncheckedIOException e) {
      LOG.warn(
          "Cannot read {}: {}", LogText.oneLine(document.path()), LogText.oneLine(e.getMessage()));
    } finally {
      if (admitted) {
        document.exit();
      }
      final long[] found = entries;
      final String message = error;
      final long end = Math.min(to, index.lineCount());
      owner
          .ui()
          .asyncExec(
              new Runnable() {
                @Override
                public void run() {
                  classified(found, message, from, end, line, token, started);
                }
              });
    }
  }

  private void classified(
      final long[] entries,
      String error,
      long from,
      long to,
      final long line,
      CancellationToken token,
      long started) {
    if (running == token) {
      running = null;
    }
    if (closed || owner.isClosed()) {
      return;
    }
    if (started == generation) {
      apply(entries, error, from, to, line);
    }
    if (running == null && queuedToggle >= 0) {
      long queued = queuedToggle;
      queuedToggle = -1;
      toggle(queued);
    }
  }

  /** Collapses what a current classification found. */
  private void apply(final long[] entries, String error, long from, long to, long line) {
    if (error != null) {
      collapseAll = false;
      owner.notifications().showNotice(error);
      return;
    }
    if (entries == null) {
      return;
    }
    if (line >= 0) {
      collapseEntryOf(entries, line);
    } else {
      if (classifiedFrom < 0 || to < classifiedFrom || from > classifiedTo) {
        classifiedFrom = from;
        classifiedTo = to;
      } else {
        classifiedFrom = Math.min(classifiedFrom, from);
        classifiedTo = Math.max(classifiedTo, to);
      }
      owner.changeFolds(
          new Runnable() {
            @Override
            public void run() {
              for (int i = 0; i < entries.length; i += 2) {
                collapse(entries[i], entries[i + 1]);
              }
            }
          });
    }
    if (wanted) {
      wanted = false;
      owner.foldsMayNeedClassifying();
    }
  }

  private void collapseEntryOf(long[] entries, long line) {
    for (int i = 0; i < entries.length; i += 2) {
      final long start = entries[i];
      final long end = entries[i + 1];
      if (line >= start && line < end) {
        owner.changeFolds(
            new Runnable() {
              @Override
              public void run() {
                collapse(start, end);
              }
            });
        return;
      }
    }
    owner.notifications().showNotice(UiText.NOTICE_NO_FOLD.text());
  }

  /** Collapses an entry unless it overlaps one; a refusal by the share shows a notice once. */
  private void collapse(long start, long end) {
    if (map.collapsedEntry(start) != null || map.collapsedEntry(end - 1) != null) {
      return;
    }
    if (!map.collapse(start, end)
        && owner.budget().available(MemoryShare.FOLDS) < FoldMap.ENTRY_BYTES
        && !limitShown) {
      limitShown = true;
      owner.notifications().showNotice(UiText.NOTICE_FOLD_LIMIT.text());
    }
  }
}
