package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.KeySequencer;
import io.github.ajdailey.qlogg.app.command.KeyStroke;
import io.github.ajdailey.qlogg.app.command.Keymap;
import io.github.ajdailey.qlogg.app.config.FontSettings;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.CommandStates;
import io.github.ajdailey.qlogg.app.controller.LineViewController;
import io.github.ajdailey.qlogg.app.highlight.ColorLabel;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.view.DocumentViewListener;
import io.github.ajdailey.qlogg.app.view.DocumentViewState;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.LineBlock;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.model.Viewport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Slider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A log view: paints the visible rows of a {@link LineViewController} (the main view or the
 * filtered view) with a bullet column (mark or match), source line numbers, highlights, the current
 * line and the selection, and forwards keys, mouse, scroll bars and the context menu to it. It
 * reads only the controller's snapshot ({@link LineBlock}); rows the block does not hold yet stay
 * blank until the next read arrives. Focus, a click or a key makes it the view that commands go to;
 * a double click selects the word under the pointer within the decoded column window.
 *
 * <p>Column geometry and hit testing are a {@link TextGrid} measured from the font; each screen row
 * is painted by the {@link RowPainter}, one row per line or, with text wrap on, one per segment of
 * the view's width. Painting allocates no SWT resource except a highlight color seen for the first
 * time, which {@link PaletteColors} keeps; the font, colors and painter are disposed with the
 * canvas, the colors also when the palette changes, and the font when {@link #setFont} replaces it.
 * A font change or {@code SWT.ZoomChanged} remeasures the grid and refits the viewport in place.
 *
 * <p>The gutter marks a collapsed entry's row with a triangle and a rule under the row. An
 * Alt-drag, or any drag with Edit > Block Selection on, selects a block of columns. A drag held
 * above or below the view scrolls one line per {@link #AUTOSCROLL_MILLIS} on a {@link
 * SwtUiDispatcher} timer, which stops on release, focus loss and dispose. {@code Alt}+wheel scrolls
 * sideways and {@code Shift}+wheel by pages. With text wrap on, the view reports the lines that fit
 * whole ({@link LineViewController#wrapFitted}).
 *
 * <p>The canvas sits in a {@link #frame()} with a {@link Slider} as the vertical scroll bar at its
 * right, so a side control ({@link #addSide}) goes between the text and the scroll bar. The canvas
 * keeps its native horizontal scroll bar.
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

  /** Measured for the grid advance; over a long run a rounded extent errs little per column. */
  private static final String ADVANCE_SAMPLE = "0".repeat(1000);

  /** Startup timing marker: the benchmark harness waits for this DEBUG line on standard error. */
  static final String FIRST_TEXT_PAINT = "First paint of file text";

  private static final Logger LOG = LoggerFactory.getLogger(LogCanvas.class);

  /** Set by the first paint in this process that draws a loaded line. */
  private static final AtomicBoolean TEXT_PAINTED = new AtomicBoolean();

  private final Composite frame;
  private final Canvas canvas;
  private final Slider vertical;

  /** Commands of the right-click menu, in order; null is a separator. */
  static final CommandId[] CONTEXT_MENU = {
    CommandId.TOGGLE_MARK,
    CommandId.EDIT_MARK_COMMENT,
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
    CommandId.TOGGLE_FOLD,
    // Time sync.
    null,
    CommandId.SCROLL_TABS_TO_TIME
  };

  private final LineViewController document;
  private final ApplicationController application;
  private final KeySequencer keys;
  private final Keymap keymapRef;
  private final PrefixListener prefix;
  private Font font;
  private boolean antialias;
  private PaletteColors colors;
  private TextGrid grid;
  private final RowPainter painter;
  private boolean dragging;
  private boolean dragBlock;

  // Drag autoscroll.
  /** Interval between autoscroll steps while a drag holds the pointer above or below the view. */
  static final int AUTOSCROLL_MILLIS = 50;

  private int dragX;
  private int dragY;
  private boolean autoscrolling;
  private final SwtUiDispatcher timers;
  private final Runnable autoscroll =
      new Runnable() {
        @Override
        public void run() {
          autoscrollStep();
        }
      };
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
    frame = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(2, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    layout.horizontalSpacing = 0;
    frame.setLayout(layout);
    canvas = new Canvas(frame, SWT.H_SCROLL | SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
    canvas.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    vertical = new Slider(frame, SWT.VERTICAL);
    vertical.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, true));
    FontSettings settings = application.preferences().options().font();
    font = createFont(settings);
    antialias = settings.antialias();
    colors = new PaletteColors(canvas.getDisplay(), palette);
    painter = new RowPainter(canvas.getDisplay(), font);
    timers = new SwtUiDispatcher(canvas.getDisplay());
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
      grid =
          new TextGrid(
              gc.getFontMetrics().getHeight(),
              gc.textExtent(ADVANCE_SAMPLE).x / (double) ADVANCE_SAMPLE.length());
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
    painter.setFont(font);
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
    // Time sync.
    MenuItem timeSync = new MenuItem(menu, SWT.CASCADE);
    timeSync.setText(UiText.MENU_TIME_SYNC.text());
    final Menu timeSyncMenu = new Menu(menu);
    timeSync.setMenu(timeSyncMenu);
    timeSyncMenu.addListener(
        SWT.Show,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            TimeSyncMenu.fill(timeSyncMenu, application.windowSet().timeSync(), document.owner());
          }
        });
    return menu;
  }

  /** Lists None and the highlighter sets, the active ones checked. */
  void fillSets(Menu menu) {
    for (MenuItem item : menu.getItems()) {
      item.dispose();
    }
    CommandStates states = new CommandStates(application);
    MenuItem none = new MenuItem(menu, SWT.CHECK);
    none.setText(CommandId.NO_HIGHLIGHTERS.label());
    none.setSelection(states.checked(CommandId.NO_HIGHLIGHTERS));
    none.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            run(CommandId.NO_HIGHLIGHTERS);
          }
        });
    for (CommandStates.SetChoice choice : states.highlightSets()) {
      final HighlightSet set = choice.set();
      MenuItem item = new MenuItem(menu, SWT.CHECK);
      item.setText(set.name());
      item.setSelection(choice.active());
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
   * Returns the composite holding the canvas, any side control and the vertical scroll bar. Lay out
   * and dispose the view through it.
   *
   * @return the frame
   */
  public Composite frame() {
    return frame;
  }

  /**
   * Returns the vertical scroll bar.
   *
   * @return the slider
   */
  Slider verticalScrollBar() {
    return vertical;
  }

  /**
   * Places a control between the canvas and the vertical scroll bar.
   *
   * @param side a child of {@link #frame()}
   */
  public void addSide(Control side) {
    side.moveAbove(vertical);
    ((GridLayout) frame.getLayout()).numColumns = frame.getChildren().length;
    frame.layout(true);
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
    return grid.lineHeight();
  }

  /**
   * Returns the grid's column advance, which can be fractional.
   *
   * @return pixels
   */
  double advance() {
    return grid.advance();
  }

  /**
   * Returns the pixel offset of a grid column from the text's left edge ({@link
   * TextGrid#columnOffset}).
   *
   * @param column the column, counted from the text's first column; may be negative
   * @return pixels
   */
  int columnOffset(long column) {
    return grid.columnOffset(column);
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
              document.selectWordAt(wordHit(event.x, event.y));
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
    // Mark comment tooltip.
    Listener tooltip =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            commentTooltip(event.type == SWT.MouseMove ? event.x : -1, event.y);
          }
        };
    canvas.addListener(SWT.MouseMove, tooltip);
    canvas.addListener(SWT.MouseExit, tooltip);
    canvas.addListener(
        SWT.MouseUp,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            endDrag();
          }
        });
    canvas.addListener(
        SWT.FocusOut,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            endDrag();
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
    vertical.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            verticalBar(event.detail);
            if (vertical.isFocusControl()) {
              canvas.setFocus();
            }
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
            endDrag();
            document.removeListener(viewListener);
            painter.dispose();
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
      default -> document.setScrollbarSelection(vertical.getSelection());
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
      // Navigation history.
      document.rowChosen();
    }
    dragging = true;
  }

  /**
   * Shows the comment of the mark under the pointer as the canvas tooltip while the pointer is in
   * the bullet column, and no tooltip elsewhere.
   *
   * @param x the pointer's x, or -1 when it left the canvas
   * @param y the pointer's y
   */
  void commentTooltip(int x, int y) {
    String comment = null;
    Viewport viewport = document.viewport();
    boolean below =
        !document.wrap() && viewport.topLine() + y / grid.lineHeight() >= viewport.lineCount();
    if (x >= 0 && x < bulletWidth() && !dragging && !below) {
      long source = document.sourceLine(hit(x, y).line());
      comment = source >= 0 && document.marked(source) ? document.markComment(source) : null;
    }
    String shown = canvas.getToolTipText();
    if (comment == null ? shown != null : !comment.equals(shown)) {
      canvas.setToolTipText(comment);
    }
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
    dragX = event.x;
    dragY = event.y;
    if (autoscrolling) {
      extendDrag();
    } else {
      autoscrollStep();
    }
  }

  /** Scrolls one line toward a pointer held above or below the view and rearms the timer. */
  private void autoscrollStep() {
    autoscrolling = false;
    if (!dragging || canvas.isDisposed()) {
      return;
    }
    int direction = dragY < 0 ? -1 : dragY >= canvas.getClientArea().height ? 1 : 0;
    if (direction != 0) {
      document.scrollLines(direction);
      autoscrolling = true;
      timers.timerExec(AUTOSCROLL_MILLIS, autoscroll);
    }
    extendDrag();
  }

  /** Moves the drag's caret to the pointer, clamped to the view's rows. */
  private void extendDrag() {
    Rectangle area = canvas.getClientArea();
    TextPosition position = hit(dragX, Math.max(0, Math.min(dragY, area.height - 1)));
    select(new Selection(document.selection().anchor(), position));
  }

  private void endDrag() {
    dragging = false;
    dragBlock = false;
    if (autoscrolling) {
      autoscrolling = false;
      timers.cancelTimer(autoscroll);
    }
  }

  /**
   * Returns whether the drag autoscroll timer is armed.
   *
   * @return true while a drag holds the pointer above or below the view
   */
  boolean autoscrolling() {
    return autoscrolling;
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

  /** Maps a point to a line and column on the grid. */
  TextPosition hit(int x, int y) {
    Viewport viewport = document.viewport();
    int textX = x - gutterWidth();
    if (document.wrap()) {
      return grid.wrappedHit(
          viewport.topLine(),
          viewport.lineCount(),
          wrappedRows(document.lines()),
          wrapColumns(),
          textX,
          y);
    }
    return grid.hit(viewport.topLine(), viewport.lineCount(), viewport.firstColumn(), textX, y);
  }

  /**
   * Maps a double click to a line and column: as {@link #hit}, except that with text wrap on a
   * point past a segment's right edge stays on that segment ({@link TextGrid#wrappedWordHit}).
   */
  TextPosition wordHit(int x, int y) {
    if (!document.wrap()) {
      return hit(x, y);
    }
    Viewport viewport = document.viewport();
    return grid.wrappedWordHit(
        viewport.topLine(),
        viewport.lineCount(),
        wrappedRows(document.lines()),
        wrapColumns(),
        x - gutterWidth(),
        y);
  }

  /** Returns the width of the bullet column in pixels. */
  int bulletWidth() {
    return grid.bulletWidth();
  }

  /** Returns the width of the gutter in pixels: the bullet column and, when on, line numbers. */
  int gutterWidth() {
    return grid.gutterWidth(document.lineNumbers(), document.owner().viewport().lineCount());
  }

  private void resized() {
    if (canvas.isDisposed()) {
      return;
    }
    Rectangle area = canvas.getClientArea();
    document.viewportResized(grid.rows(area.height), grid.columns(area.width - gutterWidth()));
    updateScrollBars();
  }

  private void updateScrollBars() {
    Viewport viewport = document.viewport();
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

  /** Columns per wrapped row. */
  private int wrapColumns() {
    return Math.max(1, document.viewport().columns());
  }

  /** Rows a line takes when wrapped; a line not read yet takes one. */
  int wrappedRows(LineBlock block, long line) {
    if (!block.contains(line)) {
      return 1;
    }
    return TextGrid.wrappedRows(Math.min(block.width(line), (long) block.columns()), wrapColumns());
  }

  private TextGrid.WrappedRows wrappedRows(final LineBlock block) {
    return new TextGrid.WrappedRows() {
      @Override
      public int rows(long line) {
        return wrappedRows(block, line);
      }
    };
  }

  /**
   * Counts the lines from the top that fit the view whole when wrapped.
   *
   * @return at least 1
   */
  int wrapFit() {
    Viewport viewport = document.viewport();
    return grid.wrapFit(
        viewport.topLine(),
        viewport.lineCount(),
        wrappedRows(document.lines()),
        canvas.getClientArea().height,
        document.screenRows());
  }

  /**
   * Paints the visible rows through the {@link RowPainter}: one screen row per line, or with text
   * wrap on one per segment of {@link #wrapColumns} columns, then the gutter.
   */
  private void paint(GC gc) {
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
    long caretLine = selection.caret().line();
    boolean wrap = document.wrap();
    int columns = wrapColumns();
    int gutter = gutterWidth();
    int lineHeight = grid.lineHeight();
    int y = 0;
    for (long line = viewport.topLine(); line < viewport.lineCount() && y < area.height; line++) {
      long[] selected = selectedColumns(selection, line);
      int segments = wrap ? wrappedRows(block, line) : 1;
      long source = block.contains(line) ? block.sourceLine(line) : -1;
      boolean context = source >= 0 && document.contextRow(source);
      if (source >= 0 && document.startsGroup(source)) {
        groupRules.add(y);
      }
      for (int k = 0; k < segments && y < area.height; k++) {
        long from = wrap ? (long) k * columns : viewport.firstColumn();
        painter.paint(
            gc,
            colors,
            grid,
            new RowPainter.Row(
                block,
                line,
                from,
                wrap ? from + columns : Long.MAX_VALUE,
                k == segments - 1,
                gutter,
                y,
                area.width,
                line == caretLine,
                selected[0],
                selected[1],
                context));
        y += lineHeight;
      }
    }
    painter.endPaint();
    paintGroupRules(gc, gutter, area.width);
    paintGutter(gc, viewport, block, wrap, gutter, area);
    if (!TEXT_PAINTED.get()
        && y > 0
        && block.contains(viewport.topLine())
        && TEXT_PAINTED.compareAndSet(false, true)) {
      LOG.debug(FIRST_TEXT_PAINT);
    }
  }

  // Context lines.

  /** Tops of the rows that start a group of results with context, collected per paint. */
  private final List<Integer> groupRules = new ArrayList<Integer>();

  /** Draws a rule across the text area on top of each row that starts a group. */
  private void paintGroupRules(GC gc, int gutter, int width) {
    gc.setForeground(colors.lineNumbers);
    for (int top : groupRules) {
      gc.drawLine(gutter, top, width, top);
    }
    groupRules.clear();
  }

  /** The selected columns {@code [from, to)} of a line, from -1 for none. */
  private long[] selectedColumns(Selection selection, long line) {
    TextPosition start = selection.start();
    TextPosition end = selection.end();
    if (selection.isEmpty() || line < start.line() || line > end.line()) {
      return new long[] {-1, -1};
    }
    if (document.blockSelection()) {
      long anchor = selection.anchor().column();
      long caret = selection.caret().column();
      return new long[] {Math.min(anchor, caret), Math.max(anchor, caret)};
    }
    return new long[] {
      line == start.line() ? start.column() : 0, line == end.line() ? end.column() : Long.MAX_VALUE
    };
  }

  /** Marks a collapsed entry's row: a triangle at the gutter's right edge and a rule under it. */
  private void paintFold(GC gc, int gutter, int y, int width) {
    int lineHeight = grid.lineHeight();
    int size = Math.max(4, lineHeight / 3);
    int x = gutter - TextGrid.GUTTER_PAD + 1;
    int top = y + (lineHeight - size) / 2;
    gc.setBackground(colors.lineNumbers);
    gc.fillPolygon(new int[] {x, top, x + size, top + size / 2, x, top + size});
    gc.setForeground(colors.lineNumbers);
    gc.drawLine(gutter, y + lineHeight - 1, width, y + lineHeight - 1);
  }

  /**
   * Paints the gutter over each line's first screen row: the mark or match bullet, the line number
   * and, for a collapsed entry, the fold marker under the line's last row.
   */
  private void paintGutter(
      GC gc, Viewport viewport, LineBlock block, boolean wrap, int gutter, Rectangle area) {
    gc.setBackground(colors.gutter);
    gc.fillRectangle(0, 0, gutter, area.height);
    int lineHeight = grid.lineHeight();
    int bullet = bulletWidth();
    int diameter = Math.max(4, Math.min(bullet, lineHeight) - 6);
    boolean numbers = document.lineNumbers();
    int y = 0;
    for (long line = viewport.topLine(); line < viewport.lineCount() && y < area.height; line++) {
      int rows = wrap ? wrappedRows(block, line) : 1;
      if (block.contains(line)) {
        long source = block.sourceLine(line);
        Color marker =
            document.marked(source)
                ? colors.markMarker
                : document.matched(source) ? colors.matchMarker : null;
        if (marker != null && marker == colors.markMarker && document.markComment(source) != null) {
          // A commented mark is a square.
          gc.setBackground(marker);
          gc.fillRectangle(
              (bullet - diameter) / 2, y + (lineHeight - diameter) / 2, diameter, diameter);
        } else if (marker != null) {
          gc.setBackground(marker);
          gc.fillOval((bullet - diameter) / 2, y + (lineHeight - diameter) / 2, diameter, diameter);
        }
        if (numbers) {
          gc.setForeground(colors.lineNumbers);
          String number = Long.toString(source + 1);
          Point size = gc.stringExtent(number);
          gc.drawString(number, gutter - TextGrid.GUTTER_PAD - size.x, y, true);
        }
        if (document.folded(source)) {
          paintFold(gc, gutter, y + (rows - 1) * lineHeight, area.width);
        }
      }
      y += rows * lineHeight;
    }
  }
}
