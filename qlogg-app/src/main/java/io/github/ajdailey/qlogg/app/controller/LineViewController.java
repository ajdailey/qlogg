package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.controller.TextExporter.Output;
import io.github.ajdailey.qlogg.app.controller.TextExporter.Request;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.ActivityIndicator;
import io.github.ajdailey.qlogg.app.view.ClipboardService;
import io.github.ajdailey.qlogg.app.view.DocumentViewListener;
import io.github.ajdailey.qlogg.app.view.DocumentViewState;
import io.github.ajdailey.qlogg.app.view.NotificationService;
import io.github.ajdailey.qlogg.app.view.TimeStatus;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.Highlighter;
import io.github.ajdailey.qlogg.core.model.LineBlock;
import io.github.ajdailey.qlogg.core.model.LineReader;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.RowCursor;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.model.Viewport;
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
 * A scrolling view of rows over one document: the main view, where row n is line n, or the filtered
 * view, where rows map to matching and marked lines. It holds the {@link Viewport}, the {@link
 * Selection} in rows and the decoded visible {@link LineBlock}, and it runs the navigation, copy
 * and mark commands both views share. Confined to the UI thread and never blocks it.
 *
 * <p>Visible rows are decoded on a {@code qlogg-read-} worker into an immutable block. At most one
 * read is in flight; a viewport change during a read starts one more when it returns. Reads, copies
 * and selected-text reads hold a {@link LogDocument#enter} entry, so closing the document waits for
 * them. Copy, save and selected-text reads run {@link TextExporter} on a worker, charged to the
 * {@link MemoryShare#COPY} share until the text is on the clipboard. One copy or save runs at a
 * time; another shows a notice. {@link #cancelExports} ({@code Esc}) and {@link #detachView} cancel
 * the running ones. Copy holds the {@link ActivityIndicator} active until it ends.
 *
 * <p>The read that decodes the rows also colors them with the view's {@link Highlighter}, from each
 * row's whole decoded line, so highlighting runs off the UI thread and only for the visible rows.
 * The block's spans are charged to {@link MemoryShare#HIGHLIGHT} while it is shown; a block whose
 * spans the share cannot hold is shown without them. Mark toggles run on a worker ({@link
 * DocumentController#toggleMarks}).
 *
 * <p>A block selection ({@link #blockSelection}) covers the display columns between its anchor and
 * caret on every row between them; copy takes, per row, the chars whose first column lies inside,
 * replaces a tab partly inside with spaces and joins the rows with LF. With text wrap on ({@link
 * #wrap}) the first column stays 0, a read takes up to {@link #MAX_WRAP_COLUMNS} columns per line,
 * the viewport's rows are the lines the view reports as fitting ({@link #wrapFitted}), and a block
 * selection is refused. Save ({@link #saveLines}) builds the text like copy and writes it as UTF-8
 * through {@link io.github.ajdailey.qlogg.core.io.AtomicFiles} on the worker.
 *
 * <p>With a timestamp format ({@link DocumentController#time}), the caret line's time and, for a
 * selection over more than one row, the times of its first and last rows' lines are read by a
 * {@link CaretTimes} ({@link #timeStatus}); listeners hear {@code timeChanged} when they change.
 */
public abstract class LineViewController {
  /** Columns read past the right edge, so a small horizontal scroll repaints at once. */
  static final int COLUMN_MARGIN = 16;

  /** Most columns of a line read with text wrap on. */
  public static final int MAX_WRAP_COLUMNS = 1 << 16;

  private static final Logger LOG = LoggerFactory.getLogger(LineViewController.class);

  private final LogDocument document;
  private final Workers workers;
  private final UiDispatcher ui;
  private final ClipboardService clipboard;
  private final NotificationService notifications;
  private final ActivityIndicator activity;
  private final MemoryBudget budget;
  private final Viewport viewport = new Viewport();
  private final List<DocumentViewListener> listeners = new ArrayList<DocumentViewListener>();
  private final LineReader reader;
  private LineBlock lines = LineBlock.EMPTY;
  private long spanCharge;
  private Highlighter highlighter = Highlighter.NONE;
  private Selection selection = Selection.caretAt(new TextPosition(0, 0));
  private boolean lineNumbers = true;
  private boolean readInFlight;
  private boolean readWanted;
  private boolean copyInFlight;
  private boolean closed;
  // Canceled by Esc and detach; replaced after Esc so later exports run.
  private CancellationToken exports = new CancellationToken();
  private long rangeAnchor = -1;
  // Block selection, text wrap and whitespace markers.
  private boolean blockSelected;
  private boolean wrap;
  private boolean whitespace;
  private int screenRows = 1;
  private int wrapFit = 1;
  // Caret time and selection difference.
  private final CaretTimes caretTimes;

  /**
   * Creates the view controller.
   *
   * @param document the document read
   * @param workers runs reads and copies
   * @param ui the UI-thread dispatcher
   * @param clipboard receives copied text
   * @param notifications shows copy failures and notices
   * @param activity shows copy as running
   * @param budget charged while copied text is built
   */
  LineViewController(
      LogDocument document,
      Workers workers,
      UiDispatcher ui,
      ClipboardService clipboard,
      NotificationService notifications,
      ActivityIndicator activity,
      MemoryBudget budget) {
    this.document = document;
    this.workers = workers;
    this.ui = ui;
    this.clipboard = clipboard;
    this.notifications = notifications;
    this.activity = activity;
    this.budget = budget;
    this.reader = document.newReader();
    this.caretTimes = new CaretTimes(this, workers, ui);
  }

  /**
   * Returns the main view's controller of this document.
   *
   * @return the controller; itself for the main view
   */
  public abstract DocumentController owner();

  /**
   * Maps a row to its document line.
   *
   * @param row a 0-based row
   * @return the 0-based line, or -1 outside the view
   */
  public abstract long sourceLine(long row);

  /**
   * Returns the row of a line, or of the nearest listed line after it.
   *
   * @param line a 0-based line
   * @return the row
   */
  public abstract long rowOf(long line);

  /**
   * Returns the row of a line for navigation to it; the main view first expands a fold that hides
   * the line.
   *
   * @param line a 0-based line
   * @return the row
   */
  public long revealRow(long line) {
    return rowOf(line);
  }

  /**
   * Returns a thread-safe walk over the lines this view lists.
   *
   * @return the cursor
   */
  public abstract RowCursor rows();

  /** Called after the caret may have moved to another row. Does nothing by default. */
  void caretMoved() {}

  /** Called after the user moved the view or the caret. Does nothing by default. */
  void userMoved() {}

  // Navigation history.

  /**
   * Called after a click or Enter chose the caret's row; the filtered view records the main view's
   * line from before it moved it. Does nothing by default.
   */
  public void rowChosen() {}

  /**
   * Runs a move as a jump of the document's navigation history: the main view records its line,
   * then moves.
   *
   * @param move the move
   */
  void jump(Runnable move) {
    owner().recordJump();
    move.run();
  }

  /**
   * Returns the status of the document for the status bar.
   *
   * @return the latest state
   */
  public abstract DocumentViewState state();

  /**
   * Returns the document.
   *
   * @return the document
   */
  public LogDocument document() {
    return document;
  }

  /**
   * Returns the viewport; the view reads it and changes it only through this controller.
   *
   * @return the viewport, in rows
   */
  public Viewport viewport() {
    return viewport;
  }

  /**
   * Returns the latest decoded rows.
   *
   * @return the block; may not cover the whole viewport while a read is in flight
   */
  public LineBlock lines() {
    return lines;
  }

  /**
   * Returns the selection, in rows and display columns.
   *
   * @return the selection
   */
  public Selection selection() {
    return selection;
  }

  /**
   * Reports whether line numbers show.
   *
   * @return true by default
   */
  public boolean lineNumbers() {
    return lineNumbers;
  }

  /**
   * Returns the highlighter the rows are colored with.
   *
   * @return the highlighter
   */
  public Highlighter highlighter() {
    return highlighter;
  }

  /**
   * Replaces the highlighter and re-reads the visible rows.
   *
   * @param next the highlighter
   */
  void setHighlighter(Highlighter next) {
    if (next == highlighter) {
      return;
    }
    highlighter = next;
    changed();
  }

  /**
   * Reports whether a line starts a collapsed entry, for the gutter's fold marker.
   *
   * @param line a 0-based line
   * @return false in a view without folding
   */
  public boolean folded(long line) {
    return false;
  }

  /**
   * Reports whether a line is marked, for the gutter's bullet.
   *
   * @param line a 0-based line
   * @return true when marked
   */
  public boolean marked(long line) {
    return document.marks().contains(line);
  }

  /**
   * Returns a line's mark comment, for the gutter's bullet and tooltip.
   *
   * @param line a 0-based line
   * @return the comment, or null when the line has none
   */
  public String markComment(long line) {
    return document.markComments().get(line);
  }

  /**
   * Reports whether a line is in the shown search results, for the gutter's bullet.
   *
   * @param line a 0-based line
   * @return true when it matches
   */
  public boolean matched(long line) {
    return owner().search().shownResults().contains(line);
  }

  /**
   * Adds a view listener.
   *
   * @param listener called on the UI thread
   */
  public void addListener(DocumentViewListener listener) {
    listeners.add(listener);
  }

  /**
   * Removes a view listener.
   *
   * @param listener a listener added earlier
   */
  public void removeListener(DocumentViewListener listener) {
    listeners.remove(listener);
  }

  /**
   * Reports whether the controller is detached.
   *
   * @return true once its document is closing
   */
  public boolean isClosed() {
    return closed;
  }

  /**
   * Runs a command of the focused view; commands no view handles are ignored.
   *
   * @param command the command
   */
  public void execute(CommandId command) {
    int rows = viewport.rows();
    switch (command) {
      case COPY -> copySelection();
      case COPY_LINE -> copyLine();
      case SELECT_ALL -> selectAll();
      case SET_SELECTION_START -> setSelectionStart();
      case SET_SELECTION_END -> setSelectionEnd();
      case TOGGLE_LINE_NUMBERS -> toggleLineNumbers();
      case TOGGLE_MARK -> toggleMarks();
      case GO_TO_TOP -> jumpTo(0);
      case GO_TO_BOTTOM -> jumpTo(Long.MAX_VALUE);
      case GO_TO_RESULT -> rowChosen();
      case LINE_UP -> moveCaret(-1, false);
      case LINE_DOWN -> moveCaret(1, false);
      case EXTEND_UP -> moveCaret(-1, true);
      case EXTEND_DOWN -> moveCaret(1, true);
      case SCROLL_UP -> scrollLines(-1);
      case SCROLL_DOWN -> scrollLines(1);
      case PAGE_UP -> page(-rows);
      case PAGE_DOWN -> page(rows);
      case SCROLL_LEFT -> scrollColumns(-1);
      case SCROLL_RIGHT -> scrollColumns(1);
      case LINE_START -> setFirstColumn(0);
      case LINE_END -> lineEnd();
      case RIGHT_EDGE -> rightEdge();
      case COPY_WITH_LINE_NUMBERS -> copyWithLineNumbers();
      case EXTEND_BLOCK_UP -> extendBlock(-1, 0);
      case EXTEND_BLOCK_DOWN -> extendBlock(1, 0);
      case EXTEND_BLOCK_LEFT -> extendBlock(0, -1);
      case EXTEND_BLOCK_RIGHT -> extendBlock(0, 1);
      default -> {
        // Window and document commands run in ApplicationController and DocumentController.
      }
    }
  }

  /**
   * Reports the view's size.
   *
   * @param rows whole rows that fit
   * @param columns whole columns that fit right of the line numbers
   */
  public void viewportResized(int rows, int columns) {
    screenRows = Math.max(1, rows);
    int shown = wrap ? Math.min(screenRows, wrapFit) : screenRows;
    if (shown == viewport.rows() && columns == viewport.columns()) {
      return;
    }
    viewport.resize(shown, columns);
    changed();
  }

  /**
   * Reports whether long lines wrap.
   *
   * @return true with text wrap on
   */
  public boolean wrap() {
    return wrap;
  }

  /**
   * Turns text wrap on or off; turning it on scrolls to the first column and drops a block
   * selection.
   *
   * @param on true to wrap
   */
  public void setWrap(boolean on) {
    if (on == wrap) {
      return;
    }
    wrap = on;
    wrapFit = screenRows;
    if (on) {
      viewport.setFirstColumn(0);
      if (blockSelected) {
        blockSelected = false;
        selection = Selection.caretAt(selection.caret());
      }
    }
    viewport.resize(screenRows, viewport.columns());
    changed();
  }

  /**
   * Reports, with text wrap on, how many lines from the top fit the view whole, counting a line not
   * read yet as one row. The viewport's rows follow it, so the last lines can scroll into view and
   * caret moves keep the caret's line on screen.
   *
   * @param lines lines that fit, at least 1
   */
  public void wrapFitted(int lines) {
    int fit = Math.max(1, Math.min(screenRows, lines));
    if (!wrap || fit == wrapFit) {
      return;
    }
    wrapFit = fit;
    viewport.resize(fit, viewport.columns());
    changed();
  }

  /**
   * Returns the rows the view has room for, whatever wraps.
   *
   * @return rows from the last {@link #viewportResized}
   */
  public int screenRows() {
    return screenRows;
  }

  /**
   * Reports whether reads record whitespace markers.
   *
   * @return true when tabs, trailing spaces and line ends are marked
   */
  public boolean whitespace() {
    return whitespace;
  }

  /**
   * Turns whitespace markers on or off and re-reads.
   *
   * @param on true to mark
   */
  public void setWhitespace(boolean on) {
    if (on != whitespace) {
      whitespace = on;
      changed();
    }
  }

  /**
   * Reports whether the selection is a block of columns.
   *
   * @return true for a block selection
   */
  public boolean blockSelection() {
    return blockSelected;
  }

  /**
   * Replaces the selection with a block between two corners, as an Alt-drag sets it. With text wrap
   * on a notice shows and the selection becomes a stream selection.
   *
   * @param next anchor and caret, in rows and display columns
   */
  public void selectBlock(Selection next) {
    if (wrap) {
      notifications.showNotice(UiText.NOTICE_BLOCK_WRAP.text());
      select(next);
      return;
    }
    selection = next;
    blockSelected = true;
    caretMoved();
    changed();
  }

  /** Moves the caret corner of a block selection, starting one at the caret. */
  private void extendBlock(long rows, long columns) {
    long count = viewport.lineCount();
    if (count == 0) {
      return;
    }
    TextPosition caret = selection.caret();
    TextPosition next =
        new TextPosition(
            Math.max(0, Math.min(count - 1, caret.line() + rows)),
            Math.max(0, caret.column() + columns));
    TextPosition anchor = blockSelected ? selection.anchor() : caret;
    if (!wrap) {
      viewport.ensureVisible(next.line());
      viewport.ensureColumnVisible(next.column());
    }
    selectBlock(new Selection(anchor, next));
  }

  /**
   * Scrolls without moving the caret.
   *
   * @param delta rows; positive scrolls down
   */
  public void scrollLines(long delta) {
    viewport.scrollBy(delta);
    userMoved();
    changed();
  }

  /**
   * Scrolls horizontally.
   *
   * @param delta columns; positive scrolls right
   */
  public void scrollColumns(long delta) {
    if (wrap) {
      return;
    }
    viewport.scrollColumnsBy(delta);
    changed();
  }

  /**
   * Sets the first visible column.
   *
   * @param column a 0-based column
   */
  public void setFirstColumn(long column) {
    viewport.setFirstColumn(wrap ? 0 : column);
    changed();
  }

  /**
   * Applies a scroll bar thumb position.
   *
   * @param position the scroll bar's selection
   */
  public void setScrollbarSelection(int position) {
    viewport.setScrollbarSelection(position);
    userMoved();
    changed();
  }

  /**
   * Replaces the selection, as a mouse press or drag sets it.
   *
   * @param next the selection, in rows
   */
  public void select(Selection next) {
    this.selection = next;
    blockSelected = false;
    caretMoved();
    changed();
  }

  /** Selects every row listed so far. */
  public void selectAll() {
    long count = viewport.lineCount();
    if (count == 0) {
      return;
    }
    selection = new Selection(new TextPosition(0, 0), new TextPosition(count - 1, Long.MAX_VALUE));
    blockSelected = false;
    changed();
  }

  // Word selection.

  /**
   * Selects the word at a position, as a double click does: the letters, digits and underscores
   * around the char at the column, or around the char before it, within the decoded column window.
   * A row not read yet, a column outside the window, a position between non-word chars and a row
   * with a char outside printable ASCII select nothing: the view's hit columns follow the grid,
   * which matches the glyphs of printable-ASCII rows only.
   *
   * @param position the row and column
   */
  public void selectWordAt(TextPosition position) {
    long row = position.line();
    if (!lines.contains(row)) {
      return;
    }
    String text = lines.text(row);
    long column = position.column() - lines.firstColumn();
    if (column < 0 || column >= text.length() || !printableAscii(text)) {
      return;
    }
    int at = (int) column;
    if (!wordChar(text.charAt(at)) && at > 0 && wordChar(text.charAt(at - 1))) {
      at--;
    }
    if (!wordChar(text.charAt(at))) {
      return;
    }
    int start = at;
    while (start > 0 && wordChar(text.charAt(start - 1))) {
      start--;
    }
    int end = at;
    while (end < text.length() && wordChar(text.charAt(end))) {
      end++;
    }
    long first = lines.firstColumn();
    select(new Selection(new TextPosition(row, first + start), new TextPosition(row, first + end)));
  }

  private static boolean wordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  private static boolean printableAscii(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < 0x20 || c > 0x7e) {
        return false;
      }
    }
    return true;
  }

  /** Shows or hides line numbers. */
  public void toggleLineNumbers() {
    lineNumbers = !lineNumbers;
    fireContent();
  }

  /**
   * Shows or hides line numbers.
   *
   * @param shown true shows them
   */
  public void setLineNumbers(boolean shown) {
    if (lineNumbers != shown) {
      lineNumbers = shown;
      fireContent();
    }
  }

  /**
   * Selects matched text on a row and scrolls it into view, centering the row when it was off
   * screen.
   *
   * @param row the row
   * @param startColumn the first selected column
   * @param endColumn the column after the selection
   */
  public void selectRange(long row, long startColumn, long endColumn) {
    selection = new Selection(new TextPosition(row, startColumn), new TextPosition(row, endColumn));
    blockSelected = false;
    if (row < viewport.topLine() || row >= viewport.topLine() + viewport.rows()) {
      viewport.scrollToLine(row - viewport.rows() / 2);
    }
    viewport.ensureColumnVisible(endColumn);
    viewport.ensureColumnVisible(startColumn);
    caretMoved();
    userMoved();
    changed();
  }

  /**
   * Puts the caret on a row without scrolling or the {@link #caretMoved} hook.
   *
   * @param row a row
   */
  void placeCaret(long row) {
    selection = Selection.caretAt(new TextPosition(Math.max(0, row), 0));
    blockSelected = false;
  }

  /** Remembers the caret's row as one end of a line selection. */
  public void setSelectionStart() {
    rangeAnchor = selection.caret().line();
  }

  /**
   * Selects whole rows from the remembered row to the caret's row; without a remembered row, the
   * caret's row alone.
   */
  public void setSelectionEnd() {
    long count = viewport.lineCount();
    if (count == 0) {
      return;
    }
    long end = selection.caret().line();
    long start = rangeAnchor >= 0 ? Math.min(rangeAnchor, count - 1) : end;
    long first = Math.min(start, end);
    long last = Math.max(start, end);
    selection = new Selection(new TextPosition(first, 0), new TextPosition(last, Long.MAX_VALUE));
    blockSelected = false;
    changed();
  }

  /**
   * Returns the row remembered by {@link #setSelectionStart}.
   *
   * @return the row, or -1
   */
  public long selectionStart() {
    return rangeAnchor;
  }

  // Context lines.

  /**
   * Reports whether a line shows as context of a search result, for the row's shading.
   *
   * @param line a 0-based line
   * @return true for a context line; the main view lists none
   */
  public boolean contextRow(long line) {
    return false;
  }

  /**
   * Reports whether a gap in the listed lines lies above a line's row, for the group rule.
   *
   * @param line a listed 0-based line
   * @return true when the row starts a group of results with context; the main view has none
   */
  public boolean startsGroup(long line) {
    return false;
  }

  // Range selection.

  /**
   * Selects whole rows, the caret on the first, and scrolls the first into view when it is off
   * screen.
   *
   * @param first the first row
   * @param last the last row, at least {@code first}
   */
  void selectRows(long first, long last) {
    selection = new Selection(new TextPosition(last, Long.MAX_VALUE), new TextPosition(first, 0));
    blockSelected = false;
    if (first < viewport.topLine() || first >= viewport.topLine() + viewport.rows()) {
      viewport.scrollToLine(first - viewport.rows() / 2);
    }
    viewport.setFirstColumn(0);
    caretMoved();
    userMoved();
    changed();
  }

  /** Moves to a row as a jump of the navigation history. */
  private void jumpTo(final long row) {
    jump(
        new Runnable() {
          @Override
          public void run() {
            moveTo(row);
          }
        });
  }

  /**
   * Puts the caret on a row and scrolls it to the middle of the view.
   *
   * @param row a row; one past the end goes to the last row
   */
  void moveTo(long row) {
    long count = viewport.lineCount();
    long target = Math.max(0, Math.min(row, count - 1));
    selection = Selection.caretAt(new TextPosition(target, 0));
    blockSelected = false;
    viewport.scrollToLine(target - viewport.rows() / 2);
    viewport.setFirstColumn(0);
    caretMoved();
    userMoved();
    changed();
  }

  private void moveCaret(long delta, boolean extend) {
    long count = viewport.lineCount();
    if (count == 0) {
      return;
    }
    TextPosition caret = selection.caret();
    long line = Math.max(0, Math.min(count - 1, caret.line() + delta));
    TextPosition next = new TextPosition(line, caret.column());
    selection = extend ? new Selection(selection.anchor(), next) : Selection.caretAt(next);
    blockSelected = false;
    viewport.ensureVisible(line);
    caretMoved();
    userMoved();
    changed();
  }

  private void page(int delta) {
    viewport.scrollBy(delta);
    moveCaret(delta, false);
  }

  private void lineEnd() {
    if (wrap) {
      return;
    }
    long line = selection.caret().line();
    long width = lines.contains(line) ? lines.width(line) : viewport.maxColumns();
    viewport.setFirstColumn(width - viewport.columns() + 1);
    changed();
  }

  /** Scrolls so the end of the longest line in the decoded block shows at the right edge. */
  private void rightEdge() {
    if (wrap) {
      return;
    }
    long width = lines.count() > 0 ? lines.maxWidth() : viewport.maxColumns();
    viewport.setFirstColumn(width - viewport.columns() + 1);
    changed();
  }

  /**
   * Toggles the marks on the selected rows' lines on a worker: when every one is marked they are
   * unmarked, otherwise all are marked.
   */
  public void toggleMarks() {
    long count = viewport.lineCount();
    if (count == 0 || closed) {
      return;
    }
    long firstRow = selection.start().line();
    long lastRow = Math.min(selection.end().line(), count - 1);
    long firstLine = sourceLine(firstRow);
    if (firstLine < 0 || lastRow < firstRow) {
      return;
    }
    owner().toggleMarks(rows(), firstLine, lastRow - firstRow + 1);
  }

  /**
   * Copies the selection, or the caret's row when nothing is selected, to the clipboard. The text
   * is built on a worker from the rows' source lines; a selection the {@link MemoryShare#COPY}
   * share cannot hold is refused with a notice.
   */
  public void copySelection() {
    copy(new Request(selection, blockSelected, Output.CLIPBOARD, null), null);
  }

  /** Copies the caret's row. */
  public void copyLine() {
    copy(
        new Request(
            Selection.caretAt(new TextPosition(selection.caret().line(), 0)),
            false,
            Output.CLIPBOARD,
            null),
        null);
  }

  /** Copies the selection, or the caret's row, each line after its source line number and a tab. */
  public void copyWithLineNumbers() {
    copy(new Request(selection, blockSelected, Output.NUMBERED, null), null);
  }

  /**
   * Builds the text of the selection, or of the caret's row, on a worker as copy does and passes it
   * to a listener on the UI thread.
   *
   * @param listener receives the text
   */
  public void selectionText(TextListener listener) {
    copy(new Request(selection, blockSelected, Output.LISTENER, null), listener);
  }

  /**
   * Writes the selected rows, or the caret's row, to a file on a worker: whole lines, or the
   * block's columns, as UTF-8 joined by the platform line separator. The file is replaced
   * atomically; a notice reports the line count.
   *
   * @param file the validated target
   */
  public void saveLines(Path file) {
    Selection lines =
        blockSelected
            ? selection
            : new Selection(
                new TextPosition(selection.start().line(), 0),
                new TextPosition(selection.end().line(), Long.MAX_VALUE));
    copy(new Request(lines, blockSelected, Output.FILE, file), null);
  }

  /**
   * Writes every row of the view to a file, as {@link #saveLines} does.
   *
   * @param file the validated target
   */
  public void saveAll(Path file) {
    long count = viewport.lineCount();
    if (count == 0) {
      notifications.showNotice(UiText.NOTICE_NOTHING_TO_SAVE.text());
      return;
    }
    copy(
        new Request(
            new Selection(new TextPosition(0, 0), new TextPosition(count - 1, Long.MAX_VALUE)),
            false,
            Output.FILE,
            file),
        null);
  }

  private void copy(final Request request, final TextListener listener) {
    final Selection copied = request.selection();
    final LineIndex index = document.index();
    final long count = viewport.lineCount();
    if (closed || count == 0 || index.lineCount() == 0) {
      return;
    }
    if (copyInFlight) {
      notifications.showNotice(UiText.NOTICE_EXPORT_RUNNING.text());
      return;
    }
    long firstRow = Math.min(copied.start().line(), count - 1);
    long lastRow = Math.min(copied.end().line(), count - 1);
    long firstLine = sourceLine(firstRow);
    if (firstLine < 0) {
      return;
    }
    final TextExporter.Rows taken = new TextExporter.Rows(rows(), firstRow, lastRow, firstLine);
    final CancellationToken token = exports;
    copyInFlight = true;
    activity.setActive();
    boolean started =
        workers.tryStart(
            Workers.Kind.READ,
            new Runnable() {
              @Override
              public void run() {
                buildCopy(index, taken, request, listener, token);
              }
            });
    if (!started) {
      copyInFlight = false;
      activity.setIdle();
    }
  }

  /**
   * Reads the selected text of one row on a worker and passes it to the listener. Nothing is read,
   * and a notice shows, when the selection is empty or spans rows; the text is cut at {@link
   * TextExporter#MAX_SELECTED_CHARS}.
   *
   * @param listener receives the text on the UI thread
   */
  public void selectedText(final TextListener listener) {
    final long row = selection.start().line();
    if (selection.isEmpty() || row != selection.end().line()) {
      notifications.showNotice(UiText.SELECT_TEXT_FIRST.text());
      return;
    }
    final long line = sourceLine(row);
    final long from = selection.start().column();
    final long to = selection.end().column();
    final LineIndex index = document.index();
    if (line < 0 || line >= index.lineCount() || closed) {
      return;
    }
    final CancellationToken token = exports;
    boolean started =
        workers.tryStart(
            Workers.Kind.READ,
            new Runnable() {
              @Override
              public void run() {
                readSelected(index, line, from, to, token, listener);
              }
            });
    if (!started) {
      LOG.debug("Selected text not read: workers are closed");
    }
  }

  @WorkerThread
  private void readSelected(
      LineIndex index,
      long line,
      long from,
      long to,
      CancellationToken token,
      final TextListener listener) {
    final String result = TextExporter.selectedText(document, index, line, from, to, token);
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (!closed && result != null && !result.isEmpty()) {
              listener.textRead(result);
            }
          }
        });
  }

  /** Cancels the running copies, saves and selected-text reads; later ones run. */
  void cancelExports() {
    exports.cancel();
    exports = new CancellationToken();
  }

  /**
   * Stops applying reads and copies and cancels the running copies; listeners are dropped and the
   * spans' charge returned.
   */
  void detachView() {
    closed = true;
    exports.cancel();
    caretTimes.cancel();
    listeners.clear();
    budget.release(MemoryShare.HIGHLIGHT, spanCharge);
    spanCharge = 0;
  }

  /** Clamps the viewport to the current row count and re-reads the visible rows. */
  void refreshRows(long rowCount) {
    viewport.setLineCount(rowCount);
    viewport.setMaxColumns(Math.max(viewport.maxColumns(), lines.maxWidth()));
    long count = viewport.lineCount();
    if (count > 0 && selection.caret().line() >= count) {
      selection = Selection.caretAt(new TextPosition(count - 1, 0));
    }
    changed();
  }

  /** Re-reads and repaints. */
  void changed() {
    requestLines();
    caretTimes.request();
    fireContent();
  }

  /** Tells the views to repaint from the snapshot. */
  void fireContent() {
    for (DocumentViewListener listener : new ArrayList<DocumentViewListener>(listeners)) {
      listener.contentChanged();
    }
  }

  /** Pushes a status to the views. */
  void fireState(DocumentViewState state) {
    for (DocumentViewListener listener : new ArrayList<DocumentViewListener>(listeners)) {
      listener.stateChanged(state);
    }
  }

  /** The UI-thread dispatcher. */
  UiDispatcher ui() {
    return ui;
  }

  /** The thread owner. */
  Workers workers() {
    return workers;
  }

  /** The notice and error service. */
  NotificationService notifications() {
    return notifications;
  }

  /** The busy indicator. */
  ActivityIndicator activity() {
    return activity;
  }

  /** The memory budget. */
  MemoryBudget budget() {
    return budget;
  }

  // Caret time and selection difference.

  /**
   * Returns the times of the caret line and of the first and last selected line, as last read.
   *
   * @return the times before the offset; {@link TimeStatus#NONE} without a format
   */
  public TimeStatus timeStatus() {
    return caretTimes.status();
  }

  /**
   * The format, the zone or the offset changed: the times are read again when {@code reread}, and
   * the listeners reformat them.
   */
  void timeSettingsChanged(boolean reread) {
    if (reread) {
      caretTimes.reread();
    }
    fireTime();
  }

  /** Tells the views the times changed. */
  void fireTime() {
    for (DocumentViewListener listener : new ArrayList<DocumentViewListener>(listeners)) {
      listener.timeChanged();
    }
  }

  /** Maps the visible rows to their lines for a read: ascending, fewer at the end of the view. */
  private long[] visibleSourceLines(long top, int count) {
    long[] found = new long[count];
    RowCursor walk = rows();
    long line = sourceLine(top);
    int n = 0;
    while (n < count && line >= 0) {
      found[n++] = line;
      line = walk.next(line);
    }
    if (n == count) {
      return found;
    }
    long[] fewer = new long[n];
    System.arraycopy(found, 0, fewer, 0, n);
    return fewer;
  }

  private void requestLines() {
    if (closed) {
      return;
    }
    if (readInFlight) {
      readWanted = true;
      return;
    }
    final LineIndex index = document.index();
    final long top = viewport.topLine();
    final int count = Math.max(viewport.rows(), screenRows) + 1;
    final long firstColumn = wrap ? 0 : viewport.firstColumn();
    final int columns =
        wrap
            ? (int) Math.min(MAX_WRAP_COLUMNS, (long) viewport.columns() * screenRows)
            : viewport.columns() + COLUMN_MARGIN;
    final long[] source = visibleSourceLines(top, count);
    final Highlighter colors = highlighter;
    final boolean markers = whitespace;
    readInFlight =
        workers.tryStart(
            Workers.Kind.READ,
            new Runnable() {
              @Override
              public void run() {
                read(index, top, source, firstColumn, columns, colors, markers);
              }
            });
  }

  /**
   * Posts back on every outcome, so {@code readInFlight} always clears; an unexpected exception
   * still reaches the worker's uncaught-exception handler.
   */
  @WorkerThread
  private void read(
      LineIndex index,
      long top,
      long[] source,
      long firstColumn,
      int columns,
      Highlighter colors,
      boolean markers) {
    LineBlock block = null;
    String error = null;
    long charged = 0;
    boolean admitted = document.enter();
    try {
      if (!admitted) {
        return;
      }
      Highlighter.LineMatcher matcher = colors.isEmpty() ? null : colors.matcher();
      block =
          reader.readRows(
              index, top, source, source.length, firstColumn, columns, matcher, markers);
      long bytes = block.spanBytes();
      if (bytes > 0 && budget.reserve(MemoryShare.HIGHLIGHT, bytes)) {
        charged = bytes;
      } else if (bytes > 0) {
        block = block.withoutSpans();
      }
    } catch (ClosedChannelException e) {
      // Closed while reading: the controller is detached and drops the result.
      block = null;
    } catch (IOException | UncheckedIOException e) {
      error = LogText.oneLine(e.getMessage());
    } finally {
      if (admitted) {
        document.exit();
      }
      final LineBlock result = block;
      final String message = error;
      final long reserved = charged;
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              linesRead(result, message, reserved);
            }
          });
    }
  }

  private void linesRead(LineBlock block, String error, long reserved) {
    readInFlight = false;
    if (closed || block == null) {
      budget.release(MemoryShare.HIGHLIGHT, reserved);
    }
    if (closed) {
      return;
    }
    if (block != null) {
      budget.release(MemoryShare.HIGHLIGHT, spanCharge);
      spanCharge = reserved;
      lines = block;
      viewport.setMaxColumns(Math.max(viewport.maxColumns(), block.maxWidth()));
      fireContent();
    } else if (error != null) {
      LOG.warn("Cannot read {}: {}", LogText.oneLine(document.path()), LogText.oneLine(error));
    }
    if (readWanted) {
      readWanted = false;
      requestLines();
    }
  }

  /**
   * Posts back on every outcome, so {@code copyInFlight} always clears and the reservation and the
   * activity are always released.
   */
  @WorkerThread
  private void buildCopy(
      LineIndex index,
      TextExporter.Rows taken,
      final Request request,
      final TextListener listener,
      CancellationToken token) {
    TextExporter.Result built = TextExporter.Result.NONE;
    try {
      built = TextExporter.export(document, index, taken, request, budget, token);
    } finally {
      final TextExporter.Result result = built;
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              copied(request, listener, result);
            }
          });
    }
  }

  private void copied(Request request, TextListener listener, TextExporter.Result result) {
    copyInFlight = false;
    try {
      if (closed) {
        return;
      }
      String text = result.text();
      if (text != null) {
        switch (request.output()) {
          case CLIPBOARD, NUMBERED -> clipboard.setText(text);
          case LISTENER -> listener.textRead(text);
          case FILE ->
              notifications.showNotice(
                  result.cutLines() == 0
                      ? UiText.NOTICE_SAVED.format(result.rows(), request.file().getFileName())
                      : UiText.NOTICE_SAVED_CUT.format(
                          result.rows(), request.file().getFileName(), result.cutLines()));
        }
      } else if (result.refusal() != null) {
        UiText title = request.output() == Output.FILE ? UiText.SAVE_TITLE : UiText.COPY_TITLE;
        notifications.showError(title.text(), result.refusal());
      }
    } finally {
      budget.release(MemoryShare.COPY, result.charged());
      activity.setIdle();
    }
  }
}
