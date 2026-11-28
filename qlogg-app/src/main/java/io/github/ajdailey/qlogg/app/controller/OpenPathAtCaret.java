package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.MainView;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.model.CellReader;
import io.github.ajdailey.qlogg.core.model.CellRecord;
import io.github.ajdailey.qlogg.core.model.ColumnRead;
import io.github.ajdailey.qlogg.core.model.LineReader;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.text.PathAtCaret;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File > Open Path at Caret: reads the focused view's caret line, at most {@link #REACH} chars
 * either side of the caret, finds the path there ({@link PathAtCaret}), resolves a relative path
 * against the folder of the tab's file and opens the file through {@link
 * ApplicationController#openFile} at its {@code :line}. The read and the {@link SafePaths} check
 * run on a {@code qlogg-read-} worker under the busy cursor. No path, a URL, a relative path on a
 * tab without a file, or no regular file shows a notice; a URL is never opened. A newer run drops
 * an older result. In column view the text is the current cell's value ({@link CellCaret}), and the
 * path is its first run holding a {@code /} or {@code \}, else its first run. Confined to the UI
 * thread.
 */
final class OpenPathAtCaret {
  /** Chars read on each side of the caret. */
  static final int REACH = 4096;

  private static final Logger LOG = LoggerFactory.getLogger(OpenPathAtCaret.class);

  /**
   * What a check found.
   *
   * @param file the regular file to open, absolute and normalized; null with a notice
   * @param line the line to go to from 1, or 0
   * @param notice why nothing opens; null with a file, and null with no file when the line could
   *     not be read
   */
  record Target(Path file, long line, String notice) {}

  private final ApplicationController application;
  private final Supplier<LineViewController> focused;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final BooleanSupplier closing;
  private int generation;

  /**
   * Creates the command of one window.
   *
   * @param focused the view commands go to, or null with no document
   * @param closing true once the window stops accepting operations; drops a pending result
   */
  OpenPathAtCaret(
      ApplicationController application,
      Supplier<LineViewController> focused,
      Workers workers,
      UiDispatcher ui,
      MainView view,
      BooleanSupplier closing) {
    this.application = application;
    this.focused = focused;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.closing = closing;
  }

  /** Looks for the path at the focused view's caret and opens its file. */
  void open() {
    LineViewController target = focused.get();
    if (target == null) {
      return;
    }
    final LogDocument document = target.document();
    final LineIndex index = document.index();
    final long line = target.sourceLine(target.selection().caret().line());
    final long column = target.selection().caret().column();
    final ColumnRead cells = target.owner().columns().read();
    if (line < 0 || line >= index.lineCount()) {
      view.showNotice(UiText.OPEN_PATH_NONE.text());
      return;
    }
    Path file = document.path();
    final Path base = file == null ? null : file.getParent();
    final SafePaths.HeldLock held = application.services().heldLock();
    final int run = ++generation;
    new BusyWork(ui, view) {
      private Target found;

      @Override
      @WorkerThread
      void work() {
        found =
            cells == null
                ? find(document, index, line, column, base, held)
                : findInCell(document, index, line, column, cells, base, held);
      }

      @Override
      void finished(boolean completed) {
        if (completed && run == generation && !closing.getAsBoolean()) {
          opened(found);
        }
      }
    }.start(workers, Workers.Kind.READ);
  }

  private void opened(Target target) {
    if (target.notice() != null) {
      view.showNotice(target.notice());
    } else if (target.file() != null) {
      application.openFile(target.file(), target.line());
    }
  }

  /**
   * Reads the text around a caret and checks the path there.
   *
   * @param line the caret's 0-based line
   * @param column the caret's display column
   * @param base the folder a relative path resolves against, or null for none
   * @param held the lock file the process holds, or null
   * @return the file, or the notice to show
   */
  @WorkerThread
  private static Target find(
      LogDocument document,
      LineIndex index,
      long line,
      long column,
      Path base,
      SafePaths.HeldLock held) {
    if (!document.enter()) {
      return new Target(null, 0, null);
    }
    String around;
    int caret;
    try {
      CharSequence text = document.newReader().decodedLine(index, line);
      int at = charAt(text, column);
      int from = Math.max(0, at - REACH);
      around = text.subSequence(from, Math.min(text.length(), at + REACH)).toString();
      caret = at - from;
    } catch (ClosedChannelException e) {
      return new Target(null, 0, null);
    } catch (IOException | UncheckedIOException e) {
      LOG.warn(
          "Cannot read {}: {}", LogText.oneLine(document.path()), LogText.oneLine(e.getMessage()));
      return new Target(null, 0, null);
    } finally {
      document.exit();
    }
    return check(PathAtCaret.find(around, caret), base, held);
  }

  /** Reads the current cell's value and checks the path in it. */
  @WorkerThread
  private static Target findInCell(
      LogDocument document,
      LineIndex index,
      long line,
      long column,
      ColumnRead cells,
      Path base,
      SafePaths.HeldLock held) {
    if (!document.enter()) {
      return new Target(null, 0, null);
    }
    PathAtCaret.Found found;
    try {
      CellRecord record = new CellReader(document.newReader()).record(index, line, cells);
      CharSequence value =
          RecordText.cellValue(record, cells.layout(), cells.layout().cellAt(column));
      if (!record.parsed()) {
        int at = charAt(value, column);
        int from = Math.max(0, at - REACH);
        found =
            PathAtCaret.find(
                value.subSequence(from, Math.min(value.length(), at + REACH)), at - from);
      } else {
        found = firstPath(value.subSequence(0, Math.min(value.length(), 2 * REACH)));
      }
    } catch (ClosedChannelException e) {
      return new Target(null, 0, null);
    } catch (IOException | UncheckedIOException e) {
      LOG.warn(
          "Cannot read {}: {}", LogText.oneLine(document.path()), LogText.oneLine(e.getMessage()));
      return new Target(null, 0, null);
    } finally {
      document.exit();
    }
    return check(found, base, held);
  }

  /** The path of a value's first run holding a path separator, else of its first run. */
  static PathAtCaret.Found firstPath(CharSequence value) {
    PathAtCaret.Found first = null;
    for (int k = 0; k < value.length(); k++) {
      boolean starts =
          !Character.isWhitespace(value.charAt(k))
              && (k == 0 || Character.isWhitespace(value.charAt(k - 1)));
      if (!starts) {
        continue;
      }
      PathAtCaret.Found found = PathAtCaret.find(value, k);
      if (found != null && (found.path().indexOf('/') >= 0 || found.path().indexOf('\\') >= 0)) {
        return found;
      }
      if (first == null) {
        first = found;
      }
    }
    return first;
  }

  /** Returns the index of the char at a display column, or the length past the line's end. */
  private static int charAt(CharSequence text, long column) {
    int k = 0;
    long at = 0;
    while (k < text.length() && at < column) {
      at = LineReader.advance(at, text.charAt(k));
      k++;
    }
    return k;
  }

  /**
   * Checks a path found at a caret.
   *
   * @param found the path, or null for none
   * @param base the folder a relative path resolves against, or null for none
   * @param held the lock file the process holds, or null
   * @return the file, or the notice to show
   */
  @WorkerThread
  static Target check(PathAtCaret.Found found, Path base, SafePaths.HeldLock held) {
    if (found == null) {
      return new Target(null, 0, UiText.OPEN_PATH_NONE.text());
    }
    if (found.url()) {
      return new Target(null, 0, UiText.OPEN_PATH_URL.format(found.path()));
    }
    Path path;
    try {
      path = Path.of(found.path());
    } catch (InvalidPathException e) {
      return new Target(null, 0, UiText.OPEN_PATH_MISSING.format(found.path()));
    }
    if (!path.isAbsolute()) {
      if (base == null) {
        return new Target(null, 0, UiText.OPEN_PATH_NO_FOLDER.format(found.path()));
      }
      path = base.resolve(path);
    }
    path = path.normalize();
    try {
      SafePaths.LogPath checked = SafePaths.logPath(path, held);
      if (!Files.isRegularFile(checked.real())) {
        return new Target(null, 0, UiText.OPEN_PATH_MISSING.format(path));
      }
      return new Target(checked.given(), found.line(), null);
    } catch (IOException e) {
      String form = PathFormText.reason(e);
      if (form != null) {
        return new Target(null, 0, UiText.OPEN_PATH_REFUSED.format(form));
      }
      if (Files.isRegularFile(path)) {
        return new Target(null, 0, UiText.OPEN_PATH_REFUSED.format(e.getMessage()));
      }
      return new Target(null, 0, UiText.OPEN_PATH_MISSING.format(path));
    }
  }
}
