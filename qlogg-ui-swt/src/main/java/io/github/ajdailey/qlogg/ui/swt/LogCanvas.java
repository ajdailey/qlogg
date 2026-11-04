package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.KeySequencer;
import io.github.ajdailey.qlogg.app.command.KeyStroke;
import io.github.ajdailey.qlogg.app.command.Keymap;
import io.github.ajdailey.qlogg.app.config.FontSettings;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.LineViewController;
import io.github.ajdailey.qlogg.app.highlight.ColorLabel;
import io.github.ajdailey.qlogg.app.highlight.HighlightSettings;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.view.DocumentViewListener;
import io.github.ajdailey.qlogg.app.view.DocumentViewState;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.HighlightSpans;
import io.github.ajdailey.qlogg.core.model.LineBlock;
import io.github.ajdailey.qlogg.core.model.LineTerminator;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.model.Viewport;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ScrollBar;

/**
 * A log view: paints the visible rows of a {@link LineViewController} (the main view or the
 * filtered view) with a bullet column (mark or match), source line numbers, highlights, the current
 * line and the selection, and forwards keys, mouse, scroll bars and the context menu to it. It
 * reads only the controller's snapshot ({@link LineBlock}); rows the block does not hold yet stay
 * blank until the next read arrives. Focus, a click or a key makes it the view that commands go to;
 * a double click selects the word under the pointer within the decoded column window.
 *
 * <p>Text sits on a monospace grid from the font's average char width. A row holding chars outside
 * printable ASCII is drawn as one string, and its selection edges are measured with {@code
 * GC.textExtent}, so wide glyphs keep the highlight aligned. Painting allocates no SWT resource
 * except a highlight color seen for the first time, which {@link PaletteColors} keeps; the font and
 * colors are disposed with the canvas, the colors also when the palette changes, and the font when
 * {@link #setFont} replaces it. A font change or {@code SWT.ZoomChanged} remeasures the line height
 * and advance and refits the viewport in place.
 *
 * <p>A row paints its background (base, current line, then the whole-line highlight), the span
 * backgrounds and the selection, the text in the line's color, then each span with a text color and
 * the selection text again inside their clip. A translucent highlight is blended over the color
 * under it.
 *
 * <p>The gutter marks a collapsed entry's row with a triangle and a rule under the row. With
 * whitespace markers on, a tab's first cell shows an arrow, a trailing space a dot, a lone CR a
 * left arrow and the line end its terminator, drawn over the cells so columns do not move. An
 * Alt-drag, or any drag with Edit > Block Selection on, selects a block of columns. {@code
 * Alt}+wheel scrolls sideways and {@code Shift}+wheel by pages. With text wrap on, each line paints
 * as rows of the view's width on the column grid, and the view reports the lines that fit whole
 * ({@link LineViewController#wrapFitted}).
 */
public final class LogCanvas {
  /** Receives the pending key prefix for the status bar. */
  public interface PrefixListener {
    /**
     * The prefix changed.
     *
     * @param text for example {@code Ctrl+E -}, or empty when none is pending
     */
    void prefixChanged(String text);
  }

  private static final int GUTTER_PAD = 6;
  private static final int MIN_DIGITS = 4;

  /** Measured for the grid advance; over a long run a rounded extent errs little per column. */
  private static final String ADVANCE_SAMPLE = "0".repeat(1000);

  /** Bound on a column's pixel offset, so offsets and their differences stay in range. */
  private static final int MAX_OFFSET = Integer.MAX_VALUE / 4;

  private final Canvas canvas;

  /** Commands of the right-click menu, in order; null is a separator. */
  static final CommandId[] CONTEXT_MENU = {
    CommandId.TOGGLE_MARK,
    null,
    CommandId.COPY,
    CommandId.COPY_LINE,
    null,
    CommandId.FIND_SELECTION_FORWARD,
    CommandId.FIND_SELECTION_BACKWARD,
    null,
    CommandId.ADD_TO_SEARCH,
    CommandId.EXCLUDE_FROM_SEARCH,
    CommandId.REPLACE_SEARCH,
    null,
    CommandId.SET_SEARCH_START,
    CommandId.SET_SEARCH_END,
    CommandId.CLEAR_SEARCH_LIMITS,
    null,
    CommandId.SET_SELECTION_START,
    CommandId.SET_SELECTION_END,
    null,
    CommandId.SAVE_SPLIT,
    // Copy variants, save, scratchpad and folding.
    null,
    CommandId.COPY_WITH_LINE_NUMBERS,
    CommandId.SAVE_SELECTION,
    CommandId.SEND_TO_SCRATCHPAD,
    CommandId.TOGGLE_FOLD
  };

  private final LineViewController document;
  private final ApplicationController application;
  private final KeySequencer keys;
  private final Keymap keymapRef;
  private final PrefixListener prefix;
  private Font font;
  private boolean antialias;
  private PaletteColors colors;
  private int lineHeight;
  private double advance;
  private boolean dragging;
  private boolean dragBlock;
  private final DocumentViewListener viewListener =
      new DocumentViewListener() {
        @Override
        public void stateChanged(DocumentViewState state) {
          resized();
        }

        @Override
        public void contentChanged() {
          if (!canvas.isDisposed()) {
            if (document.wrap()) {
              document.wrapFitted(wrapFit());
            }
            updateScrollBars();
            canvas.redraw();
          }
        }
      };

  /**
   * Creates the view.
   *
   * @param parent the parent
   * @param document the main or filtered view's controller
   * @param application runs commands and tracks the focused view
   * @param keymap the key bindings
   * @param palette the colors
   * @param prefix receives the pending key prefix
   */
  public LogCanvas(
      Composite parent,
      LineViewController document,
      ApplicationController application,
      Keymap keymap,
      Palette palette,
      PrefixListener prefix) {
    this.document = document;
    this.application = application;
    this.keys = new KeySequencer(keymap);
    this.keymapRef = keymap;
    this.prefix = prefix;
    canvas =
        new Canvas(parent, SWT.V_SCROLL | SWT.H_SCROLL | SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
    FontSettings settings = application.preferences().options().font();
    font = createFont(settings);
    antialias = settings.antialias();
    colors = new PaletteColors(canvas.getDisplay(), palette);
    measure();
    canvas.setBackground(colors.background);
    listen();
    document.addListener(viewListener);
  }

  private Font createFont(FontSettings settings) {
    return new Font(
        canvas.getDisplay(),
        settings.family().isEmpty() ? Dialogs.monospaceName() : settings.family(),
        settings.effectiveSize(Dialogs.monospaceSize()),
        settings.bold() ? SWT.BOLD : SWT.NORMAL);
  }

  private void measure() {
    GC gc = new GC(canvas);
    try {
      gc.setFont(font);
      lineHeight = Math.max(1, gc.getFontMetrics().getHeight());
      advance = Math.max(1.0, gc.textExtent(ADVANCE_SAMPLE).x / (double) ADVANCE_SAMPLE.length());
    } finally {
      gc.dispose();
    }
  }

  /**
   * Replaces the font, remeasures the line height and advance, refits the viewport and repaints.
   *
   * @param settings the font settings
   */
  public void setFont(FontSettings settings) {
    if (canvas.isDisposed()) {
      return;
    }
    Font old = font;
    font = createFont(settings);
    antialias = settings.antialias();
    old.dispose();
    remeasure();
  }

  private void remeasure() {
    measure();
    resized();
    if (document.wrap()) {
      document.wrapFitted(wrapFit());
    }
    canvas.redraw();
  }

  private Menu contextMenu(Keymap keymap) {
    Menu menu = new Menu(canvas);
    boolean mac = SwtKeys.mac();
    for (final CommandId command : CONTEXT_MENU) {
      if (command == null) {
        new MenuItem(menu, SWT.SEPARATOR);
        continue;
      }
      MenuItem item = new MenuItem(menu, SWT.PUSH);
      String keys = keymap.keysText(command, mac);
      item.setText(keys.isEmpty() ? command.label() : command.label() + "\t" + keys);
      item.setData(command);
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              run(command);
            }
          });
    }
    new MenuItem(menu, SWT.SEPARATOR);
    MenuItem sets = new MenuItem(menu, SWT.CASCADE);
    sets.setText(UiText.GROUP_HIGHLIGHT.text());
    final Menu setsMenu = new Menu(menu);
    sets.setMenu(setsMenu);
    MenuItem labels = new MenuItem(menu, SWT.CASCADE);
    labels.setText(UiText.MENU_COLOR_LABEL.text());
    final Menu labelsMenu = new Menu(menu);
    labels.setMenu(labelsMenu);
    setsMenu.addListener(
        SWT.Show,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            fillSets(setsMenu);
          }
        });
    labelsMenu.addListener(
        SWT.Show,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            fillLabels(labelsMenu);
          }
        });
    return menu;
  }

  /** Lists None and the highlighter sets, the active ones checked. */
  void fillSets(Menu menu) {
    for (MenuItem item : menu.getItems()) {
      item.dispose();
    }
    HighlightSettings settings = application.highlightSettings();
    MenuItem none = new MenuItem(menu, SWT.CHECK);
    none.setText(CommandId.NO_HIGHLIGHTERS.label());
    none.setSelection(settings.active().isEmpty());
    none.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            run(CommandId.NO_HIGHLIGHTERS);
          }
        });
    for (final HighlightSet set : settings.sets()) {
      MenuItem item = new MenuItem(menu, SWT.CHECK);
      item.setText(set.name());
      item.setSelection(settings.isActive(set.id()));
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              application.toggleHighlightSet(set.id());
            }
          });
    }
  }

  /** Lists None and the color labels by name. */
  void fillLabels(Menu menu) {
    for (MenuItem item : menu.getItems()) {
      item.dispose();
    }
    MenuItem none = new MenuItem(menu, SWT.PUSH);
    none.setText(UiText.MENU_NONE.text());
    none.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            application.setFocusedView(document);
            application.removeColorLabel();
          }
        });
    new MenuItem(menu, SWT.SEPARATOR);
    List<ColorLabel> labels = application.highlightSettings().labels();
    boolean mac = SwtKeys.mac();
    for (int i = 0; i < labels.size(); i++) {
      final int index = i;
      MenuItem item = new MenuItem(menu, SWT.PUSH);
      CommandId command = CommandId.values()[CommandId.COLOR_LABEL_1.ordinal() + i];
      String keys = keymapText(command, mac);
      item.setText(keys.isEmpty() ? labels.get(i).name() : labels.get(i).name() + "\t" + keys);
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              application.setFocusedView(document);
              application.applyColorLabel(index);
            }
          });
    }
  }

  private String keymapText(CommandId command, boolean mac) {
    return keymapRef.keysText(command, mac);
  }

  /**
   * Recreates the colors from another palette and repaints.
   *
   * @param palette the palette
   */
  public void setPalette(Palette palette) {
    if (canvas.isDisposed() || palette == colors.palette) {
      return;
    }
    PaletteColors old = colors;
    colors = new PaletteColors(canvas.getDisplay(), palette);
    old.dispose();
    canvas.setBackground(colors.background);
    canvas.redraw();
  }

  /**
   * Returns the palette painted with.
   *
   * @return the palette
   */
  public Palette palette() {
    return colors.palette;
  }

  /** Runs a command with this view focused for it. */
  void run(CommandId command) {
    application.setFocusedView(document);
    application.execute(command);
  }

  /**
   * Returns the SWT control.
   *
   * @return the canvas
   */
  public Canvas control() {
    return canvas;
  }

  /**
   * Returns the controller shown.
   *
   * @return the controller
   */
  public LineViewController document() {
    return document;
  }

  /**
   * Returns the row height.
   *
   * @return pixels
   */
  public int lineHeight() {
    return lineHeight;
  }

  /**
   * Returns the grid's column advance, which can be fractional.
   *
   * @return pixels
   */
  double advance() {
    return advance;
  }

  /**
   * Returns the pixel offset of a grid column from the text's left edge, {@code round(column *
   * advance)}.
   *
   * @param column the column, counted from the text's first column; may be negative
   * @return pixels
   */
  int columnOffset(long column) {
    long x = Math.round(column * advance);
    return (int) Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, x));
  }

  /** The grid boundary nearest a pixel offset from the text's left edge. */
  private long columnAt(int textX) {
    return textX <= 0 ? 0 : (long) Math.floor(textX / advance + 0.5);
  }

  /** One cell's width for markers drawn inside a cell. */
  private int cellWidth() {
    return Math.max(1, (int) Math.round(advance));
  }

  private void listen() {
    canvas.addListener(
        SWT.Paint,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            paint(event.gc);
          }
        });
    canvas.addListener(
        SWT.Resize,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            resized();
          }
        });
    canvas.addListener(
        SWT.ZoomChanged,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!canvas.isDisposed()) {
              remeasure();
            }
          }
        });
    canvas.addListener(
        SWT.Traverse,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            switch (event.detail) {
              case SWT.TRAVERSE_ARROW_NEXT,
                  SWT.TRAVERSE_ARROW_PREVIOUS,
                  SWT.TRAVERSE_PAGE_NEXT,
                  SWT.TRAVERSE_PAGE_PREVIOUS,
                  SWT.TRAVERSE_ESCAPE,
                  SWT.TRAVERSE_RETURN ->
                  event.doit = false;
              default -> {
                // Tab and mnemonics traverse as usual.
              }
            }
          }
        });
    canvas.addListener(
        SWT.KeyDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            key(event);
          }
        });
    canvas.addListener(
        SWT.MouseDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            mouseDown(event);
          }
        });
    canvas.addListener(
        SWT.MouseDoubleClick,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.button == 1) {
              selectWord(hit(event.x, event.y));
            }
          }
        });
    canvas.addListener(
        SWT.MenuDetect,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            Point point = canvas.toControl(event.x, event.y);
            menuAt(point.x, point.y);
          }
        });
    canvas.addListener(
        SWT.FocusIn,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            application.setFocusedView(document);
          }
        });
    canvas.addListener(
        SWT.MouseMove,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (dragging) {
              drag(event);
            }
          }
        });
    canvas.addListener(
        SWT.MouseUp,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            dragging = false;
            dragBlock = false;
          }
        });
    canvas.addListener(
        SWT.MouseVerticalWheel,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            event.doit = false;
            wheel(event.count, event.stateMask);
          }
        });
    canvas.addListener(
        SWT.MouseHorizontalWheel,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            event.doit = false;
            document.scrollColumns(-event.count);
          }
        });
    canvas
        .getVerticalBar()
        .addListener(
            SWT.Selection,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                verticalBar(event.detail);
              }
            });
    canvas
        .getHorizontalBar()
        .addListener(
            SWT.Selection,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                document.setFirstColumn(canvas.getHorizontalBar().getSelection());
              }
            });
    canvas.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            document.removeListener(viewListener);
            font.dispose();
            colors.dispose();
          }
        });
  }

  private void key(Event event) {
    KeyStroke stroke = SwtKeys.stroke(event.stateMask, event.keyCode, event.character);
    if (stroke == null) {
      return;
    }
    KeySequencer.Result result = keys.press(stroke);
    if (result instanceof KeySequencer.Run found) {
      event.doit = false;
      prefix.prefixChanged("");
      run(found.command());
    } else if (result instanceof KeySequencer.Pending pending) {
      event.doit = false;
      prefix.prefixChanged(UiText.STATUS_KEY_PREFIX.format(pending.prefix().text(SwtKeys.mac())));
    } else {
      prefix.prefixChanged("");
    }
  }

  private void verticalBar(int detail) {
    Viewport viewport = document.viewport();
    switch (detail) {
      case SWT.ARROW_UP -> document.scrollLines(-1);
      case SWT.ARROW_DOWN -> document.scrollLines(1);
      case SWT.PAGE_UP -> document.scrollLines(-viewport.rows());
      case SWT.PAGE_DOWN -> document.scrollLines(viewport.rows());
      default -> document.setScrollbarSelection(canvas.getVerticalBar().getSelection());
    }
  }

  private void mouseDown(Event event) {
    if (event.button != 1) {
      return;
    }
    canvas.setFocus();
    application.setFocusedView(document);
    TextPosition position = hit(event.x, event.y);
    if (event.x < bulletWidth()) {
      document.select(Selection.caretAt(new TextPosition(position.line(), 0)));
      document.toggleMarks();
      return;
    }
    Selection current = document.selection();
    dragBlock = (event.stateMask & SWT.ALT) != 0 || application.blockMode();
    if ((event.stateMask & SWT.MOD2) != 0) {
      select(new Selection(current.anchor(), position));
    } else {
      select(Selection.caretAt(position));
    }
    dragging = true;
  }

  /** Selects a stream, or a block during a block drag. */
  private void select(Selection next) {
    if (dragBlock && !next.isEmpty()) {
      document.selectBlock(next);
    } else {
      document.select(next);
    }
  }

  private void drag(Event event) {
    Rectangle area = canvas.getClientArea();
    if (event.y < 0) {
      document.scrollLines(-1);
    } else if (event.y >= area.height) {
      document.scrollLines(1);
    }
    TextPosition position = hit(event.x, Math.max(0, Math.min(event.y, area.height - 1)));
    select(new Selection(document.selection().anchor(), position));
  }

  /**
   * Handles a vertical wheel: font zoom with {@code Ctrl} (Command on macOS), columns with {@code
   * Alt}, pages with {@code Shift}, else lines.
   *
   * @param count the wheel's count; positive scrolls up or left
   * @param stateMask the modifiers held
   */
  void wheel(int count, int stateMask) {
    if ((stateMask & SWT.MOD1) != 0) {
      if (count != 0) {
        application.zoomFont(count > 0 ? 1 : -1);
      }
    } else if ((stateMask & SWT.ALT) != 0) {
      document.scrollColumns(-count);
    } else if ((stateMask & SWT.SHIFT) != 0) {
      document.scrollLines((long) -Integer.signum(count) * document.viewport().rows());
    } else {
      document.scrollLines(-count);
    }
  }

  /**
   * Prepares the context menu for a right click: builds it on the first one, and a click outside
   * the selection moves the caret to the clicked row, so Copy This Line and Mark act on it. SWT
   * shows the control's menu after its {@code MenuDetect} listeners return.
   *
   * @param x the pointer's x in the canvas
   * @param y the pointer's y in the canvas
   */
  void menuAt(int x, int y) {
    if (canvas.getMenu() == null) {
      canvas.setMenu(contextMenu(keymapRef));
    }
    application.setFocusedView(document);
    TextPosition position = hit(x, y);
    Selection current = document.selection();
    boolean inside =
        position.line() >= current.start().line() && position.line() <= current.end().line();
    if (current.isEmpty() || !inside) {
      document.select(Selection.caretAt(position));
    }
  }

  /**
   * Selects the word around a position: letters, digits and underscores, within the decoded column
   * window.
   *
   * @param position the row and column
   */
  void selectWord(TextPosition position) {
    LineBlock block = document.lines();
    long line = position.line();
    if (!block.contains(line)) {
      return;
    }
    String text = block.text(line);
    long column = position.column() - block.firstColumn();
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
    long first = block.firstColumn();
    document.select(
        new Selection(new TextPosition(line, first + start), new TextPosition(line, first + end)));
  }

  private static boolean wordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  /** Maps a point to a line and column on the grid. */
  TextPosition hit(int x, int y) {
    if (document.wrap()) {
      return wrappedHit(x, y);
    }
    Viewport viewport = document.viewport();
    long count = viewport.lineCount();
    long line = viewport.topLine() + Math.max(0, y) / lineHeight;
    line = Math.max(0, Math.min(line, count - 1));
    int textX = x - gutterWidth();
    long column = columnAt(textX);
    return new TextPosition(line, viewport.firstColumn() + column);
  }

  /** Returns the width of the bullet column in pixels. */
  int bulletWidth() {
    return Math.max(8, lineHeight * 3 / 4);
  }

  /** Returns the width of the gutter in pixels: the bullet column and, when on, line numbers. */
  int gutterWidth() {
    if (!document.lineNumbers()) {
      return bulletWidth();
    }
    long lines = document.owner().viewport().lineCount();
    int digits = Math.max(MIN_DIGITS, Long.toString(lines).length());
    return bulletWidth() + (int) Math.ceil(digits * advance) + 2 * GUTTER_PAD;
  }

  private void resized() {
    if (canvas.isDisposed()) {
      return;
    }
    Rectangle area = canvas.getClientArea();
    int rows = Math.max(1, area.height / lineHeight);
    int columns = Math.max(1, (int) ((area.width - gutterWidth()) / advance));
    document.viewportResized(rows, columns);
    updateScrollBars();
  }

  private void updateScrollBars() {
    Viewport viewport = document.viewport();
    ScrollBar vertical = canvas.getVerticalBar();
    int thumb = viewport.scrollbarThumb();
    vertical.setValues(
        viewport.scrollbarSelection(),
        0,
        Math.max(1, viewport.scrollbarMaximum()),
        Math.max(1, thumb),
        1,
        Math.max(1, thumb));
    ScrollBar horizontal = canvas.getHorizontalBar();
    int columns = viewport.columns();
    int max =
        document.wrap()
            ? columns
            : (int) Math.min(Integer.MAX_VALUE, Math.max(viewport.maxColumns(), columns));
    horizontal.setValues((int) viewport.firstColumn(), 0, max, columns, 1, columns);
  }

  private void paint(GC gc) {
    if (document.wrap()) {
      paintWrapped(gc);
      return;
    }
    colors.trim();
    Rectangle area = canvas.getClientArea();
    gc.setFont(font);
    if (antialias) {
      gc.setTextAntialias(SWT.ON);
    }
    gc.setBackground(colors.background);
    gc.fillRectangle(area);
    Viewport viewport = document.viewport();
    LineBlock block = document.lines();
    Selection selection = document.selection();
    TextPosition start = selection.start();
    TextPosition end = selection.end();
    long caretLine = selection.caret().line();
    int gutter = gutterWidth();
    int rows = area.height / lineHeight + 1;
    long count = viewport.lineCount();
    int base = PaletteColors.packed(colors.palette.background());
    int current = PaletteColors.packed(colors.palette.currentLine());
    for (int row = 0; row < rows; row++) {
      long line = viewport.topLine() + row;
      if (line >= count) {
        break;
      }
      int y = row * lineHeight;
      String text = block.contains(line) ? block.text(line) : null;
      HighlightSpans spans = text == null ? HighlightSpans.NONE : block.spans(line);
      int under = line == caretLine ? current : base;
      if (spans.lineBackground() != 0) {
        under = PaletteColors.blend(spans.lineBackground(), under);
      }
      if (under != base) {
        gc.setBackground(colors.cached(under));
        gc.fillRectangle(gutter, y, area.width - gutter, lineHeight);
      }
      long shift = viewport.firstColumn() - block.firstColumn();
      int textX = gutter + columnOffset(-shift);
      Rectangle[] spanRects = new Rectangle[spans.count()];
      for (int i = 0; i < spans.count(); i++) {
        spanRects[i] =
            selectionRect(
                gc, text, block, viewport, spans.start(i), spans.end(i), gutter, y, area.width);
        if (spanRects[i] != null && spans.background(i) != 0) {
          gc.setBackground(colors.cached(PaletteColors.blend(spans.background(i), under)));
          gc.fillRectangle(spanRects[i]);
        }
      }
      Rectangle selected = null;
      if (!selection.isEmpty() && line >= start.line() && line <= end.line()) {
        long from = line == start.line() ? start.column() : 0;
        long to = line == end.line() ? end.column() : Long.MAX_VALUE;
        if (document.blockSelection()) {
          from = Math.min(selection.anchor().column(), selection.caret().column());
          to = Math.max(selection.anchor().column(), selection.caret().column());
        }
        selected = selectionRect(gc, text, block, viewport, from, to, gutter, y, area.width);
      }
      if (selected != null) {
        gc.setBackground(colors.selection);
        gc.fillRectangle(selected);
      }
      if (text != null && !text.isEmpty()) {
        Rectangle rowClip = new Rectangle(gutter, y, area.width - gutter, lineHeight);
        gc.setClipping(rowClip);
        Color rowText =
            spans.lineForeground() == 0
                ? colors.text
                : colors.cached(spans.lineForeground() & 0xffffff);
        gc.setForeground(rowText);
        gc.drawString(text, textX, y, true);
        for (int i = 0; i < spans.count(); i++) {
          if (spanRects[i] != null && spans.foreground(i) != 0) {
            gc.setClipping(spanRects[i].intersection(rowClip));
            gc.setForeground(colors.cached(spans.foreground(i) & 0xffffff));
            gc.drawString(text, textX, y, true);
          }
        }
        if (selected != null) {
          gc.setClipping(selected.intersection(rowClip));
          gc.setForeground(colors.selectionText);
          gc.drawString(text, textX, y, true);
        }
        gc.setClipping((Rectangle) null);
      }
      if (text != null) {
        paintMarkers(gc, block, line, text, gutter, shift, y, 0, Long.MAX_VALUE, true);
      }
    }
    paintGutter(gc, viewport, block, rows, gutter, area.height);
  }

  // Whitespace markers, fold markers and text wrap.

  /**
   * Draws a row's whitespace markers and line end on the grid, for the block columns {@code [from,
   * to)}; {@code last} allows the line end at {@code to}.
   *
   * @param origin the x of block column {@code shift}
   * @param shift the block column drawn at {@code origin}
   */
  private void paintMarkers(
      GC gc,
      LineBlock block,
      long line,
      String text,
      int origin,
      long shift,
      int y,
      long from,
      long to,
      boolean last) {
    String marks = block.whitespace(line);
    if (marks == null) {
      return;
    }
    gc.setForeground(colors.lineNumbers);
    int middle = y + lineHeight / 2;
    long column = 0;
    for (int i = 0; i < marks.length() && i < text.length(); i++) {
      if (Character.isLowSurrogate(text.charAt(i))) {
        continue;
      }
      int x = origin + columnOffset(column - shift);
      if (column < from || column >= to) {
        column++;
        continue;
      }
      switch (marks.charAt(i)) {
        case LineBlock.TAB_MARK -> arrow(gc, x, middle, true);
        case LineBlock.SPACE_MARK -> gc.drawPoint(x + cellWidth() / 2, middle);
        case LineBlock.CR_MARK -> arrow(gc, x, middle, false);
        default -> {
          // Not whitespace.
        }
      }
      column++;
    }
    long end = block.width(line) - block.firstColumn();
    if (end < 0 || end > block.columns() || end < from || end > to || end == to && !last) {
      return;
    }
    int x = origin + columnOffset(Math.max(0, end) - shift);
    int cell = cellWidth();
    LineTerminator terminator = block.terminator(line);
    if (terminator == LineTerminator.CRLF) {
      arrow(gc, x, middle, false);
      x += cell;
    }
    if (terminator != LineTerminator.NONE) {
      int bottom = y + lineHeight - lineHeight / 4;
      gc.drawLine(x + cell - 2, y + lineHeight / 4, x + cell - 2, bottom);
      gc.drawLine(x + 2, bottom, x + cell - 2, bottom);
    }
  }

  /** A small arrow inside one cell, right or left. */
  private void arrow(GC gc, int x, int middle, boolean right) {
    int left = x + 2;
    int far = x + cellWidth() - 2;
    int head = Math.max(2, cellWidth() / 3);
    gc.drawLine(left, middle, far, middle);
    if (right) {
      gc.drawLine(far, middle, far - head, middle - head);
      gc.drawLine(far, middle, far - head, middle + head);
    } else {
      gc.drawLine(left, middle, left + head, middle - head);
      gc.drawLine(left, middle, left + head, middle + head);
    }
  }

  /** Marks a collapsed entry's row: a triangle at the gutter's right edge and a rule under it. */
  private void paintFold(GC gc, int gutter, int y, int width) {
    int size = Math.max(4, lineHeight / 3);
    int x = gutter - GUTTER_PAD + 1;
    int top = y + (lineHeight - size) / 2;
    gc.setBackground(colors.lineNumbers);
    gc.fillPolygon(new int[] {x, top, x + size, top + size / 2, x, top + size});
    gc.setForeground(colors.lineNumbers);
    gc.drawLine(gutter, y + lineHeight - 1, width, y + lineHeight - 1);
  }

  /** Columns per wrapped row. */
  private int wrapColumns() {
    return Math.max(1, document.viewport().columns());
  }

  /** Rows a line takes when wrapped; a line not read yet takes one. */
  int wrappedRows(LineBlock block, long line) {
    if (!block.contains(line)) {
      return 1;
    }
    long width = Math.min(block.width(line), (long) block.columns());
    return (int) Math.max(1, (width + wrapColumns() - 1) / wrapColumns());
  }

  /**
   * Counts the lines from the top that fit the view whole when wrapped.
   *
   * @return at least 1
   */
  int wrapFit() {
    Viewport viewport = document.viewport();
    LineBlock block = document.lines();
    int available = Math.max(1, canvas.getClientArea().height / lineHeight);
    int used = 0;
    int lines = 0;
    for (long line = viewport.topLine(); line < viewport.lineCount(); line++) {
      used += wrappedRows(block, line);
      if (used > available) {
        break;
      }
      lines++;
    }
    return Math.max(1, Math.min(lines, document.screenRows()));
  }

  /** Maps a point to a line and column with text wrap on. */
  private TextPosition wrappedHit(int x, int y) {
    Viewport viewport = document.viewport();
    LineBlock block = document.lines();
    long count = viewport.lineCount();
    int target = Math.max(0, y) / lineHeight;
    int row = 0;
    long line = viewport.topLine();
    while (line < count - 1 && row + wrappedRows(block, line) <= target) {
      row += wrappedRows(block, line);
      line++;
    }
    line = Math.max(0, Math.min(line, count - 1));
    int segment = Math.max(0, Math.min(target - row, wrappedRows(block, line) - 1));
    int textX = x - gutterWidth();
    long column = columnAt(textX);
    return new TextPosition(line, (long) segment * wrapColumns() + Math.min(column, wrapColumns()));
  }

  /** Paints with text wrap on: each line as rows of the view's width, on the column grid. */
  private void paintWrapped(GC gc) {
    colors.trim();
    Rectangle area = canvas.getClientArea();
    gc.setFont(font);
    gc.setBackground(colors.background);
    gc.fillRectangle(area);
    Viewport viewport = document.viewport();
    LineBlock block = document.lines();
    Selection selection = document.selection();
    TextPosition start = selection.start();
    TextPosition end = selection.end();
    long caretLine = selection.caret().line();
    int gutter = gutterWidth();
    int columns = wrapColumns();
    int base = PaletteColors.packed(colors.palette.background());
    int current = PaletteColors.packed(colors.palette.currentLine());
    gc.setBackground(colors.gutter);
    gc.fillRectangle(0, 0, gutter, area.height);
    int y = 0;
    for (long line = viewport.topLine(); line < viewport.lineCount() && y < area.height; line++) {
      String text = block.contains(line) ? block.text(line) : null;
      HighlightSpans spans = text == null ? HighlightSpans.NONE : block.spans(line);
      int segments = wrappedRows(block, line);
      int under = line == caretLine ? current : base;
      if (spans.lineBackground() != 0) {
        under = PaletteColors.blend(spans.lineBackground(), under);
      }
      paintWrappedGutter(gc, block, line, y, gutter);
      for (int k = 0; k < segments && y < area.height; k++) {
        long from = (long) k * columns;
        long to = from + columns;
        if (under != base) {
          gc.setBackground(colors.cached(under));
          gc.fillRectangle(gutter, y, area.width - gutter, lineHeight);
        }
        Rectangle rowClip = new Rectangle(gutter, y, area.width - gutter, lineHeight);
        for (int i = 0; i < spans.count(); i++) {
          Rectangle r = gridRect(spans.start(i), spans.end(i), from, to, gutter, y);
          if (r != null && spans.background(i) != 0) {
            gc.setBackground(colors.cached(PaletteColors.blend(spans.background(i), under)));
            gc.fillRectangle(r);
          }
        }
        Rectangle selected = null;
        if (!selection.isEmpty() && line >= start.line() && line <= end.line()) {
          long a = line == start.line() ? start.column() : 0;
          long b = line == end.line() ? end.column() : Long.MAX_VALUE;
          selected = gridRect(a, b, from, to, gutter, y);
          if (selected != null) {
            gc.setBackground(colors.selection);
            gc.fillRectangle(selected);
          }
        }
        if (text != null && !text.isEmpty()) {
          String part =
              text.substring(
                  charIndex(text, Math.min(from, text.length())),
                  charIndex(text, Math.min(to, text.length())));
          gc.setClipping(rowClip);
          gc.setForeground(
              spans.lineForeground() == 0
                  ? colors.text
                  : colors.cached(spans.lineForeground() & 0xffffff));
          gc.drawString(part, gutter, y, true);
          for (int i = 0; i < spans.count(); i++) {
            Rectangle r = gridRect(spans.start(i), spans.end(i), from, to, gutter, y);
            if (r != null && spans.foreground(i) != 0) {
              gc.setClipping(r.intersection(rowClip));
              gc.setForeground(colors.cached(spans.foreground(i) & 0xffffff));
              gc.drawString(part, gutter, y, true);
            }
          }
          if (selected != null) {
            gc.setClipping(selected.intersection(rowClip));
            gc.setForeground(colors.selectionText);
            gc.drawString(part, gutter, y, true);
          }
          gc.setClipping((Rectangle) null);
        }
        if (text != null) {
          paintMarkers(gc, block, line, text, gutter, from, y, from, to, k == segments - 1);
        }
        y += lineHeight;
      }
      if (document.folded(block.contains(line) ? block.sourceLine(line) : -1)) {
        paintFold(gc, gutter, y - lineHeight, area.width);
      }
    }
  }

  /** The grid rectangle of columns {@code [a, b)} within a wrapped row's {@code [from, to)}. */
  private Rectangle gridRect(long a, long b, long from, long to, int gutter, int y) {
    long left = Math.max(a, from);
    long right = Math.min(b, to);
    if (right <= left) {
      return null;
    }
    int x = gutter + columnOffset(left - from);
    return new Rectangle(x, y, gutter + columnOffset(right - from) - x, lineHeight);
  }

  private void paintWrappedGutter(GC gc, LineBlock block, long line, int y, int gutter) {
    if (!block.contains(line)) {
      return;
    }
    long source = block.sourceLine(line);
    int bullet = bulletWidth();
    int diameter = Math.max(4, Math.min(bullet, lineHeight) - 6);
    Color marker =
        document.marked(source)
            ? colors.markMarker
            : document.matched(source) ? colors.matchMarker : null;
    if (marker != null) {
      gc.setBackground(marker);
      gc.fillOval((bullet - diameter) / 2, y + (lineHeight - diameter) / 2, diameter, diameter);
    }
    if (document.lineNumbers()) {
      gc.setForeground(colors.lineNumbers);
      String number = Long.toString(source + 1);
      Point size = gc.stringExtent(number);
      gc.drawString(number, gutter - GUTTER_PAD - size.x, y, true);
    }
  }

  private void paintGutter(
      GC gc, Viewport viewport, LineBlock block, int rows, int gutter, int height) {
    gc.setBackground(colors.gutter);
    gc.fillRectangle(0, 0, gutter, height);
    gc.setForeground(colors.lineNumbers);
    long count = viewport.lineCount();
    int bullet = bulletWidth();
    int diameter = Math.max(4, Math.min(bullet, lineHeight) - 6);
    boolean numbers = document.lineNumbers();
    for (int row = 0; row < rows; row++) {
      long line = viewport.topLine() + row;
      if (line >= count) {
        break;
      }
      if (!block.contains(line)) {
        continue;
      }
      long source = block.sourceLine(line);
      int y = row * lineHeight;
      Color marker =
          document.marked(source)
              ? colors.markMarker
              : document.matched(source) ? colors.matchMarker : null;
      if (marker != null) {
        gc.setBackground(marker);
        gc.fillOval((bullet - diameter) / 2, y + (lineHeight - diameter) / 2, diameter, diameter);
      }
      if (numbers) {
        String number = Long.toString(source + 1);
        Point size = gc.stringExtent(number);
        gc.drawString(number, gutter - GUTTER_PAD - size.x, y, true);
      }
      if (document.folded(source)) {
        paintFold(gc, gutter, y, canvas.getClientArea().width);
        gc.setForeground(colors.lineNumbers);
      }
    }
  }

  /** The selected part of a row in pixels, or null when it is off screen. */
  private Rectangle selectionRect(
      GC gc,
      String text,
      LineBlock block,
      Viewport viewport,
      long from,
      long to,
      int gutter,
      int y,
      int width) {
    long first = viewport.firstColumn();
    int left = gutter + columnX(gc, text, block, viewport, Math.max(from, first) - first);
    int right =
        to == Long.MAX_VALUE
            ? width
            : gutter + columnX(gc, text, block, viewport, Math.max(to, first) - first);
    if (to == Long.MAX_VALUE || right <= left) {
      right = Math.max(right, left + cellWidth() / 2);
    }
    if (right <= gutter || left >= width) {
      return null;
    }
    return new Rectangle(left, y, right - left, lineHeight);
  }

  /**
   * Pixel offset of a visible column from the text's left edge. A row outside printable ASCII is
   * measured with {@code textExtent} when the block starts at the visible column; otherwise the
   * grid applies.
   */
  private int columnX(GC gc, String text, LineBlock block, Viewport viewport, long column) {
    if (text == null
        || printableAscii(text)
        || block.firstColumn() != viewport.firstColumn()
        || column > text.length()) {
      return columnOffset(column);
    }
    return gc.textExtent(text.substring(0, charIndex(text, column)), SWT.DRAW_TRANSPARENT).x;
  }

  /** Index of the char at a column of a cut line (tabs are already spaces). */
  private static int charIndex(String text, long column) {
    long seen = 0;
    int i = 0;
    while (i < text.length() && seen < column) {
      if (!Character.isLowSurrogate(text.charAt(i))) {
        seen++;
      }
      i++;
    }
    while (i < text.length() && Character.isLowSurrogate(text.charAt(i))) {
      i++;
    }
    return i;
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
}
