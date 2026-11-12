package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.about.AboutInfo;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.KeyStroke;
import io.github.ajdailey.qlogg.app.command.Keymap;
import io.github.ajdailey.qlogg.app.command.MenuTable;
import io.github.ajdailey.qlogg.app.config.FontSettings;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.UiState;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.ApplicationListener;
import io.github.ajdailey.qlogg.app.controller.CommandStates;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.controller.EncodingChoices;
import io.github.ajdailey.qlogg.app.controller.LineViewController;
import io.github.ajdailey.qlogg.app.controller.TabDataListener;
import io.github.ajdailey.qlogg.app.controller.TabDataState;
import io.github.ajdailey.qlogg.app.controller.TabTips;
import io.github.ajdailey.qlogg.app.controller.TimeController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.theme.PaletteListener;
import io.github.ajdailey.qlogg.app.view.BusyCounter;
import io.github.ajdailey.qlogg.app.view.DocumentViewListener;
import io.github.ajdailey.qlogg.app.view.DocumentViewState;
import io.github.ajdailey.qlogg.app.view.MainView;
import io.github.ajdailey.qlogg.app.view.PositionText;
import io.github.ajdailey.qlogg.app.view.StatusText;
import io.github.ajdailey.qlogg.app.view.TimeText;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.model.MarkComments;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.time.UserFormat;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

/**
 * The main window: the menu bar built from the {@link Keymap}; a top row with the icon toolbar
 * (Open, Reload, Follow), the read-only path field, Stop, and the shown file's size, encoding and
 * position; a row of tabs, one per open file, shown while any is open, with Open, tab list and
 * Close buttons at its right end; the main log view with the {@link OverviewBar} between its text
 * and scroll bar over the bottom pane, split by a sash with a grip; the bottom pane holds the
 * {@link SearchBar} over the {@link ResultsPane}; then the {@link QuickFindBar} and a status bar
 * with the document notes, notices and the key-prefix field. The views show the active tab:
 * switching tabs disposes them and creates them over the other document's controllers, which hold
 * the viewport, selection and rows. Tabs show file names and move by dragging, within the strip or
 * to another window ({@link #dropTab}); a tab's close button or a middle click closes its file, and
 * its context menu holds the close variants, the path commands, Rename Tab and Move to New Window.
 * The path field's context menu holds the path commands, Copy and Select All. Files dropped on the
 * window open a tab each. A palette change from the theme recolors both log views and the overview
 * bar at once; native widgets follow the system. Created, placed and opened on the UI thread; a
 * close request from the window system is vetoed and forwarded to the close handler, which ends
 * with {@link #dispose}. Every QLogg dialog returns at once. The platform file and color choosers
 * run a nested event loop, and on GTK {@link #clipboardText} waits on the UI thread for the
 * clipboard owner.
 *
 * <p>{@link #setActive} and {@link #setIdle} count overlapping operations through a {@link
 * BusyCounter}: while any is active the shell shows {@code SWT.CURSOR_APPSTARTING}, which keeps the
 * window usable, and the default cursor returns when the last one ends.
 */
public final class MainWindow implements MainView, ApplicationListener {
  /** How long a notice stays in the status bar. */
  static final int NOTICE_MILLIS = 5000;

  /** System property SWT sets on GTK to {@code x11}, {@code wayland} or {@code unknown}. */
  static final String GDK_BACKEND = "org.eclipse.swt.internal.gdk.backend";

  /** System property SWT sets on GTK to the GTK version. */
  static final String GTK_VERSION = "org.eclipse.swt.internal.gtk.version";

  /** Widget data key marking a sash that draws its grip. */
  private static final String GRIP = "qlogg.grip";

  // Tab move between windows.
  /** Shell data key holding the shell's {@code MainWindow}. */
  private static final String WINDOW = "qlogg.mainWindow";

  /** Narrowest the path field gets in the top row, in pixels. */
  static final int MIN_PATH_WIDTH = 160;

  /**
   * Narrowest the position field gets, in pixels; a longer text widens it until the tab changes.
   */
  static final int POSITION_WIDTH = 100;

  /** Height of the splitter between the main view and the bottom pane, in pixels. */
  static final int SASH_WIDTH = 7;

  // Filtered view minimum.
  /** Rows the filtered view keeps while the window is tall enough for them and a main view row. */
  static final int MIN_FILTERED_ROWS = 3;

  private final Shell shell;
  private Keymap keymap;
  private final Composite topRow;
  private final ToolBar toolBar;
  private final ToolIcons toolIcons;
  private final Text pathField;
  private final Label sizeLabel;
  private final Label encodingLabel;
  private final Composite statusBar;
  private final Map<CommandId, ToolItem> toolItems =
      new EnumMap<CommandId, ToolItem>(CommandId.class);
  private Palette palette;
  private final List<Image> icons;
  private final SearchBar searchBar;
  private final CTabFolder tabs;
  // Tab strip buttons.
  private final TabStripButtons tabButtons;
  private final SashForm split;
  // Filtered view minimum: the main view's percent as set, before the minimum applies.
  private int wantedSplit = Preferences.DEFAULTS.splitPercent();
  private final Composite mainHolder;
  private final Composite resultsHolder;
  private final Label status;
  private final Label notice;
  private final Label position;
  // Timestamps.
  private final Label timeField;
  private final Label prefix;
  private final BusyCounter busy;
  private final SwtUiDispatcher dispatcher;
  // Command palette.
  private CommandPaletteWindow paletteWindow;
  private ApplicationController controller;
  private QuickFindBar quickFindBar;
  private LogCanvas canvas;
  private ResultsPane results;
  private OverviewBar overviewBar;
  private CTabItem dragged;
  // Tab drag and tab context menu.
  private boolean movingTab;
  private Menu tabMenu;
  private PointerProbe pointer =
      new PointerProbe() {
        @Override
        public Control controlAt(Point point) {
          return shell.getDisplay().getCursorControl();
        }
      };
  private MainMenuBar menuBar;
  private EncodingSource encodingSource =
      new EncodingSource() {
        @Override
        public void load(EncodingChoices.Listener listener) {
          controller.loadEncodingChoices(listener);
        }
      };
  private WindowGeometry.Request normalBounds = new WindowGeometry.Request(null, null, null, null);
  private ShortcutsWindow shortcuts;
  private Clipboard clipboard;
  // Scratchpad and the search across open files.
  private ScratchpadWindow scratchpad;
  private MultiSearchWindow multiSearch;
  // Mark comments and marks list.
  private MarksWindow marks;
  // Multiple windows, logging and support items.
  private final Link warnings;
  private TextWindow logWindow;
  // User guide.
  private GuideWindow guide;
  private PaletteListener paletteListener;
  // Tab data dot.
  private TabDots tabDots;
  private TabDataListener tabDataListener;
  private long noticeGeneration;
  // Drop target: the drag counter and whether the current drag holds folders only.
  private int drags;
  private boolean dragFoldersOnly;
  private final LogCanvas.PrefixListener prefixListener =
      new LogCanvas.PrefixListener() {
        @Override
        public void prefixChanged(String text) {
          if (!prefix.isDisposed()) {
            prefix.setText(text);
          }
        }
      };
  private final DocumentViewListener statusListener =
      new DocumentViewListener() {
        @Override
        public void stateChanged(DocumentViewState state) {
          showStatus(state);
          syncToolBar();
        }

        @Override
        public void contentChanged() {
          showPosition();
        }

        @Override
        public void timeChanged() {
          showTime();
          showPosition();
          // A format change enables the timestamp commands' accelerators.
          syncEnablement();
        }

        // Navigation history: Go Back and Go Forward accelerators follow their entries.
        @Override
        public void navigationChanged() {
          syncEnablement();
        }
      };
  // Timestamps: the filtered view's selection and its time difference.
  private final DocumentViewListener filteredListener =
      new DocumentViewListener() {
        @Override
        public void stateChanged(DocumentViewState state) {}

        @Override
        public void contentChanged() {
          showPosition();
        }

        @Override
        public void timeChanged() {
          showPosition();
        }
      };

  /**
   * Creates the window with the default keymap, not yet visible.
   *
   * @param display the Display
   */
  public MainWindow(Display display) {
    this(display, Keymap.defaults());
  }

  /**
   * Creates the window, not yet visible.
   *
   * @param display the Display
   * @param keymap the key bindings for menus and the log views
   */
  public MainWindow(Display display, Keymap keymap) {
    this(display, keymap, Palette.LIGHT);
  }

  /**
   * Creates the window, not yet visible.
   *
   * @param display the Display
   * @param keymap the key bindings for menus and the log views
   * @param palette the palette the views start with
   */
  public MainWindow(Display display, Keymap keymap, Palette palette) {
    this.keymap = keymap;
    this.palette = palette;
    this.dispatcher = new SwtUiDispatcher(display);
    shell = new Shell(display, SWT.SHELL_TRIM);
    shell.setData(WINDOW, this);
    shell.setText(UiText.WINDOW_TITLE.text());
    icons = Icons.load(display, Icons.Variant.DARK);
    shell.setImages(icons.toArray(new Image[0]));
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    layout.verticalSpacing = 0;
    shell.setLayout(layout);
    topRow = new Composite(shell, SWT.NONE);
    GridLayout topLayout = new GridLayout(11, false);
    topLayout.marginWidth = 2;
    topLayout.marginHeight = 1;
    topLayout.horizontalSpacing = 6;
    topRow.setLayout(topLayout);
    topRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    toolBar = new ToolBar(topRow, SWT.FLAT);
    toolBar.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
    toolIcons = new ToolIcons(toolBar);
    toolItem(toolBar, CommandId.OPEN, ToolIcons.OPEN, UiText.TOOLBAR_OPEN, SWT.PUSH);
    toolItem(toolBar, CommandId.RELOAD, ToolIcons.RELOAD, UiText.TOOLBAR_RELOAD, SWT.PUSH);
    toolItem(toolBar, CommandId.FOLLOW, ToolIcons.FOLLOW, UiText.TOOLBAR_FOLLOW, SWT.CHECK);
    pathField = new Text(topRow, SWT.SINGLE | SWT.READ_ONLY | SWT.BORDER);
    GridData pathData = new GridData(SWT.FILL, SWT.CENTER, true, false);
    pathData.widthHint = MIN_PATH_WIDTH;
    pathData.minimumWidth = MIN_PATH_WIDTH;
    pathField.setLayoutData(pathData);
    pathField.setEnabled(false);
    pathField.setMenu(pathMenu());
    ToolBar stopBar = new ToolBar(topRow, SWT.FLAT);
    stopBar.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
    toolItem(stopBar, CommandId.STOP, ToolIcons.STOP, UiText.TOOLBAR_STOP, SWT.PUSH);
    sizeLabel = infoLabel();
    encodingLabel = infoLabel();
    position = infoLabel();
    ((GridData) position.getLayoutData()).widthHint = POSITION_WIDTH;
    timeField = infoLabel();
    timeField.setCursor(display.getSystemCursor(SWT.CURSOR_HAND));
    timeField.addListener(
        SWT.MouseUp,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (controller != null && event.button == 1 && !timeField.getText().isEmpty()) {
              controller.execute(CommandId.GO_TO_TIME);
            }
          }
        });
    tabs = new CTabFolder(shell, SWT.TOP | SWT.FLAT);
    GridData tabsData = new GridData(SWT.FILL, SWT.TOP, true, false);
    // The folder adds its tab row as trim around the hint, which is the empty client area.
    tabsData.heightHint = 1;
    tabsData.exclude = true;
    tabs.setLayoutData(tabsData);
    tabs.setVisible(false);
    // Tab strip buttons.
    tabButtons =
        new TabStripButtons(
            tabs,
            palette,
            new Runnable() {
              @Override
              public void run() {
                if (controller != null) {
                  controller.execute(CommandId.OPEN);
                }
              }
            },
            new Runnable() {
              @Override
              public void run() {
                if (controller != null) {
                  controller.execute(CommandId.CLOSE);
                }
              }
            });
    tabButtons.setLabels(toolTip(CommandId.OPEN), toolTip(CommandId.CLOSE));
    split = new SashForm(shell, SWT.VERTICAL);
    split.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    split.setSashWidth(SASH_WIDTH);
    mainHolder = new Composite(split, SWT.NONE);
    GridLayout mainLayout = new GridLayout(1, false);
    mainLayout.marginWidth = 0;
    mainLayout.marginHeight = 0;
    mainLayout.horizontalSpacing = 0;
    mainHolder.setLayout(mainLayout);
    Composite bottom = new Composite(split, SWT.NONE);
    GridLayout bottomLayout = new GridLayout(1, false);
    bottomLayout.marginWidth = 0;
    bottomLayout.marginHeight = 0;
    bottomLayout.verticalSpacing = 0;
    bottom.setLayout(bottomLayout);
    searchBar = new SearchBar(bottom, keymap);
    searchBar.control().setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    resultsHolder = new Composite(bottom, SWT.NONE);
    resultsHolder.setLayout(new FillLayout());
    resultsHolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    setSplit(Preferences.DEFAULTS.splitPercent());
    gripSashes();
    // Filtered view minimum.
    split.addListener(
        SWT.Resize,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            fitSplit();
          }
        });
    statusBar = new Composite(shell, SWT.NONE);
    GridLayout statusLayout = new GridLayout(4, false);
    statusLayout.marginHeight = 1;
    statusLayout.marginWidth = 6;
    statusBar.setLayout(statusLayout);
    statusBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    status = new Label(statusBar, SWT.NONE);
    status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    notice = new Label(statusBar, SWT.NONE);
    notice.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
    warnings = new Link(statusBar, SWT.NONE);
    warnings.setText("<a>" + UiText.STATUS_WARNINGS.text() + "</a>");
    warnings.setToolTipText(UiText.STATUS_WARNINGS_TIP.text());
    GridData warningsData = new GridData(SWT.END, SWT.CENTER, false, false);
    warningsData.exclude = true;
    warnings.setLayoutData(warningsData);
    warnings.setVisible(false);
    warnings.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (controller != null) {
              controller.execute(CommandId.SHOW_LOG);
            }
          }
        });
    prefix = new Label(statusBar, SWT.RIGHT);
    GridData prefixData = new GridData(SWT.END, SWT.CENTER, false, false);
    prefixData.widthHint = 120;
    prefix.setLayoutData(prefixData);
    busy =
        new BusyCounter(
            dispatcher,
            new BusyCounter.Indicator() {
              @Override
              public void busyChanged(boolean active) {
                if (!shell.isDisposed()) {
                  shell.setCursor(
                      active ? shell.getDisplay().getSystemCursor(SWT.CURSOR_APPSTARTING) : null);
                }
              }
            });
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            for (Image image : icons) {
              image.dispose();
            }
            if (clipboard != null) {
              clipboard.dispose();
            }
          }
        });
    acceptDroppedFiles();
  }

  /**
   * Adds a top-row field after a separator.
   *
   * @return the label
   */
  private Label infoLabel() {
    Label line = new Label(topRow, SWT.SEPARATOR | SWT.VERTICAL);
    GridData lineData = new GridData(SWT.CENTER, SWT.CENTER, false, false);
    lineData.heightHint = 16;
    line.setLayoutData(lineData);
    Label label = new Label(topRow, SWT.NONE);
    label.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
    return label;
  }

  /** Draws a grip on each sash of the split; sashes appear when the split lays out. */
  private void gripSashes() {
    final Listener grip =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            Control sash = (Control) event.widget;
            Rectangle area = sash.getBounds();
            event.gc.setBackground(
                sash.getDisplay().getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
            int x = area.width / 2;
            int y = area.height / 2;
            for (int i = -2; i <= 2; i++) {
              event.gc.fillRectangle(x + i * 4 - 1, y - 1, 2, 2);
            }
          }
        };
    // SashForm creates its sash during layout, which on Cocoa runs after the Resize event: a resize
    // forces one layout when no sash exists yet, and a paint repeats the pass. Paint never forces a
    // layout, because a hidden bottom pane leaves no sash and the layout would repaint.
    split.addListener(
        SWT.Resize,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!gripPass(grip)) {
              split.layout(true);
              gripPass(grip);
            }
          }
        });
    split.addListener(
        SWT.Paint,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            gripPass(grip);
          }
        });
  }

  /** Adds the grip to every sash without one; false when the splitter has no sash yet. */
  private boolean gripPass(Listener grip) {
    boolean found = false;
    for (Control child : split.getChildren()) {
      if (child instanceof Sash) {
        found = true;
        if (child.getData(GRIP) == null) {
          child.setData(GRIP, Boolean.TRUE);
          child.setToolTipText(UiText.SPLIT_GRIP.text());
          child.addListener(SWT.Paint, grip);
          // Filtered view minimum: SashForm's own listener has moved the sash by now.
          child.addListener(
              SWT.Selection,
              new Listener() {
                @Override
                public void handleEvent(Event event) {
                  if (event.detail != SWT.DRAG) {
                    sashDragged();
                  }
                }
              });
          child.redraw();
        }
      }
    }
    return found;
  }

  /**
   * Returns the splitter between the main view and the bottom pane.
   *
   * @return the sash, or null before the window first lays out
   */
  Sash sash() {
    for (Control child : split.getChildren()) {
      if (child instanceof Sash sash) {
        return sash;
      }
    }
    return null;
  }

  private void toolItem(
      ToolBar bar, final CommandId command, String icon, UiText label, int style) {
    ToolItem item = new ToolItem(bar, style);
    if (!toolIcons.apply(item, icon)) {
      item.setText(label.text());
    }
    item.setToolTipText(toolTip(command));
    item.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (controller != null) {
              controller.execute(command);
            }
            syncToolBar();
          }
        });
    toolItems.put(command, item);
  }

  private String toolTip(CommandId command) {
    String keys = keymap.keysText(command, SwtKeys.mac());
    return keys.isEmpty() ? command.title() : command.title() + " (" + keys + ")";
  }

  /** Enables the document buttons while a tab is shown and checks Follow while it follows. */
  private void syncToolBar() {
    if (toolBar.isDisposed()) {
      return;
    }
    DocumentController document = controller == null ? null : controller.activeDocument();
    boolean file = document != null && document.state().hasFile();
    toolItems.get(CommandId.RELOAD).setEnabled(file);
    toolItems.get(CommandId.FOLLOW).setEnabled(file);
    toolItems.get(CommandId.STOP).setEnabled(document != null);
    toolItems.get(CommandId.FOLLOW).setSelection(document != null && document.following());
  }

  /**
   * Returns the toolbar.
   *
   * @return the toolbar; each item runs one command
   */
  ToolBar toolBar() {
    return toolBar;
  }

  /** Opens the files dropped on the window, a tab each; the controller opens them on a worker. */
  private void acceptDroppedFiles() {
    final FileTransfer files = FileTransfer.getInstance();
    DropTarget target = new DropTarget(shell, DND.DROP_COPY | DND.DROP_MOVE | DND.DROP_DEFAULT);
    target.setTransfer(files);
    target.addDropListener(
        new DropTargetAdapter() {
          @Override
          public void dragEnter(DropTargetEvent event) {
            drags++;
            dragFoldersOnly = false;
            checkDragged(files, event);
            event.detail = dropDetail();
          }

          @Override
          public void dragOver(DropTargetEvent event) {
            event.detail = dropDetail();
          }

          @Override
          public void dragOperationChanged(DropTargetEvent event) {
            event.detail = dropDetail();
          }

          @Override
          public void drop(DropTargetEvent event) {
            if (event.data instanceof String[] names) {
              dropped(names);
            }
          }
        });
  }

  /**
   * Returns the operation a drag over the window gets: Copy, so a modifier pressed during the drag
   * never asks the source to move and delete the file; none while every dragged path is a folder.
   */
  int dropDetail() {
    return dragFoldersOnly ? DND.DROP_NONE : DND.DROP_COPY;
  }

  /**
   * Starts the folder check of a drag's paths when {@code FileTransfer} returns them during the
   * drag; GTK SWT receives them only for the drop.
   */
  private void checkDragged(FileTransfer files, DropTargetEvent event) {
    if (controller == null || !files.isSupportedType(event.currentDataType)) {
      return;
    }
    Object data = files.nativeToJava(event.currentDataType);
    if (data instanceof String[] names) {
      checkDragged(names);
    }
  }

  /**
   * Checks dragged paths on a worker and records whether all are folders, for {@link #dropDetail}.
   *
   * @param names the dragged paths as the platform gives them
   */
  void checkDragged(String[] names) {
    final int drag = drags;
    List<Path> paths = new ArrayList<Path>();
    for (String name : names) {
      try {
        paths.add(Path.of(name));
      } catch (InvalidPathException e) {
        return;
      }
    }
    controller.checkDragged(
        paths,
        new Consumer<Boolean>() {
          @Override
          public void accept(Boolean foldersOnly) {
            if (drag == drags) {
              dragFoldersOnly = foldersOnly;
            }
          }
        });
  }

  /**
   * Opens dropped files, as a drop does.
   *
   * @param names the dropped paths as the platform gives them
   */
  void dropped(String[] names) {
    if (controller == null) {
      return;
    }
    List<Path> paths = new ArrayList<Path>();
    for (String name : names) {
      try {
        paths.add(Path.of(name));
      } catch (InvalidPathException e) {
        showError(UiText.OPEN_FAILED_TITLE.text(), UiText.OPEN_INVALID_PATH.format(e.getReason()));
      }
    }
    controller.openDropped(paths);
  }

  /**
   * Connects the window to its controller: builds the menu bar and the quick find bar and shows
   * each document the controller opens.
   *
   * @param application the application controller
   */
  public void attach(ApplicationController application) {
    this.controller = application;
    this.keymap = application.keymap();
    application.addListener(this);
    searchBar.attach(application);
    searchBar.setKeymap(keymap);
    quickFindBar = new QuickFindBar(shell, application);
    GridData barData = new GridData(SWT.FILL, SWT.CENTER, true, false);
    barData.exclude = true;
    quickFindBar.control().setLayoutData(barData);
    quickFindBar.control().setVisible(false);
    quickFindBar.control().moveBelow(split);
    int remembered = application.preferences().state().split();
    setSplit(remembered != 0 ? remembered : application.preferences().splitPercent());
    buildMenuBar();
    trackWindowState();
    dispatchWindowKeys();
    listenToTabs();
    paletteListener =
        new PaletteListener() {
          @Override
          public void paletteChanged(Palette next) {
            applyPalette(next);
          }
        };
    application.theme().addListener(paletteListener);
    applyPalette(application.theme().palette());
    // Tab data dot.
    listenToTabData(application);
    // Multiple windows and the warning marker.
    shell.addListener(
        SWT.Activate,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            controller.windowActivated();
          }
        });
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            controller.theme().removeListener(paletteListener);
          }
        });
    showWarningMarker(application.newWarnings());
    // Reopen Closed Tab: another window may have changed the closed list.
    shell.addListener(
        SWT.Activate,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            syncEnablement();
          }
        });
  }

  /**
   * Tab selection, close buttons, middle clicks and dragging go to the controller. Closing the last
   * tab leaves an empty window unless the window closes with it.
   */
  private void listenToTabs() {
    tabs.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!movingTab && event.item instanceof CTabItem item && !item.isDisposed()) {
              controller.activate((DocumentController) item.getData());
            }
          }
        });
    tabs.addCTabFolder2Listener(
        new CTabFolder2Adapter() {
          @Override
          public void close(CTabFolderEvent event) {
            event.doit = false;
            controller.closeDocument((DocumentController) event.item.getData());
          }
        });
    tabs.addListener(
        SWT.MouseDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            dragged = event.button == 1 ? tabs.getItem(new Point(event.x, event.y)) : null;
          }
        });
    tabs.addListener(
        SWT.MenuDetect,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            CTabItem item = tabs.getItem(tabs.toControl(event.x, event.y));
            if (item != null && !item.isDisposed()) {
              event.doit = false;
              showTabMenu((DocumentController) item.getData(), event.x, event.y);
            }
          }
        });
    tabs.addListener(
        SWT.MouseUp,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.button == 2) {
              CTabItem middle = tabs.getItem(new Point(event.x, event.y));
              if (middle != null && !middle.isDisposed()) {
                controller.closeDocument((DocumentController) middle.getData());
              }
              return;
            }
            CTabItem source = dragged;
            dragged = null;
            if (source == null || source.isDisposed()) {
              return;
            }
            CTabItem target = tabs.getItem(new Point(event.x, event.y));
            if (target != null && source != target) {
              moveTab(source, tabs.indexOf(target));
            } else if (target == null) {
              Point point = tabs.toDisplay(event.x, event.y);
              dropTab((DocumentController) source.getData(), point, pointer.controlAt(point));
            }
          }
        });
  }

  /**
   * Opens a tab's context menu: the close variants, the path commands, the tab name, then Time Sync
   * and Move to New Window. One menu serves every tab; each call replaces its items.
   *
   * @param document the tab's controller
   * @param x display x
   * @param y display y
   * @return the menu, already visible
   */
  Menu showTabMenu(final DocumentController document, int x, int y) {
    if (tabMenu == null || tabMenu.isDisposed()) {
      tabMenu = new Menu(shell, SWT.POP_UP);
    }
    Menu menu = tabMenu;
    for (MenuItem old : menu.getItems()) {
      old.dispose();
    }
    CommandStates.TabItems state = menuBar.states().tabItems(document);
    for (final UiText label : MenuTable.TAB_MENU) {
      if (label == null) {
        new MenuItem(menu, SWT.SEPARATOR);
      } else if (label == UiText.MENU_TIME_SYNC) {
        MenuItem timeSync = new MenuItem(menu, SWT.CASCADE);
        timeSync.setText(label.text());
        Menu timeSyncMenu = new Menu(menu);
        timeSync.setMenu(timeSyncMenu);
        TimeSyncMenu.fill(timeSyncMenu, controller.windowSet().timeSync(), document);
      } else {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(label.text());
        item.setEnabled(tabItemEnabled(label, state));
        item.addListener(
            SWT.Selection,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                runTabItem(label, document);
              }
            });
      }
    }
    menu.setLocation(x, y);
    menu.setVisible(true);
    return menu;
  }

  private static boolean tabItemEnabled(UiText label, CommandStates.TabItems state) {
    return switch (label) {
      case TAB_CLOSE_OTHERS -> state.closeOthers();
      case TAB_CLOSE_LEFT -> state.closeLeft();
      case TAB_CLOSE_RIGHT -> state.closeRight();
      case TAB_COPY_PATH, TAB_COPY_FILE_NAME, TAB_OPEN_FOLDER, TAB_RENAME, TAB_RESET_NAME ->
          state.file();
      default -> true;
    };
  }

  /** Runs a {@link MenuTable#TAB_MENU} item on a tab. */
  private void runTabItem(UiText label, DocumentController document) {
    switch (label) {
      case TAB_CLOSE -> controller.closeDocument(document);
      case TAB_CLOSE_OTHERS -> controller.closeOthers(document);
      case TAB_CLOSE_LEFT -> controller.closeSide(document, true);
      case TAB_CLOSE_RIGHT -> controller.closeSide(document, false);
      case TAB_CLOSE_ALL -> controller.closeAll();
      case TAB_COPY_PATH -> controller.copyPath(document);
      case TAB_COPY_FILE_NAME -> controller.copyFileName(document);
      case TAB_OPEN_FOLDER -> controller.openContainingFolder(document);
      case TAB_RENAME -> controller.renameTab(document);
      case TAB_RESET_NAME -> controller.resetTabName(document);
      case TAB_MOVE_TO_NEW_WINDOW -> controller.moveToNewWindow(document, null, null);
      default -> throw new IllegalArgumentException(label.name());
    }
  }

  /**
   * Recreates a tab at another position; a tab's control and order cannot be changed in place.
   * Disposing the selected item makes {@code CTabFolder} select a neighbor and send {@code
   * SWT.Selection}; the tab listener ignores it, so the shown document stays the moved one.
   */
  private void moveTab(CTabItem source, int index) {
    DocumentController document = (DocumentController) source.getData();
    boolean selected = tabs.getSelection() == source;
    movingTab = true;
    try {
      source.dispose();
      CTabItem moved = newTab(document, index);
      if (selected) {
        tabs.setSelection(moved);
      }
    } finally {
      movingTab = false;
    }
    controller.moveDocument(document, index);
  }

  // Tab move between windows.

  /** Finds the control under a display point; the default reads the pointer's from the Display. */
  interface PointerProbe {
    /**
     * Returns the control under a point.
     *
     * @param point display coordinates
     * @return a control of this application, or null outside every shell of it
     */
    Control controlAt(Point point);
  }

  /**
   * Sets how a tab drag finds the control it was released over.
   *
   * @param probe the probe
   */
  void setPointerProbe(PointerProbe probe) {
    pointer = probe;
  }

  /**
   * Ends a tab drag released off this window's tabs. Over another main window the tab moves there:
   * before the tab under the point, else after the last tab. Outside every QLogg shell and outside
   * this window's bounds it moves to a new window with its top-left corner at the point. A release
   * anywhere else does nothing.
   *
   * @param document the dragged tab's controller
   * @param point the release point in display coordinates
   * @param under the control under the point, or null outside every QLogg shell
   */
  void dropTab(DocumentController document, Point point, Control under) {
    if (under == null) {
      if (!shell.getBounds().contains(point)) {
        controller.moveToNewWindow(document, point.x, point.y);
      }
      return;
    }
    Shell other = under.getShell();
    if (other == shell
        || !(other.getData(WINDOW) instanceof MainWindow target)
        || target.controller == null) {
      return;
    }
    int index = target.controller.documents().size();
    if (target.tabs.isVisible()) {
      CTabItem item = target.tabs.getItem(target.tabs.toControl(point));
      if (item != null) {
        index = target.tabs.indexOf(item);
      }
    }
    controller.moveToWindow(document, target.controller, index);
  }

  @Override
  public void documentMovedOut(DocumentController document) {
    removeTab(document);
  }

  @Override
  public void documentMovedIn(DocumentController document, int index) {
    newTab(document, index);
    showTabs();
    syncToolBar();
    syncEnablement();
  }

  private CTabItem newTab(DocumentController document, int index) {
    CTabItem item = new CTabItem(tabs, SWT.CLOSE, Math.min(index, tabs.getItemCount()));
    item.setData(document);
    showTabTitle(item, document);
    showTabData(item);
    return item;
  }

  /** Sets a tab's text; a {@code CTabItem} takes a single {@code &} as a mnemonic. */
  private void showTabTitle(CTabItem item, DocumentController document) {
    item.setText(controller.tabTitle(document).replace("&", "&&"));
  }

  // Tab data dot.

  /**
   * Shows each tab's {@link TabDataState} as a dot from {@link TabDots}; the dots and the listener
   * go with the shell.
   */
  private void listenToTabData(final ApplicationController application) {
    tabs.setUnselectedImageVisible(true);
    tabDataListener =
        new TabDataListener() {
          @Override
          public void tabDataChanged(DocumentController document, TabDataState state) {
            CTabItem item = tabOf(document);
            if (item != null) {
              showTabData(item);
            }
          }
        };
    application.tabData().addListener(tabDataListener);
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            application.tabData().removeListener(tabDataListener);
            if (tabDots != null) {
              tabDots.dispose();
              tabDots = null;
            }
          }
        });
    for (CTabItem item : tabs.getItems()) {
      showTabData(item);
    }
  }

  /** Sets a tab's dot and adds the state to its tooltip under the path. */
  private void showTabData(CTabItem item) {
    DocumentController document = (DocumentController) item.getData();
    TabDataState state =
        controller == null ? TabDataState.NONE : controller.tabData().state(document);
    int group = controller == null ? 0 : controller.windowSet().timeSync().number(document);
    item.setToolTipText(TabTips.text(document.state().path(), state, group));
    item.setImage(tabDots == null ? null : tabDots.image(state));
  }

  /** Creates the palette's dots, moves every tab to them, then disposes the old ones. */
  private void recolorTabDots(Palette next) {
    TabDots old = tabDots;
    tabDots = new TabDots(shell.getDisplay(), next);
    for (CTabItem item : tabs.getItems()) {
      showTabData(item);
    }
    if (old != null) {
      old.dispose();
    }
  }

  /**
   * Returns the dots the tabs show.
   *
   * @return the current palette's images, or null before {@link #attach}
   */
  TabDots tabDots() {
    return tabDots;
  }

  // Time sync.

  @Override
  public void timeSyncChanged() {
    for (CTabItem item : tabs.getItems()) {
      showTabData(item);
    }
  }

  @Override
  public void tabRenamed(DocumentController document) {
    CTabItem item = tabOf(document);
    if (item != null) {
      showTabTitle(item, document);
    }
  }

  /**
   * Builds the path field's context menu from {@link MenuTable#PATH_MENU}: Copy Full Path, Copy
   * File Name and Open Containing Folder for the shown file, then Copy and Select All for the
   * field's text.
   */
  private Menu pathMenu() {
    final Menu menu = new Menu(pathField);
    final Map<UiText, MenuItem> items = new EnumMap<UiText, MenuItem>(UiText.class);
    for (final UiText label : MenuTable.PATH_MENU) {
      if (label == null) {
        new MenuItem(menu, SWT.SEPARATOR);
        continue;
      }
      MenuItem item = new MenuItem(menu, SWT.PUSH);
      item.setText(label.text());
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              runPathItem(label);
            }
          });
      items.put(label, item);
    }
    menu.addListener(
        SWT.Show,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            for (Map.Entry<UiText, MenuItem> entry : items.entrySet()) {
              CommandId command = pathCommand(entry.getKey());
              boolean enabled =
                  command == null
                      ? entry.getKey() != UiText.PATH_COPY || pathField.getSelectionCount() > 0
                      : menuBar != null && menuBar.states().enabled(command);
              entry.getValue().setEnabled(enabled);
            }
          }
        });
    return menu;
  }

  /** Returns the command a {@link MenuTable#PATH_MENU} item runs, or null for the field's own. */
  private static CommandId pathCommand(UiText label) {
    return switch (label) {
      case TAB_COPY_PATH -> CommandId.COPY_PATH;
      case TAB_COPY_FILE_NAME -> CommandId.COPY_FILE_NAME;
      case TAB_OPEN_FOLDER -> CommandId.OPEN_FOLDER;
      default -> null;
    };
  }

  private void runPathItem(UiText label) {
    CommandId command = pathCommand(label);
    if (command != null) {
      if (controller != null) {
        controller.execute(command);
      }
    } else if (label == UiText.PATH_COPY) {
      pathField.copy();
    } else {
      pathField.selectAll();
    }
  }

  /**
   * Returns the path field.
   *
   * @return the read-only field showing the shown file's full path
   */
  Text pathField() {
    return pathField;
  }

  private CTabItem tabOf(DocumentController document) {
    for (CTabItem item : tabs.getItems()) {
      if (item.getData() == document) {
        return item;
      }
    }
    return null;
  }

  /** Shows the tab row while a file is open. */
  private void showTabs() {
    boolean shown = tabs.getItemCount() > 0;
    GridData data = (GridData) tabs.getLayoutData();
    if (data.exclude == shown) {
      data.exclude = !shown;
      tabs.setVisible(shown);
      shell.layout(true);
    }
  }

  /**
   * Returns the tab strip.
   *
   * @return the folder; each item's data is its {@link DocumentController}
   */
  CTabFolder tabs() {
    return tabs;
  }

  // Tab strip buttons.

  /**
   * Returns the buttons at the right end of the tab strip.
   *
   * @return Open, the tab list and Close
   */
  TabStripButtons tabButtons() {
    return tabButtons;
  }

  /**
   * Colors a tab strip from the palette. {@code CTabFolder} copies the Display's list colors when
   * created, and the Display reads them once, so they outlast a theme change. The selection
   * background shows only while focus is inside the folder; elsewhere the selected tab has the
   * strip's background and keeps the selection foreground, so that foreground must read on both.
   * {@link CocoaChrome} leaves a folder colored here alone.
   */
  private static void colorTabs(CTabFolder folder, Palette next) {
    folder.setData(CocoaChrome.PALETTE_COLORED, Boolean.TRUE);
    folder.setBackground(color(next.gutter()));
    folder.setForeground(color(next.text()));
    folder.setSelectionBackground(color(next.background()));
    folder.setSelectionForeground(color(next.text()));
  }

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }

  /** Recolors the log views and the overview bar. */
  private void applyPalette(Palette next) {
    palette = next;
    // Tab data dot.
    recolorTabDots(next);
    colorTabs(tabs, next);
    // Tab strip buttons.
    tabButtons.setPalette(next);
    if (results != null) {
      results.tabButtons().setPalette(next);
    }
    if (canvas != null) {
      canvas.setPalette(next);
    }
    if (results != null) {
      results.canvas().setPalette(next);
      colorTabs(results.sets(), next);
    }
    if (scratchpad != null && !scratchpad.isDisposed()) {
      colorTabs(scratchpad.tabs(), next);
    }
    if (overviewBar != null) {
      overviewBar.setPalette(next);
    }
    // Native controls restyle after the theme call returns; re-ink the icons on the next turn.
    dispatcher.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (!shell.isDisposed()) {
              toolIcons.reink();
              searchBar.reinkIcons();
            }
          }
        });
  }

  /**
   * Returns the palette the views paint with.
   *
   * @return the palette
   */
  public Palette palette() {
    return palette;
  }

  private void buildMenuBar() {
    if (menuBar == null) {
      menuBar = new MainMenuBar(shell, controller);
    }
    menuBar.build(keymap);
    encodingSource.load(
        new EncodingChoices.Listener() {
          @Override
          public void loaded(EncodingChoices choices) {
            if (!shell.isDisposed()) {
              menuBar.encodingsLoaded(choices);
            }
          }
        });
  }

  /** Delivers the Encoding menu's lists to a listener on the UI thread. */
  interface EncodingSource {
    /**
     * Requests the lists.
     *
     * @param listener receives them on the UI thread, at once or later
     */
    void load(EncodingChoices.Listener listener);
  }

  /**
   * Sets where the menu bar gets the Encoding menu's lists; the default asks the controller.
   *
   * @param source the source, used from the next menu bar build
   */
  void setEncodingSource(EncodingSource source) {
    encodingSource = source;
  }

  /** Syncs every menu of the menu bar, as showing each would. */
  void syncMenu() {
    if (menuBar != null) {
      menuBar.syncAll();
    }
  }

  /** Enables the menu items from the command states; runs on every document change. */
  void syncEnablement() {
    if (menuBar != null) {
      menuBar.syncEnablement();
    }
  }

  /**
   * Returns the menu bar.
   *
   * @return the menu bar, or null before {@link #attach}
   */
  MainMenuBar menuBar() {
    return menuBar;
  }

  /**
   * Returns Window > Opened Files.
   *
   * @return the submenu, filled when the Window menu shows
   */
  Menu openedFilesMenu() {
    return menuBar.openedFilesMenu();
  }

  /**
   * Routes window-system close requests to a handler instead of closing.
   *
   * @param handler runs on the UI thread for each close request
   */
  public void onCloseRequest(final Runnable handler) {
    shell.addListener(
        SWT.Close,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            event.doit = false;
            handler.run();
          }
        });
  }

  /**
   * Sizes and positions the window from a request; call before {@link #open}.
   *
   * @param request command-line geometry; null fields are not given
   * @param persisted geometry from the previous session, or null
   * @return the placement applied
   */
  public WindowGeometry.Placement place(
      WindowGeometry.Request request, WindowGeometry.Request persisted) {
    Display display = shell.getDisplay();
    List<WindowGeometry.Screen> screens = new ArrayList<WindowGeometry.Screen>();
    for (Monitor monitor : display.getMonitors()) {
      screens.add(screen(monitor));
    }
    WindowGeometry.Screen primary = screen(display.getPrimaryMonitor());
    Rectangle trim = shell.computeTrim(0, 0, 100, 100);
    boolean positionSupported = PlatformTraits.PLATFORM.positionsWindows();
    WindowGeometry.Placement placement =
        WindowGeometry.place(
            request,
            persisted,
            screens,
            primary,
            trim.width - 100,
            trim.height - 100,
            positionSupported);
    apply(placement);
    if (PlatformTraits.PLATFORM.remeasuresTrim()) {
      Point size = shell.getSize();
      Rectangle client = shell.getClientArea();
      int trimWidth = size.x - client.width;
      int trimHeight = size.y - client.height;
      if (trimWidth != trim.width - 100 || trimHeight != trim.height - 100) {
        placement =
            WindowGeometry.place(
                request, persisted, screens, primary, trimWidth, trimHeight, positionSupported);
        apply(placement);
      }
    }
    return placement;
  }

  private void apply(WindowGeometry.Placement placement) {
    WindowGeometry.Rect bounds = placement.bounds();
    if (placement.setLocation()) {
      shell.setBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
    } else {
      shell.setSize(bounds.width(), bounds.height());
    }
  }

  /** Shows the window. */
  public void open() {
    shell.open();
  }

  /**
   * Returns the SWT shell, for the event loop and tests.
   *
   * @return the shell
   */
  public Shell shell() {
    return shell;
  }

  /**
   * Returns the main log view of the shown document.
   *
   * @return the view, or null when no document is shown
   */
  public LogCanvas canvas() {
    return canvas;
  }

  /**
   * Returns the filtered view pane of the shown document.
   *
   * @return the pane, or null when no document is shown
   */
  ResultsPane results() {
    return results;
  }

  /**
   * Returns the search row.
   *
   * @return the search bar
   */
  public SearchBar searchBar() {
    return searchBar;
  }

  /**
   * Returns the quick find row.
   *
   * @return the bar, after {@link #attach}
   */
  QuickFindBar quickFindBar() {
    return quickFindBar;
  }

  /**
   * Returns the split between the main view and the filtered view.
   *
   * @return the sash form
   */
  SashForm split() {
    return split;
  }

  /**
   * Returns the operation counter behind the busy cursor.
   *
   * @return the counter
   */
  public BusyCounter busy() {
    return busy;
  }

  /**
   * Returns the status bar's document notes: indexing, follow, missing file and failures.
   *
   * @return the text
   */
  public String statusText() {
    return status.getText();
  }

  /**
   * Returns the top row's file fields.
   *
   * @return size and encoding separated by {@link StatusText#GAP}
   */
  public String infoText() {
    return sizeLabel.getText() + StatusText.GAP + encodingLabel.getText();
  }

  /**
   * Returns the size field's tooltip.
   *
   * @return the text, or null without a modification time
   */
  String sizeTip() {
    return sizeLabel.getToolTipText();
  }

  /**
   * Returns the status bar's notice.
   *
   * @return the text, empty when no notice shows
   */
  String noticeText() {
    return notice.getText();
  }

  /**
   * Returns the top row's position field.
   *
   * @return the text
   */
  public String positionText() {
    return position.getText();
  }

  @Override
  public void documentOpened(DocumentController document) {
    newTab(document, tabs.getItemCount());
    showTabs();
    syncToolBar();
    syncEnablement();
  }

  @Override
  public void activeDocumentChanged(DocumentController document) {
    syncToolBar();
    syncEnablement();
    if (document == null) {
      searchBar.unbind();
      removeViews();
      clearStatus();
      return;
    }
    CTabItem item = tabOf(document);
    if (item != null) {
      tabs.setSelection(item);
    }
    showViews(document);
  }

  @Override
  public void documentReplaced(DocumentController closed, DocumentController replacement) {
    CTabItem item = tabOf(closed);
    if (item != null) {
      item.setData(replacement);
      // Tab data dot.
      showTabData(item);
    }
    if (canvas != null && canvas.document() == closed) {
      showViews(replacement);
    }
    syncToolBar();
    syncEnablement();
  }

  /** Disposes the views and creates them over a document's controllers. */
  private void showViews(DocumentController document) {
    removeViews();
    canvas = new LogCanvas(mainHolder, document, controller, keymap, palette, prefixListener);
    canvas.frame().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    overviewBar = new OverviewBar(canvas.frame(), document, palette);
    GridData barData = new GridData(SWT.FILL, SWT.FILL, false, true);
    barData.widthHint = OverviewBar.WIDTH;
    overviewBar.control().setLayoutData(barData);
    canvas.addSide(overviewBar.control());
    showOverview(controller.preferences().overview());
    results = new ResultsPane(resultsHolder, document, controller, keymap, palette, prefixListener);
    colorTabs(results.sets(), palette);
    document.addListener(statusListener);
    document.filtered().addListener(filteredListener);
    ((GridData) position.getLayoutData()).widthHint = POSITION_WIDTH;
    searchBar.bind(document);
    showStatus(document.state());
    showPosition();
    showTime();
    mainHolder.layout(true);
    resultsHolder.layout(true);
    // Filtered view minimum.
    fitSplit();
    canvas.control().setFocus();
  }

  @Override
  public void documentClosed(DocumentController document) {
    removeTab(document);
  }

  /** Removes a tab, and the views when they show its document. */
  private void removeTab(DocumentController document) {
    CTabItem item = tabOf(document);
    if (item != null) {
      item.dispose();
    }
    showTabs();
    if (canvas != null && canvas.document() == document) {
      searchBar.unbind();
      removeViews();
      clearStatus();
    }
    syncEnablement();
  }

  private void removeViews() {
    if (overviewBar != null) {
      overviewBar.control().dispose();
      overviewBar = null;
    }
    if (canvas != null) {
      canvas.document().removeListener(statusListener);
      canvas.document().owner().filtered().removeListener(filteredListener);
      canvas.frame().dispose();
      canvas = null;
    }
    if (results != null) {
      results.control().dispose();
      results = null;
    }
  }

  /** Shows the file fields in the top row and the notes in the status bar. */
  private void showStatus(DocumentViewState state) {
    if (status.isDisposed()) {
      return;
    }
    ZoneId zone = ZoneId.systemDefault();
    String path = state.path().isEmpty() ? state.title() : state.path();
    if (!path.equals(pathField.getText())) {
      pathField.setText(path);
      pathField.setToolTipText(path);
    }
    pathField.setEnabled(true);
    boolean changed = set(sizeLabel, StatusText.size(state.fileBytes()));
    String sizeTip = StatusText.sizeTip(state, zone);
    sizeLabel.setToolTipText(sizeTip.isEmpty() ? null : sizeTip);
    changed |= set(encodingLabel, StatusText.encoding(state));
    if (changed) {
      topRow.layout(true);
    }
    if (set(status, StatusText.notes(state))) {
      statusBar.layout(true);
    }
  }

  private void clearStatus() {
    pathField.setText("");
    pathField.setToolTipText(null);
    pathField.setEnabled(false);
    set(sizeLabel, "");
    set(encodingLabel, "");
    set(position, "");
    set(timeField, "");
    timeField.setToolTipText(null);
    set(status, "");
    topRow.layout(true);
  }

  /** Sets a label's text and reports whether it changed. */
  private static boolean set(Label label, String text) {
    if (label.isDisposed() || text.equals(label.getText())) {
      return false;
    }
    label.setText(text);
    label.setToolTipText(text.isEmpty() ? null : text);
    return true;
  }

  /**
   * Shows the main view's caret position with the size and time difference of a selection: the
   * filtered view's while it has focus and a selection over more than one row, else the main
   * view's.
   */
  private void showPosition() {
    if (position.isDisposed() || canvas == null) {
      return;
    }
    DocumentController document = (DocumentController) canvas.document();
    LineViewController selected = document;
    LineViewController focused = controller.focusedView();
    if (focused != null
        && focused != document
        && focused.owner() == document
        && focused.selection().start().line() != focused.selection().end().line()) {
      selected = focused;
    }
    boolean changed =
        set(
            position,
            PositionText.of(
                document.selection(),
                document.viewport().lineCount(),
                selected.selection(),
                TimeText.difference(selected.timeStatus())));
    GridData data = (GridData) position.getLayoutData();
    int width = Math.max(POSITION_WIDTH, position.computeSize(SWT.DEFAULT, SWT.DEFAULT).x);
    if (changed && width != data.widthHint) {
      data.widthHint = width;
      topRow.layout(true);
    }
  }

  /** Shows the main view's caret time and names the format, zone and offset in the tooltip. */
  private void showTime() {
    if (timeField.isDisposed() || canvas == null) {
      return;
    }
    DocumentController document = (DocumentController) canvas.document();
    TimeController time = document.time();
    if (time.format() == null) {
      set(timeField, "");
      timeField.setToolTipText(null);
    } else {
      if (set(
          timeField, TimeText.caret(document.timeStatus(), time.offset(), time.displayZone()))) {
        topRow.layout(true);
      }
      timeField.setToolTipText(TimeText.tooltip(time.format(), time.zone(), time.offset()));
    }
  }

  /**
   * Returns the top row's time field.
   *
   * @return the text
   */
  public String timeText() {
    return timeField.getText();
  }

  /**
   * Returns the time field's tooltip.
   *
   * @return the text, or null
   */
  String timeTip() {
    return timeField.getToolTipText();
  }

  /**
   * Returns the time field.
   *
   * @return the label
   */
  Label timeLabel() {
    return timeField;
  }

  private void setSplit(int mainPercent) {
    wantedSplit = Math.max(Preferences.MIN_SPLIT, Math.min(Preferences.MAX_SPLIT, mainPercent));
    fitSplit();
  }

  // Filtered view minimum.

  /**
   * Applies the wanted split, giving the filtered view at least {@link #MIN_FILTERED_ROWS} rows
   * while the main view keeps one; a window too short for both gives the main view one row and the
   * filtered view the rest, never under one row while the space allows. Runs on open, on resize,
   * after a sash drag and when the split changes; the wanted split stays as set.
   */
  private void fitSplit() {
    int total = split.getClientArea().height - SASH_WIDTH;
    if (results == null || canvas == null || total <= 0 || split.getMaximizedControl() != null) {
      setWeights(wantedSplit, 100 - wantedSplit);
      return;
    }
    Canvas filtered = results.canvas().control();
    int bottomTrim =
        searchBar.control().getSize().y
            + results.sets().getSize().y
            + filtered.computeTrim(0, 0, 0, 0).height;
    int mainTrim = canvas.control().computeTrim(0, 0, 0, 0).height;
    int bottomOne = bottomTrim + results.canvas().lineHeight();
    int bottomMin = bottomTrim + MIN_FILTERED_ROWS * results.canvas().lineHeight();
    int mainOne = mainTrim + canvas.lineHeight();
    int bottom = total - Math.round(total * wantedSplit / 100f);
    bottom = Math.max(bottom, bottomMin);
    bottom = Math.max(bottomOne, Math.min(bottom, total - mainOne));
    bottom = Math.min(bottom, total);
    setWeights(total - bottom, bottom);
  }

  private void setWeights(int main, int bottom) {
    int[] now = split.getWeights();
    if (now.length == 2 && (long) now[0] * bottom == (long) now[1] * main) {
      return;
    }
    split.setWeights(Math.max(0, main), Math.max(1, bottom));
  }

  /** Takes the split a sash drag left as the wanted split, then applies the minimum. */
  private void sashDragged() {
    int[] weights = split.getWeights();
    int total = weights[0] + weights[1];
    if (total > 0) {
      wantedSplit =
          Math.max(
              Preferences.MIN_SPLIT,
              Math.min(Preferences.MAX_SPLIT, Math.round(weights[0] * 100f / total)));
    }
    fitSplit();
  }

  @Override
  public void setTitle(String title) {
    if (!shell.isDisposed()) {
      shell.setText(title);
    }
  }

  @Override
  public void hide() {
    if (!shell.isDisposed()) {
      shell.setVisible(false);
    }
  }

  @Override
  public void dispose() {
    if (!shell.isDisposed()) {
      shell.dispose();
    }
  }

  @Override
  public void setActive() {
    busy.setActive();
  }

  @Override
  public void setIdle() {
    busy.setIdle();
  }

  @Override
  public void askLineNumber(long lineCount, LineNumberListener listener) {
    new LineNumberDialog(shell, lineCount, listener).open();
  }

  @Override
  public void focusSearch() {
    searchBar.focus();
  }

  @Override
  public void focusQuickFind() {
    if (quickFindBar != null) {
      quickFindBar.focus();
    }
  }

  @Override
  public void focusView(boolean filtered) {
    if (filtered && results != null) {
      results.canvas().control().setFocus();
    } else if (canvas != null) {
      canvas.control().setFocus();
    }
  }

  @Override
  public void showOverview(boolean shown) {
    if (overviewBar == null) {
      return;
    }
    GridData data = (GridData) overviewBar.control().getLayoutData();
    data.exclude = !shown;
    overviewBar.control().setVisible(shown);
    canvas.frame().layout(true);
    overviewBar.geometry();
  }

  /**
   * Returns the overview bar of the shown document.
   *
   * @return the bar, or null when no document is shown
   */
  OverviewBar overviewBar() {
    return overviewBar;
  }

  @Override
  public void showHighlightersDialog() {
    HighlightersDialog dialog = new HighlightersDialog(shell, controller);
    dialog.open();
    remember(dialog.shell(), "highlighters");
  }

  @Override
  public void askFilterName(String suggested, NameListener listener) {
    new NameDialog(
            shell,
            CommandId.SAVE_AS_FILTER.title(),
            UiText.FILTER_NAME_PROMPT.text(),
            suggested,
            ApplicationController.FILTER_NAME_CHARS,
            listener)
        .open();
  }

  @Override
  public void askTabName(String current, NameListener listener) {
    new NameDialog(
            shell,
            UiText.TAB_RENAME.text(),
            UiText.TAB_NAME_PROMPT.text(),
            current,
            ApplicationController.TAB_NAME_CHARS,
            listener)
        .open();
  }

  @Override
  public void showOpenedFiles() {
    new SwitchFileDialog(shell, controller).open();
  }

  @Override
  public boolean launch(Path target) {
    return Program.launch(target.toString());
  }

  @Override
  public boolean launchesByExtension() {
    return PlatformTraits.PLATFORM.launchesByExtension();
  }

  @Override
  public void resizeMainView(int deltaPercent) {
    setSplit(splitPercent() + deltaPercent);
  }

  @Override
  public int splitPercent() {
    return wantedSplit;
  }

  // Save, scratchpad, search across open files and automatic highlighter sets.

  @Override
  public Path chooseSaveFile(String suggestedName) {
    FileDialog dialog = new FileDialog(shell, SWT.SAVE);
    dialog.setText(CommandId.SAVE_SELECTION.title());
    dialog.setFileName(suggestedName);
    dialog.setOverwrite(true);
    String chosen = dialog.open();
    if (chosen == null) {
      return null;
    }
    try {
      return Path.of(chosen);
    } catch (InvalidPathException e) {
      showError(UiText.SAVE_TITLE.text(), UiText.SAVE_FAILED.format(e.getReason()));
      return null;
    }
  }

  @Override
  public void showScratchpad(String text, boolean replace) {
    if (scratchpad == null || scratchpad.isDisposed()) {
      scratchpad = new ScratchpadWindow(shell, controller);
      colorTabs(scratchpad.tabs(), palette);
    }
    if (text != null) {
      scratchpad.add(text, replace);
    }
    scratchpad.open();
  }

  /**
   * Returns the scratchpad window.
   *
   * @return the window, or null before the first use
   */
  ScratchpadWindow scratchpad() {
    return scratchpad;
  }

  @Override
  public void showMultiSearch() {
    if (multiSearch == null || multiSearch.isDisposed()) {
      multiSearch = new MultiSearchWindow(shell, controller);
    }
    multiSearch.open();
  }

  /**
   * Returns the window of the search across open files.
   *
   * @return the window, or null before the first search
   */
  MultiSearchWindow multiSearch() {
    return multiSearch;
  }

  // Search in a folder.

  private FolderSearchWindow folderSearch;

  @Override
  public void showFolderSearch() {
    if (folderSearch == null || folderSearch.isDisposed()) {
      folderSearch = new FolderSearchWindow(shell, controller.folderSearch(), controller::keymap);
    }
    folderSearch.open();
  }

  /**
   * Returns the Search in Folder window.
   *
   * @return the window, or null before the first use
   */
  FolderSearchWindow folderSearch() {
    return folderSearch;
  }

  @Override
  public void showSelectorsDialog() {
    new SelectorsDialog(shell, controller).open();
  }

  // Mark comments and marks list.

  @Override
  public void askMarkComment(long line, String current, NameListener listener) {
    new NameDialog(
            shell,
            UiText.MARK_COMMENT_TITLE.text(),
            UiText.MARK_COMMENT_PROMPT.format(line + 1),
            current,
            MarkComments.MAX_LENGTH,
            listener)
        .open();
  }

  @Override
  public void showMarks() {
    if (marks == null || marks.isDisposed()) {
      marks = new MarksWindow(shell, controller);
    }
    marks.open();
  }

  /**
   * Returns the marks list window.
   *
   * @return the window, or null before the first use
   */
  MarksWindow marks() {
    return marks;
  }

  @Override
  public void showHistoryDialog() {
    HistoryDialog dialog = new HistoryDialog(shell, controller);
    dialog.open();
    remember(dialog.shell(), "history");
  }

  @Override
  public void showFiltersDialog() {
    FiltersDialog dialog = new FiltersDialog(shell, controller);
    dialog.open();
    remember(dialog.shell(), "filters");
  }

  @Override
  public void showNotice(String message) {
    if (notice.isDisposed()) {
      return;
    }
    notice.setText(message);
    shell.layout(new Control[] {notice});
    final long shown = ++noticeGeneration;
    dispatcher.timerExec(
        NOTICE_MILLIS,
        new Runnable() {
          @Override
          public void run() {
            if (shown == noticeGeneration && !notice.isDisposed()) {
              notice.setText("");
              // A long notice left the notes no width.
              statusBar.layout(true);
            }
          }
        });
  }

  @Override
  public void showKeyboardShortcuts() {
    if (shortcuts == null || shortcuts.shell().isDisposed()) {
      shortcuts = new ShortcutsWindow(shell, keymap, this);
      shortcuts.open();
      remember(shortcuts.shell(), "shortcuts");
    } else {
      shortcuts.shell().setActive();
    }
  }

  @Override
  public void showText(String title, String text) {
    Shell owner = modalOwner();
    TextWindow window = new TextWindow(owner, title, text, this, owner != shell);
    window.open();
    remember(window.shell(), "text");
  }

  /** Returns the innermost visible modal dialog over the main window, or the main window. */
  private Shell modalOwner() {
    Shell owner = shell;
    boolean found = true;
    while (found) {
      found = false;
      for (Shell child : owner.getShells()) {
        if (child.isVisible() && (child.getStyle() & SWT.APPLICATION_MODAL) != 0) {
          owner = child;
          found = true;
          break;
        }
      }
    }
    return owner;
  }

  @Override
  public void showAbout(AboutInfo info) {
    Runnable licenses =
        new Runnable() {
          @Override
          public void run() {
            controller.showLicenses();
          }
        };
    new AboutDialog(shell, info, licenses, this).open();
  }

  @Override
  public String toolkitDescription() {
    String gtk = System.getProperty(GTK_VERSION);
    return "SWT "
        + SWT.getVersion()
        + " "
        + PlatformTraits.PLATFORM.name()
        + (gtk == null ? "" : " (GTK " + gtk + ")");
  }

  @Override
  public List<Path> chooseFilesToOpen(Path initialDirectory) {
    FileDialog dialog = new FileDialog(shell, SWT.OPEN | SWT.MULTI);
    dialog.setText(UiText.OPEN_DIALOG_TITLE.text());
    if (initialDirectory != null) {
      dialog.setFilterPath(initialDirectory.toString());
    }
    List<Path> chosen = new ArrayList<Path>();
    if (dialog.open() == null) {
      return chosen;
    }
    for (String name : dialog.getFileNames()) {
      try {
        chosen.add(Path.of(dialog.getFilterPath(), name));
      } catch (InvalidPathException e) {
        showError(UiText.OPEN_FAILED_TITLE.text(), UiText.OPEN_INVALID_PATH.format(e.getReason()));
      }
    }
    return chosen;
  }

  @Override
  public void setText(String text) {
    if (shell.isDisposed()) {
      return;
    }
    if (clipboard == null) {
      clipboard = new Clipboard(shell.getDisplay());
    }
    clipboard.setContents(new Object[] {text}, new Transfer[] {TextTransfer.getInstance()});
  }

  @Override
  public String clipboardText() {
    if (shell.isDisposed()) {
      return null;
    }
    if (clipboard == null) {
      clipboard = new Clipboard(shell.getDisplay());
    }
    Object text = clipboard.getContents(TextTransfer.getInstance());
    return text instanceof String value ? value : null;
  }

  @Override
  public void showError(String title, String message) {
    if (!shell.isDisposed()) {
      new ErrorDialog(shell, title, message).open();
    }
  }

  @Override
  public void showInfo(String title, String message) {
    if (!shell.isDisposed()) {
      new ErrorDialog(shell, title, message).open();
    }
  }

  private static WindowGeometry.Screen screen(Monitor monitor) {
    return new WindowGeometry.Screen(rect(monitor.getBounds()), rect(monitor.getClientArea()));
  }

  private static WindowGeometry.Rect rect(Rectangle r) {
    return new WindowGeometry.Rect(r.x, r.y, r.width, r.height);
  }

  // Preferences dialog, zoom and remembered window state.

  /** Keeps the normal bounds whenever the window moves or resizes unmaximized. */
  private void trackWindowState() {
    Listener track =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (shell.isDisposed() || shell.getMaximized() || shell.getMinimized()) {
              return;
            }
            Point location = shell.getLocation();
            Rectangle client = shell.getClientArea();
            if (client.width > 0 && client.height > 0) {
              normalBounds =
                  new WindowGeometry.Request(location.x, location.y, client.width, client.height);
            }
          }
        };
    shell.addListener(SWT.Move, track);
    shell.addListener(SWT.Resize, track);
  }

  @Override
  public WindowGeometry.Request windowBounds() {
    return normalBounds;
  }

  @Override
  public boolean windowMaximized() {
    return !shell.isDisposed() && shell.getMaximized();
  }

  // Multiple windows, logging and support items.

  @Override
  public void toFront() {
    if (shell.isDisposed()) {
      return;
    }
    if (shell.getMinimized()) {
      shell.setMinimized(false);
    }
    shell.forceActive();
  }

  @Override
  public void showWarningMarker(boolean shown) {
    if (warnings.isDisposed() || warnings.getVisible() == shown) {
      return;
    }
    warnings.setVisible(shown);
    ((GridData) warnings.getLayoutData()).exclude = !shown;
    statusBar.layout(true);
  }

  /**
   * Reports whether the status bar shows the warning marker.
   *
   * @return true when shown
   */
  boolean warningMarkerShown() {
    return !warnings.isDisposed() && warnings.getVisible();
  }

  @Override
  public void showLog(String text) {
    if (logWindow == null || logWindow.shell().isDisposed()) {
      logWindow = new TextWindow(shell, UiText.SHOW_LOG_TITLE.text(), text, this);
      logWindow.open();
      remember(logWindow.shell(), "log");
      logWindow.showStart();
    } else {
      logWindow.setContent(text);
      logWindow.shell().setActive();
    }
  }

  /**
   * Returns the Show Log window.
   *
   * @return the shell, or null before the first Show Log
   */
  Shell logShell() {
    return logWindow == null ? null : logWindow.shell();
  }

  @Override
  public boolean launchUrl(String url) {
    return Program.launch(url);
  }

  /**
   * Runs window bindings that are not a menu item's accelerator, such as a command's second window
   * key, from a Display filter while this window has a key event.
   */
  private void dispatchWindowKeys() {
    final Display display = shell.getDisplay();
    final Listener filter =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (controller == null
                || !(event.widget instanceof Control control)
                || control.isDisposed()
                || control.getShell() != shell) {
              return;
            }
            KeyStroke stroke = SwtKeys.stroke(event.stateMask, event.keyCode, event.character);
            CommandId command = stroke == null ? null : keymap.windowCommand(stroke);
            if (command != null) {
              event.type = SWT.None;
              event.doit = false;
              controller.execute(command);
            }
          }
        };
    display.addFilter(SWT.KeyDown, filter);
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            display.removeFilter(SWT.KeyDown, filter);
          }
        });
  }

  /**
   * Rebuilds the menu bar and the views after the event that changed the key bindings, which may
   * come from a menu item or a view about to be replaced. The focused view keeps the focus.
   */
  @Override
  public void keymapChanged() {
    dispatcher.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (!shell.isDisposed() && !controller.isShuttingDown()) {
              rebuild();
            }
          }
        });
  }

  private void rebuild() {
    keymap = controller.keymap();
    buildMenuBar();
    searchBar.setKeymap(keymap);
    for (Map.Entry<CommandId, ToolItem> entry : toolItems.entrySet()) {
      if (!entry.getValue().isDisposed()) {
        entry.getValue().setToolTipText(toolTip(entry.getKey()));
      }
    }
    // Tab strip buttons.
    tabButtons.setLabels(toolTip(CommandId.OPEN), toolTip(CommandId.CLOSE));
    if (shortcuts != null && !shortcuts.shell().isDisposed()) {
      shortcuts.shell().dispose();
    }
    if (canvas != null) {
      boolean filtered = results != null && controller.focusedView() == results.canvas().document();
      showViews((DocumentController) canvas.document());
      if (filtered) {
        results.canvas().control().setFocus();
      }
    }
  }

  /** Applies the font to both log views in place; the menu bar and the focus stay. */
  @Override
  public void fontChanged() {
    FontSettings font = controller.preferences().options().font();
    if (canvas != null) {
      canvas.setFont(font);
    }
    if (results != null) {
      results.canvas().setFont(font);
    }
  }

  @Override
  public void showPreferences() {
    PreferencesDialog dialog = new PreferencesDialog(shell, controller);
    dialog.open();
    remember(dialog.shell(), "preferences");
  }

  @Override
  public int platformFontSize() {
    return Dialogs.monospaceSize();
  }

  /** Gives a dialog its remembered size and remembers its size when it closes. */
  private void remember(final Shell dialog, final String name) {
    UiState.Size size = controller.dialogSize(name);
    if (size != null) {
      Rectangle owner = shell.getBounds();
      dialog.setBounds(
          owner.x + Math.max(0, (owner.width - size.width()) / 2),
          owner.y + Math.max(0, (owner.height - size.height()) / 2),
          size.width(),
          size.height());
    }
    dialog.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (controller != null && !controller.isShuttingDown()) {
              Point current = dialog.getSize();
              controller.rememberDialogSize(name, current.x, current.y);
            }
          }
        });
  }

  /**
   * Returns the Encoding menu.
   *
   * @return the menu
   */
  Menu encodingMenu() {
    return menuBar.encodingMenu();
  }

  /**
   * Returns File > Favorites.
   *
   * @return the cascade, filled when the File menu shows
   */
  Menu favoritesMenu() {
    return menuBar.favoritesMenu();
  }

  /**
   * Returns File > Recent Files.
   *
   * @return the cascade, filled when the File menu shows
   */
  Menu recentMenu() {
    return menuBar.recentMenu();
  }

  // User guide.

  /**
   * Shows the user guide in a {@link GuideWindow}, or a notice naming the file, with no {@code
   * Browser} created, where {@link PlatformTraits#opensBrowser} is false.
   */
  @Override
  public boolean showGuide(Path file) {
    if (!PlatformTraits.PLATFORM.opensBrowser()) {
      showNotice(UiText.NOTICE_GUIDE_AT.format(file));
      return true;
    }
    if (guide != null && !guide.shell().isDisposed()) {
      guide.show(file);
      return true;
    }
    GuideWindow created = GuideWindow.create(shell, file, this::guideFailed);
    if (created == null) {
      return false;
    }
    guide = created;
    created.open();
    if (!created.shell().isDisposed()) {
      remember(created.shell(), "guide");
    }
    return true;
  }

  private void guideFailed(Path file) {
    if (controller != null && !shell.isDisposed()) {
      controller.showGuideElsewhere(file);
    }
  }

  /**
   * Returns the user guide window.
   *
   * @return the window, or null before the guide was shown in one
   */
  GuideWindow guideWindow() {
    return guide;
  }

  // Timestamps.

  @Override
  public void askGoToTime(String initial, ZoneId zone, LocalDate caretDate, TimeListener listener) {
    new GoToTimeDialog(shell, initial, zone, caretDate, listener).open();
  }

  @Override
  public void askTimeOffset(
      long offset, long caret, int caretDigits, ZoneId zone, TimeListener listener) {
    new TimeOffsetDialog(shell, offset, caret, caretDigits, zone, listener).open();
  }

  @Override
  public void askTimeZone(String current, ZoneId defaultZone, NameListener listener) {
    new TimeZoneDialog(shell, current, defaultZone, listener).open();
  }

  @Override
  public void showTimestampFormats(
      List<UserFormat> formats,
      String caretLine,
      TimeContext context,
      ZoneId zone,
      FormatsListener listener) {
    new TimestampFormatsDialog(shell, formats, caretLine, context, zone, listener).open();
  }

  // Command palette.

  @Override
  public void showCommandPalette() {
    if (shell.isDisposed() || controller == null) {
      return;
    }
    if (paletteWindow != null && !paletteWindow.shell().isDisposed()) {
      paletteWindow.shell().dispose();
    }
    paletteWindow =
        new CommandPaletteWindow(shell, controller.commandPalette(), keymap, dispatcher);
    paletteWindow.open();
  }

  /**
   * Returns the command palette.
   *
   * @return the last one opened, or null
   */
  CommandPaletteWindow commandPalette() {
    return paletteWindow;
  }

  // Always on Top.

  @Override
  public boolean onTopSupported() {
    return WindowStacking.PLATFORM.supported();
  }

  @Override
  public void setOnTop(boolean onTop) {
    WindowStacking.PLATFORM.setOnTop(shell, onTop);
  }
}
