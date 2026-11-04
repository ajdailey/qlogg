package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.ActivityIndicator;
import io.github.ajdailey.qlogg.app.view.NotificationService;
import io.github.ajdailey.qlogg.app.view.QuickFindListener;
import io.github.ajdailey.qlogg.app.view.QuickFindState;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.LineFinder;
import io.github.ajdailey.qlogg.core.model.LineReader;
import io.github.ajdailey.qlogg.core.model.RowCursor;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.search.PatternTooComplexException;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Quick find: walks occurrences of a pattern in the focused view, one at a time, from the caret,
 * without a result set. The pattern is fixed text unless {@code quickFind.regex} is on; ignore case
 * is the bar's box. With no occurrence left in the direction, a notice says so and the selection
 * stays: there is no wrap. With {@code quickFind.incremental} on, each change to the text searches
 * forward again from where the caret was when typing began.
 *
 * <p>Each find compiles the pattern and scans on a {@code qlogg-search-} worker ({@link
 * LineFinder}) inside a document entry, holding the {@link ActivityIndicator} active until its
 * result is applied. A newer find cancels the older one, and a result whose generation is not the
 * newest is dropped. Confined to the UI thread.
 */
public final class QuickFindController {
  private final ApplicationController application;
  private final Workers workers;
  private final UiDispatcher ui;
  private final ActivityIndicator activity;
  private final NotificationService notifications;
  private final List<QuickFindListener> listeners = new ArrayList<QuickFindListener>();
  private QuickFindState state = QuickFindState.CLOSED;
  private long generation;
  private CancellationToken running;
  private DocumentController runningOwner;
  private LineViewController originView;
  private long originRow = -1;
  private long originColumn;
  private boolean forward = true;

  QuickFindController(
      ApplicationController application,
      Workers workers,
      UiDispatcher ui,
      ActivityIndicator activity,
      NotificationService notifications) {
    this.application = application;
    this.workers = workers;
    this.ui = ui;
    this.activity = activity;
    this.notifications = notifications;
  }

  /**
   * Returns the bar's state.
   *
   * @return the latest state
   */
  public QuickFindState state() {
    return state;
  }

  /**
   * Reports whether the bar shows, so find next and previous walk quick find.
   *
   * @return true while open
   */
  public boolean isOpen() {
    return state.open();
  }

  /**
   * Adds a listener.
   *
   * @param listener called on the UI thread
   */
  public void addListener(QuickFindListener listener) {
    listeners.add(listener);
  }

  /**
   * Removes a listener.
   *
   * @param listener a listener added earlier
   */
  public void removeListener(QuickFindListener listener) {
    listeners.remove(listener);
  }

  /**
   * Shows the bar.
   *
   * @param towardEnd the direction Enter in the field searches
   */
  public void open(boolean towardEnd) {
    originView = null;
    forward = towardEnd;
    publish(withOpen(true, ""));
  }

  /** Finds in the direction the bar was opened for, as Enter in the field does. */
  public void submit() {
    find(forward, false);
  }

  /** Hides the bar and cancels a running find. */
  public void close() {
    cancelRunning();
    generation++;
    publish(withOpen(false, ""));
  }

  /**
   * Applies the options saved as preferences.
   *
   * @param incremental {@code quickFind.incremental}
   * @param regex {@code quickFind.regex}
   */
  void applyPreferences(boolean incremental, boolean regex) {
    publish(
        new QuickFindState(
            state.open(),
            state.text(),
            state.ignoreCase(),
            regex,
            incremental,
            state.running(),
            ""));
  }

  /**
   * Sets the ignore-case box.
   *
   * @param ignoreCase true folds case
   */
  public void setIgnoreCase(boolean ignoreCase) {
    publish(
        new QuickFindState(
            state.open(),
            state.text(),
            ignoreCase,
            state.regex(),
            state.incremental(),
            state.running(),
            state.notice()));
  }

  /**
   * Records the field's text as the user types; with incremental find on, searches forward from
   * where typing began.
   *
   * @param text the field's text
   */
  public void setText(String text) {
    if (text.equals(state.text())) {
      return;
    }
    publish(
        new QuickFindState(
            state.open(),
            text,
            state.ignoreCase(),
            state.regex(),
            state.incremental(),
            state.running(),
            ""));
    if (state.open() && state.incremental() && !text.isEmpty()) {
      find(true, true);
    }
  }

  /** Finds the next occurrence after the selection. */
  public void next() {
    find(true, false);
  }

  /** Finds the previous occurrence before the selection. */
  public void previous() {
    find(false, false);
  }

  /**
   * Opens the bar with the focused view's selected text and finds it.
   *
   * @param towardEnd true for the next occurrence
   */
  public void findSelection(final boolean towardEnd) {
    LineViewController target = application.focusedView();
    if (target == null) {
      return;
    }
    target.selectedText(
        new TextListener() {
          @Override
          public void textRead(String text) {
            originView = null;
            publish(
                new QuickFindState(
                    true,
                    text,
                    state.ignoreCase(),
                    state.regex(),
                    state.incremental(),
                    state.running(),
                    ""));
            forward = towardEnd;
            application.quickFindOpened();
            find(towardEnd, false);
          }
        });
  }

  /** Cancels a running find; for shutdown and tab switches. */
  void cancelRunning() {
    if (running != null) {
      running.cancel();
      running = null;
      runningOwner = null;
    }
  }

  /**
   * Cancels a running find in one document's views; for its close.
   *
   * @param owner the document
   */
  void cancelRunning(DocumentController owner) {
    if (runningOwner == owner) {
      cancelRunning();
    }
  }

  private void find(boolean towardEnd, boolean incremental) {
    final LineViewController target = application.focusedView();
    final String text = state.text();
    if (target == null || text.isEmpty() || target.isClosed()) {
      return;
    }
    Selection selection = target.selection();
    long row;
    long column;
    if (incremental) {
      if (originView != target) {
        originView = target;
        originRow = selection.start().line();
        originColumn = selection.start().column();
      }
      row = originRow;
      column = originColumn;
    } else {
      originView = null;
      boolean after = towardEnd && !selection.isEmpty();
      row = after ? selection.end().line() : selection.start().line();
      column = after ? selection.end().column() : selection.start().column();
      if (selection.isEmpty()) {
        row = selection.caret().line();
        column = selection.caret().column();
      }
    }
    if (column == Long.MAX_VALUE) {
      column = 0;
      row = towardEnd ? row + 1 : row;
    }
    cancelRunning();
    final long started = ++generation;
    final CancellationToken token = new CancellationToken();
    running = token;
    runningOwner = target.owner();
    final long line = target.sourceLine(row);
    final long fromColumn = line < 0 ? 0 : column;
    final boolean direction = towardEnd;
    final RowCursor rows = target.rows();
    final LineIndex index = target.document().index();
    final LineReader reader = target.document().newReader();
    final boolean regex = state.regex();
    final boolean ignoreCase = state.ignoreCase();
    publish(running(true));
    activity.setActive();
    boolean launched =
        workers.tryStart(
            Workers.Kind.SEARCH,
            new Runnable() {
              @Override
              public void run() {
                scan(
                    started,
                    target,
                    reader,
                    index,
                    rows,
                    text,
                    regex,
                    ignoreCase,
                    line,
                    fromColumn,
                    direction,
                    token);
              }
            });
    if (!launched) {
      activity.setIdle();
      publish(running(false));
    }
  }

  /** Runs on a worker; posts back on every outcome so the activity always ends. */
  private void scan(
      final long started,
      final LineViewController target,
      LineReader reader,
      LineIndex index,
      RowCursor rows,
      String text,
      boolean regex,
      boolean ignoreCase,
      long line,
      long column,
      final boolean forward,
      CancellationToken token) {
    LineFinder.Match match = null;
    String error = null;
    boolean canceled = false;
    boolean admitted = target.document().enter();
    try {
      if (!admitted) {
        canceled = true;
        return;
      }
      int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
      Pattern pattern = Pattern.compile(regex ? text : Pattern.quote(text), flags);
      match = LineFinder.find(reader, index, rows, pattern, line, column, forward, token);
    } catch (PatternSyntaxException e) {
      error =
          UiText.SEARCH_REGEX_ERROR.format(
              Math.max(0, e.getIndex()) + 1, LogText.oneLine(e.getDescription()));
    } catch (PatternTooComplexException e) {
      error = UiText.SEARCH_PATTERN_TOO_COMPLEX.format(e.line() + 1);
    } catch (CancellationException | ClosedChannelException e) {
      canceled = true;
    } catch (IOException | UncheckedIOException e) {
      error = UiText.COPY_READ_FAILED.format(LogText.oneLine(e.getMessage()));
    } finally {
      if (admitted) {
        target.document().exit();
      }
      final LineFinder.Match found = match;
      final String message = error;
      final boolean dropped = canceled;
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              finished(started, target, found, message, dropped, forward);
            }
          });
    }
  }

  private void finished(
      long started,
      LineViewController target,
      LineFinder.Match match,
      String error,
      boolean canceled,
      boolean forward) {
    activity.setIdle();
    if (started != generation) {
      return;
    }
    running = null;
    runningOwner = null;
    if (canceled || target.isClosed()) {
      publish(running(false));
      return;
    }
    String notice = "";
    if (error != null) {
      notice = error;
    } else if (match == null) {
      notice = forward ? UiText.NOTICE_END_OF_FILE.text() : UiText.NOTICE_START_OF_FILE.text();
      notifications.showNotice(notice);
    } else {
      target.selectRange(target.revealRow(match.line()), match.startColumn(), match.endColumn());
    }
    publish(
        new QuickFindState(
            state.open(),
            state.text(),
            state.ignoreCase(),
            state.regex(),
            state.incremental(),
            false,
            notice));
  }

  private QuickFindState withOpen(boolean open, String notice) {
    return new QuickFindState(
        open, state.text(), state.ignoreCase(), state.regex(), state.incremental(), false, notice);
  }

  private QuickFindState running(boolean value) {
    return new QuickFindState(
        state.open(),
        state.text(),
        state.ignoreCase(),
        state.regex(),
        state.incremental(),
        value,
        value ? "" : state.notice());
  }

  private void publish(QuickFindState next) {
    state = next;
    for (QuickFindListener listener : new ArrayList<QuickFindListener>(listeners)) {
      listener.stateChanged(next);
    }
  }
}
