package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.KeySequencer;
import io.github.ajdailey.qlogg.app.command.KeyStroke;
import io.github.ajdailey.qlogg.app.command.Keymap;
import io.github.ajdailey.qlogg.app.command.MenuTable;
import io.github.ajdailey.qlogg.app.config.FontSettings;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.CommandStates;
import io.github.ajdailey.qlogg.app.controller.LineViewController;
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
 * <p>With text wrap off, a strip at the right edge ({@link #markerMargin}) holds line markers and
 * no text, and the columns reported to the controller exclude it. A row whose line runs past the
 * reported columns shows a wrap marker there, and the first row of the line wrapped in place
 * ({@link LineViewController#wrappedLines}) an unwrap marker. A click on a marker toggles the
 * line's wrap without moving the caret. The wrapped line paints from column 0 in segments of the
 * view's width, every other line from the first visible column, and the view reports the lines that
 * fit whole as with text wrap on.
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

  // Accessible names.
  private final AccessibleName accessibleName;

  private final LineViewController document;
  private final ApplicationController application;
  private final KeySequencer keys;
  private final Keymap keymapRef;
  private final PrefixListener prefix;
  // Menu item icons.
  private final MenuIcons menuIcons;
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
            if (marginShown != (markerMargin() > 0)) {
              resized();
            }
            if (fitsLines()) {
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
   * @param menuIcons the context menu's icons
   */
  public LogCanvas(
      Composite parent,
      LineViewController document,
      ApplicationController application,
      Keymap keymap,
      Palette palette,
      PrefixListener prefix,
      MenuIcons menuIcons) {
    this.document = document;
    this.application = application;
    this.keys = new KeySequencer(keymap);
    this.keymapRef = keymap;
    this.prefix = prefix;
    this.menuIcons = menuIcons;
    frame = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(2, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    layout.horizontalSpacing = 0;
    frame.setLayout(layout);
    canvas = new Canvas(frame, SWT.H_SCROLL | SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
    canvas.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    // An overlay bar fades in over the bottom row and is left out of the client area; a fixed bar
    // stays visible and the rows fit above it.
    canvas.setScrollbarsMode(SWT.NONE);
    accessibleName = AccessibleName.attach(canvas, UiText.ACCESSIBLE_LOG_VIEW.text());
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
    if (fitsLines()) {
      document.wrapFitted(wrapFit());
    }
    canvas.redraw();
  }

  private Menu contextMenu(final Keymap keymap) {
    Menu menu = new Menu(canvas);
    boolean mac = SwtKeys.mac();
    Menu cascade = null;
    for (final CommandId command : MenuTable.LOG_VIEW_MENU) {
      if (command == null) {
        new MenuItem(menu, SWT.SEPARATOR);
        cascade = null;
        continue;
      }
      UiText cascadeLabel = MenuTable.logViewCascade(command);
      if (cascadeLabel == null) {
        cascade = null;
      } else if (cascade == null) {
        MenuItem holder = new MenuItem(menu, SWT.CASCADE);
        holder.setText(cascadeLabel.text());
        cascade = new Menu(menu);
        holder.setMenu(cascade);
      }
      MenuItem item = new MenuItem(cascade == null ? menu : cascade, SWT.PUSH);
      String keys = keymap.menuKeyText(command, mac);
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
    for (UiText label : MenuTable.LOG_VIEW_CASCADES) {
      if (label == null) {
        new MenuItem(menu, SWT.SEPARATOR);
        continue;
      }
      MenuItem holder = new MenuItem(menu, SWT.CASCADE);
      holder.setText(label.text());
      final Menu listed = new Menu(menu);
      holder.setMenu(listed);
      final UiText kind = label;
      listed.addListener(
          SWT.Show,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              fillCascade(kind, listed, keymap);
            }
          });
    }
    // Menu item icons.
    menuIcons.decorate(menu);
    final Menu built = menu;
    menu.addListener(
        SWT.Show,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            enable(built, new CommandStates(application));
          }
        });
    return menu;
  }

  /** Enables each command item of a context menu and its cascades as the menu bar does. */
  private static void enable(Menu menu, CommandStates states) {
    for (MenuItem item : menu.getItems()) {
      if (item.getData() instanceof CommandId command) {
        item.setEnabled(states.enabled(command));
      } else if (item.getMenu() != null) {
        enable(item.getMenu(), states);
      }
    }
  }

  /** Fills a {@link MenuTable#LOG_VIEW_CASCADES} cascade as it shows. */
  private void fillCascade(UiText kind, Menu listed, Keymap keymap) {
    switch (kind) {
      case MENU_TIME_SYNC ->
          TimeSyncMenu.fill(listed, application.windowSet().timeSync(), document.owner());
      case GROUP_HIGHLIGHT -> fillSets(listed);
      case MENU_COLOR_LABEL -> ColorLabelMenu.fill(listed, application, keymap, document);
      default -> throw new IllegalArgumentException(kind.name());
    }
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

  /** The canvas's accessible name: the main view's until the filtered view replaces it. */
  AccessibleName accessibleName() {
    return accessibleName;
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
            if (event.button == 1 && lineMarker(event.x, event.y) < 0) {
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
    // Mark comment and line marker tooltips.
    Listener tooltip =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            tooltip(event.type == SWT.MouseMove ? event.x : -1, event.y);
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
    long marked = lineMarker(event.x, event.y);
    if (marked >= 0) {
      document.toggleLineWrap(marked);
      return;
    }
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
   * Sets the canvas tooltip for the pointer: the comment of the mark under it in the bullet column,
   * Wrap line or Unwrap line over a line marker, and none elsewhere.
   *
   * @param x the pointer's x, or -1 when it left the canvas
   * @param y the pointer's y
   */
  void tooltip(int x, int y) {
    String text = null;
    Viewport viewport = document.viewport();
    boolean below = !document.wrap() && rowAt(y).line() >= viewport.lineCount();
    if (x >= 0 && x < bulletWidth() && !dragging && !below) {
      long source = document.sourceLine(hit(x, y).line());
      text = source >= 0 && document.marked(source) ? document.markComment(source) : null;
    } else if (x >= 0 && !dragging) {
      long row = lineMarker(x, y);
      if (row >= 0) {
        text =
            row == wrappedRow()
                ? UiText.TOOLTIP_UNWRAP_LINE.text()
                : UiText.TOOLTIP_WRAP_LINE.text();
      }
    }
    String shown = canvas.getToolTipText();
    if (text == null ? shown != null : !text.equals(shown)) {
      canvas.setToolTipText(text);
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
          viewport.topLine(), viewport.lineCount(), rowsPerLine(), wrapColumns(), textX, y);
    }
    long wrapped = wrappedRow();
    if (wrapped >= 0) {
      return grid.lineWrapHit(
          viewport.topLine(),
          viewport.lineCount(),
          rowsPerLine(),
          wrapped,
          wrapColumns(),
          viewport.firstColumn(),
          textX,
          y);
    }
    return grid.hit(viewport.topLine(), viewport.lineCount(), viewport.firstColumn(), textX, y);
  }

  /**
   * Maps a double click to a line and column: as {@link #hit}, except that on a wrapped line a
   * point past a segment's right edge stays on that segment ({@link TextGrid#wrappedWordHit}).
   */
  TextPosition wordHit(int x, int y) {
    Viewport viewport = document.viewport();
    int textX = x - gutterWidth();
    if (document.wrap()) {
      return grid.wrappedWordHit(
          viewport.topLine(), viewport.lineCount(), rowsPerLine(), wrapColumns(), textX, y);
    }
    long wrapped = wrappedRow();
    if (wrapped >= 0) {
      return grid.lineWrapWordHit(
          viewport.topLine(),
          viewport.lineCount(),
          rowsPerLine(),
          wrapped,
          wrapColumns(),
          viewport.firstColumn(),
          textX,
          y);
    }
    return hit(x, y);
  }

  // Line wrap.

  /** Reports whether the viewport's rows are the lines that fit whole. */
  private boolean fitsLines() {
    return document.wrap() || document.wrappedLine() >= 0;
  }

  /**
   * Returns the row of the line wrapped in place, as last read.
   *
   * @return the row, or -1 with text wrap on or while the line is not read or not in view
   */
  long wrappedRow() {
    LineBlock wrapped = document.wrappedLines();
    return document.wrap() || wrapped.count() != 1 ? -1 : wrapped.firstLine();
  }

  /** Returns the width of a line marker at the right edge of the text area, in pixels. */
  int markerWidth() {
    return Math.max(grid.cellWidth(), grid.lineHeight() / 2) + 4;
  }

  /** Whether the last {@link #resized} reserved the marker strip. */
  private boolean marginShown;

  /**
   * Returns the width of the strip at the canvas's right edge that holds the line markers, which
   * text never paints into and the reported columns exclude.
   *
   * @return {@link #markerWidth} with text wrap off, else 0
   */
  int markerMargin() {
    return document.wrap() ? 0 : markerWidth();
  }

  /**
   * Reports whether a row shows a line marker: an unwrap marker on the first row of the line
   * wrapped in place, a wrap marker on a line that runs past the right edge.
   *
   * @param line a row
   * @param wrapped the row of the line wrapped in place, or -1
   * @return false with text wrap on and for a row not read yet
   */
  private boolean marked(long line, long wrapped) {
    if (document.wrap()) {
      return false;
    }
    if (line == wrapped) {
      return true;
    }
    LineBlock block = document.lines();
    Viewport viewport = document.viewport();
    return block.contains(line) && block.width(line) > viewport.firstColumn() + viewport.columns();
  }

  /**
   * Returns the row whose line marker lies under a point.
   *
   * @param x the x in the canvas
   * @param y the y in the canvas
   * @return the row, or -1 when no marker is there
   */
  long lineMarker(int x, int y) {
    if (document.wrap() || x < canvas.getClientArea().width - markerWidth()) {
      return -1;
    }
    TextGrid.LineRow at = rowAt(y);
    long line = at.line();
    if (at.segment() != 0 || line >= document.viewport().lineCount()) {
      return -1;
    }
    return marked(line, wrappedRow()) ? line : -1;
  }

  /** Returns the line and segment under a y, the line count below the last line. */
  private TextGrid.LineRow rowAt(int y) {
    Viewport viewport = document.viewport();
    return grid.lineRow(viewport.topLine(), viewport.lineCount(), rowsPerLine(), y);
  }

  /** Draws a line marker in the marker strip: a chevron right to wrap, left to unwrap. */
  private void paintLineMarker(GC gc, boolean unwrap, int y, int right) {
    int width = markerWidth();
    int lineHeight = grid.lineHeight();
    int x = right - width;
    gc.setForeground(colors.lineNumbers);
    gc.setLineWidth(Math.max(1, lineHeight / 10));
    int half = Math.max(2, Math.min(width - 4, lineHeight / 2) / 2);
    int middle = y + lineHeight / 2;
    int center = x + width / 2;
    int tip = unwrap ? center - half / 2 : center + half / 2;
    int back = unwrap ? tip + half : tip - half;
    gc.drawPolyline(new int[] {back, middle - half, tip, middle, back, middle + half});
    gc.setLineWidth(0);
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
    int margin = markerMargin();
    marginShown = margin > 0;
    document.viewportResized(
        grid.rows(area.height), grid.columns(area.width - gutterWidth() - margin));
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

  /**
   * Rows each line takes: wrapped with text wrap on; otherwise one, except the line wrapped in
   * place.
   */
  private TextGrid.WrappedRows rowsPerLine() {
    final boolean wrap = document.wrap();
    final LineBlock block = wrap ? document.lines() : document.wrappedLines();
    return new TextGrid.WrappedRows() {
      @Override
      public int rows(long line) {
        return wrap || block.contains(line) ? wrappedRows(block, line) : 1;
      }
    };
  }

  /**
   * Counts the lines from the top that fit the view whole, with lines wrapped as they paint.
   *
   * @return at least 1
   */
  int wrapFit() {
    Viewport viewport = document.viewport();
    return grid.wrapFit(
        viewport.topLine(),
        viewport.lineCount(),
        rowsPerLine(),
        canvas.getClientArea().height,
        document.screenRows());
  }

  /**
   * Paints the visible rows through the {@link RowPainter}: one screen row per line, or one per
   * segment of {@link #wrapColumns} columns for a wrapped line, then the line markers and the
   * gutter.
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
    long wrappedRow = wrappedRow();
    TextGrid.WrappedRows rows = rowsPerLine();
    int columns = wrapColumns();
    int gutter = gutterWidth();
    int margin = markerMargin();
    int lineHeight = grid.lineHeight();
    List<Integer> wrapMarkers = new ArrayList<Integer>();
    int unwrapMarker = -1;
    int y = 0;
    for (long line = viewport.topLine(); line < viewport.lineCount() && y < area.height; line++) {
      long[] selected = selectedColumns(selection, line);
      boolean wrapped = wrap || line == wrappedRow;
      LineBlock held = line == wrappedRow ? document.wrappedLines() : block;
      int segments = rows.rows(line);
      long source = block.contains(line) ? block.sourceLine(line) : -1;
      boolean context = source >= 0 && document.contextRow(source);
      if (source >= 0 && document.startsGroup(source)) {
        groupRules.add(y);
      }
      if (line == wrappedRow) {
        unwrapMarker = y;
      } else if (marked(line, wrappedRow)) {
        wrapMarkers.add(y);
      }
      for (int k = 0; k < segments && y < area.height; k++) {
        long from = wrapped ? (long) k * columns : viewport.firstColumn();
        painter.paint(
            gc,
            colors,
            grid,
            new RowPainter.Row(
                held,
                line,
                from,
                wrapped ? from + columns : Long.MAX_VALUE,
                k == segments - 1,
                gutter,
                y,
                area.width - margin,
                line == caretLine,
                selected[0],
                selected[1],
                context));
        y += lineHeight;
      }
    }
    painter.endPaint();
    paintGroupRules(gc, gutter, area.width - margin);
    if (margin > 0) {
      gc.setBackground(colors.gutter);
      gc.fillRectangle(area.width - margin, 0, margin, area.height);
    }
    for (int top : wrapMarkers) {
      paintLineMarker(gc, false, top, area.width);
    }
    if (unwrapMarker >= 0) {
      paintLineMarker(gc, true, unwrapMarker, area.width);
    }
    paintGutter(gc, viewport, rows, gutter, area);
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
      GC gc, Viewport viewport, TextGrid.WrappedRows lineRows, int gutter, Rectangle area) {
    LineBlock block = document.lines();
    gc.setBackground(colors.gutter);
    gc.fillRectangle(0, 0, gutter, area.height);
    int lineHeight = grid.lineHeight();
    int bullet = bulletWidth();
    int diameter = Math.max(4, Math.min(bullet, lineHeight) - 6);
    boolean numbers = document.lineNumbers();
    int y = 0;
    for (long line = viewport.topLine(); line < viewport.lineCount() && y < area.height; line++) {
      int rows = lineRows.rows(line);
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
