package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.about.AboutInfo;
import io.github.ajdailey.qlogg.app.command.CommandGroup;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.KeyStroke;
import io.github.ajdailey.qlogg.app.command.Keymap;
import io.github.ajdailey.qlogg.app.command.MenuTable;
import io.github.ajdailey.qlogg.app.config.Options;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.UiState;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.ApplicationListener;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.highlight.HighlightSettings;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.theme.PaletteListener;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.BusyCounter;
import io.github.ajdailey.qlogg.app.view.DocumentViewListener;
import io.github.ajdailey.qlogg.app.view.DocumentViewState;
import io.github.ajdailey.qlogg.app.view.MainView;
import io.github.ajdailey.qlogg.app.view.PositionText;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.StatusText;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.Visibility;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.nio.charset.Charset;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
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
 * (Open, Reload, Follow), the read-only path field, Stop, and the shown file's size, modification
 * time, encoding and position; a row of tabs, one per open file, shown while two or more are open;
 * the main log view with the {@link OverviewBar} beside it over the bottom pane, split by a sash
 * with a grip; the bottom pane holds the {@link SearchBar} over the {@link ResultsPane}; then the
 * {@link QuickFindBar} and a status bar with the document notes, notices and the key-prefix field.
 * The views show the active tab: switching tabs disposes them and creates them over the other
 * document's controllers, which hold the viewport, selection and rows. Tabs show file names and
 * move by dragging; a tab's close button or a middle click closes its file, and its context menu
 * holds the close variants, the path commands and Rename Tab. The path field's context menu holds
 * the path commands, Copy and Select All. Files dropped on the window open a tab each. A palette
 * change from the theme recolors both log views and the overview bar at once; native widgets follow
 * the system. Created, placed and opened on the UI thread; a close request from the window system
 * is vetoed and forwarded to the close handler, which ends with {@link #dispose}. Every dialog it
 * opens returns at once; none runs a nested event loop except the platform file chooser.
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

  /** Check items whose state does not depend on a document. */
  private static final Set<CommandId> APPLICATION_CHECKS =
      EnumSet.of(
          CommandId.RUN_SEARCH_ON_ADD,
          CommandId.FOLLOW_ON_LOAD,
          CommandId.FOLLOW_ON_OVERSCROLL,
          CommandId.TOGGLE_OVERVIEW,
          CommandId.TOGGLE_MATCH_HIGHLIGHT,
          CommandId.THEME_LIGHT,
          CommandId.THEME_DARK,
          CommandId.THEME_SYSTEM,
          CommandId.NO_HIGHLIGHTERS,
          // Wrap on open, whitespace markers, hidden ANSI colors and block selection.
          CommandId.WRAP_ON_OPEN,
          CommandId.TOGGLE_WHITESPACE,
          CommandId.HIDE_ANSI,
          CommandId.BLOCK_SELECTION,
          // Debug log file.
          CommandId.DEBUG_LOG_FILE);

  /** Commands that need a document with a file. */
  private static final Set<CommandId> FILE_COMMANDS =
      EnumSet.of(
          CommandId.RELOAD,
          CommandId.COPY_PATH,
          CommandId.COPY_FILE_NAME,
          CommandId.OPEN_FOLDER,
          CommandId.OPEN_IN_EDITOR);

  /** Narrowest the path field gets in the top row, in pixels. */
  static final int MIN_PATH_WIDTH = 160;

  /** Width of the position field, in pixels. */
  static final int POSITION_WIDTH = 190;

  /** Height of the splitter between the main view and the bottom pane, in pixels. */
  static final int SASH_WIDTH = 7;

  private final Shell shell;
  private Keymap keymap;
  private final Composite topRow;
  private final ToolBar toolBar;
  private final ToolIcons toolIcons;
  private final Text pathField;
  private final Label sizeLabel;
  private final Label modifiedLabel;
  private final Label encodingLabel;
  private final Composite statusBar;
  private final Map<CommandId, ToolItem> toolItems =
      new EnumMap<CommandId, ToolItem>(CommandId.class);
  private final Map<CommandId, MenuItem> fileItems =
      new EnumMap<CommandId, MenuItem>(CommandId.class);
  private Menu openedFilesMenu;
  private Palette palette;
  private final List<Image> icons;
  private final SearchBar searchBar;
  private final CTabFolder tabs;
  private final SashForm split;
  private final Composite mainHolder;
  private final Composite resultsHolder;
  private final Label status;
  private final Label notice;
  private final Label position;
  private final Label prefix;
  private final BusyCounter busy;
  private final SwtUiDispatcher dispatcher;
  private final Map<CommandId, MenuItem> checkItems =
      new EnumMap<CommandId, MenuItem>(CommandId.class);
  private ApplicationController controller;
  private QuickFindBar quickFindBar;
  private LogCanvas canvas;
  private ResultsPane results;
  private OverviewBar overviewBar;
  private CTabItem dragged;
  // Tab drag and tab context menu.
  private boolean movingTab;
  private Menu tabMenu;
  private Menu highlightMenu;
  // Recent files, favorites and encoding menus.
  private Menu recentMenu;
  private Menu favoritesMenu;
  private Menu encodingMenu;
  private WindowGeometry.Request normalBounds = new WindowGeometry.Request(null, null, null, null);
  private ShortcutsWindow shortcuts;
  private Clipboard clipboard;
  // Scratchpad and the search across open files.
  private ScratchpadWindow scratchpad;
  private MultiSearchWindow multiSearch;
  // Multiple windows, logging and support items.
  private final Link warnings;
  private TextWindow logWindow;
  // User guide.
  private GuideWindow guide;
  private PaletteListener paletteListener;
  private long noticeGeneration;
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
    modifiedLabel = infoLabel();
    encodingLabel = infoLabel();
    position = infoLabel();
    ((GridData) position.getLayoutData()).widthHint = POSITION_WIDTH;
    tabs = new CTabFolder(shell, SWT.TOP | SWT.FLAT);
    GridData tabsData = new GridData(SWT.FILL, SWT.TOP, true, false);
    // The folder adds its tab row as trim around the hint, which is the empty client area.
    tabsData.heightHint = 1;
    tabsData.exclude = true;
    tabs.setLayoutData(tabsData);
    tabs.setVisible(false);
    split = new SashForm(shell, SWT.VERTICAL);
    split.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    split.setSashWidth(SASH_WIDTH);
    mainHolder = new Composite(split, SWT.NONE);
    GridLayout mainLayout = new GridLayout(2, false);
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
    boolean file = document != null && !document.document().inMemory();
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
            if (event.detail == DND.DROP_DEFAULT || event.detail == DND.DROP_MOVE) {
              event.detail = DND.DROP_COPY;
            }
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
  }

  /**
   * Tab selection, close buttons and dragging go to the controller. While one tab is open, its
   * close button and a middle click on it do nothing; File > Close and Close All still close it.
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
            CTabItem target = tabs.getItem(new Point(event.x, event.y));
            if (source != null && target != null && source != target && !source.isDisposed()) {
              moveTab(source, tabs.indexOf(target));
            }
          }
        });
  }

  /**
   * Opens a tab's context menu: the close variants, then the path commands, then the tab name. One
   * menu serves every tab; each call replaces its items.
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
    int index = controller.documents().indexOf(document);
    int count = controller.documents().size();
    boolean file = !document.document().inMemory();
    tabMenuItem(
        menu,
        UiText.TAB_CLOSE,
        true,
        new Runnable() {
          @Override
          public void run() {
            controller.closeDocument(document);
          }
        });
    tabMenuItem(
        menu,
        UiText.TAB_CLOSE_OTHERS,
        count > 1,
        new Runnable() {
          @Override
          public void run() {
            controller.closeOthers(document);
          }
        });
    tabMenuItem(
        menu,
        UiText.TAB_CLOSE_LEFT,
        index > 0,
        new Runnable() {
          @Override
          public void run() {
            controller.closeSide(document, true);
          }
        });
    tabMenuItem(
        menu,
        UiText.TAB_CLOSE_RIGHT,
        index < count - 1,
        new Runnable() {
          @Override
          public void run() {
            controller.closeSide(document, false);
          }
        });
    tabMenuItem(
        menu,
        UiText.TAB_CLOSE_ALL,
        true,
        new Runnable() {
          @Override
          public void run() {
            controller.closeAll();
          }
        });
    new MenuItem(menu, SWT.SEPARATOR);
    tabMenuItem(
        menu,
        UiText.TAB_COPY_PATH,
        file,
        new Runnable() {
          @Override
          public void run() {
            controller.copyPath(document);
          }
        });
    tabMenuItem(
        menu,
        UiText.TAB_COPY_FILE_NAME,
        file,
        new Runnable() {
          @Override
          public void run() {
            controller.copyFileName(document);
          }
        });
    tabMenuItem(
        menu,
        UiText.TAB_OPEN_FOLDER,
        file,
        new Runnable() {
          @Override
          public void run() {
            controller.openContainingFolder(document);
          }
        });
    new MenuItem(menu, SWT.SEPARATOR);
    tabMenuItem(
        menu,
        UiText.TAB_RENAME,
        file,
        new Runnable() {
          @Override
          public void run() {
            controller.renameTab(document);
          }
        });
    tabMenuItem(
        menu,
        UiText.TAB_RESET_NAME,
        file,
        new Runnable() {
          @Override
          public void run() {
            controller.resetTabName(document);
          }
        });
    menu.setLocation(x, y);
    menu.setVisible(true);
    return menu;
  }

  private static void tabMenuItem(Menu menu, UiText label, boolean enabled, final Runnable action) {
    MenuItem item = new MenuItem(menu, SWT.PUSH);
    item.setText(label.text());
    item.setEnabled(enabled);
    item.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            action.run();
          }
        });
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

  private CTabItem newTab(DocumentController document, int index) {
    CTabItem item = new CTabItem(tabs, SWT.CLOSE, Math.min(index, tabs.getItemCount()));
    item.setData(document);
    String path = document.state().path();
    item.setToolTipText(path.isEmpty() ? null : path);
    item.setText(controller.tabTitle(document));
    return item;
  }

  @Override
  public void tabRenamed(DocumentController document) {
    CTabItem item = tabOf(document);
    if (item != null) {
      item.setText(controller.tabTitle(document));
    }
  }

  /**
   * Builds the path field's context menu: Copy Full Path, Copy File Name and Open Containing Folder
   * for the shown file, then Copy and Select All for the field's text.
   */
  private Menu pathMenu() {
    final Menu menu = new Menu(pathField);
    final MenuItem copyPath = pathMenuItem(menu, UiText.TAB_COPY_PATH, CommandId.COPY_PATH);
    final MenuItem copyName =
        pathMenuItem(menu, UiText.TAB_COPY_FILE_NAME, CommandId.COPY_FILE_NAME);
    final MenuItem folder = pathMenuItem(menu, UiText.TAB_OPEN_FOLDER, CommandId.OPEN_FOLDER);
    new MenuItem(menu, SWT.SEPARATOR);
    final MenuItem copy = new MenuItem(menu, SWT.PUSH);
    copy.setText(UiText.PATH_COPY.text());
    copy.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            pathField.copy();
          }
        });
    new MenuItem(menu, SWT.SEPARATOR);
    MenuItem selectAll = new MenuItem(menu, SWT.PUSH);
    selectAll.setText(UiText.PATH_SELECT_ALL.text());
    selectAll.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            pathField.selectAll();
          }
        });
    menu.addListener(
        SWT.Show,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            DocumentController document = controller == null ? null : controller.activeDocument();
            boolean file = document != null && !document.document().inMemory();
            copyPath.setEnabled(file);
            copyName.setEnabled(file);
            folder.setEnabled(file);
            copy.setEnabled(pathField.getSelectionCount() > 0);
          }
        });
    return menu;
  }

  private MenuItem pathMenuItem(Menu menu, UiText label, final CommandId command) {
    MenuItem item = new MenuItem(menu, SWT.PUSH);
    item.setText(label.text());
    item.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (controller != null) {
              controller.execute(command);
            }
          }
        });
    return item;
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

  /** Shows the tab row while two or more files are open. */
  private void showTabs() {
    boolean shown = tabs.getItemCount() > 1;
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

  /** Recolors the log views and the overview bar. */
  private void applyPalette(Palette next) {
    palette = next;
    if (canvas != null) {
      canvas.setPalette(next);
    }
    if (results != null) {
      results.canvas().setPalette(next);
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
    Menu old = shell.getMenuBar();
    checkItems.clear();
    fileItems.clear();
    Menu bar = new Menu(shell, SWT.BAR);
    boolean mac = SwtKeys.mac();
    boolean appMenu = AppMenu.present(shell.getDisplay());
    for (CommandGroup group : CommandGroup.values()) {
      if (!group.menu()) {
        continue;
      }
      MenuItem header = new MenuItem(bar, SWT.CASCADE);
      header.setText(group.label());
      Menu menu = new Menu(shell, SWT.DROP_DOWN);
      header.setMenu(menu);
      Menu target = menu;
      String cascade = null;
      for (final CommandId command : MenuTable.commands(group, appMenu)) {
        String submenu = command.submenu();
        if (submenu == null) {
          target = menu;
          cascade = null;
        } else if (!submenu.equals(cascade)) {
          if (command.separatorBefore() && menu.getItemCount() > 0) {
            new MenuItem(menu, SWT.SEPARATOR);
          }
          MenuItem holder = new MenuItem(menu, SWT.CASCADE);
          holder.setText(submenu);
          target = new Menu(shell, SWT.DROP_DOWN);
          holder.setMenu(target);
          cascade = submenu;
        }
        if (command.separatorBefore() && target.getItemCount() > 0) {
          new MenuItem(target, SWT.SEPARATOR);
        }
        UiText cascadeBefore = MenuTable.cascadeBefore(command);
        if (cascadeBefore != null) {
          MenuItem opened = new MenuItem(target, SWT.CASCADE);
          opened.setText(cascadeBefore.text());
          openedFilesMenu = new Menu(shell, SWT.DROP_DOWN);
          opened.setMenu(openedFilesMenu);
        }
        MenuItem item = new MenuItem(target, command.check() ? SWT.CHECK : SWT.PUSH);
        item.setData(command);
        String keys = keymap.keysText(command, mac);
        item.setText(keys.isEmpty() ? command.label() : command.label() + "\t" + keys);
        KeyStroke accelerator = keymap.accelerator(command);
        if (accelerator != null) {
          item.setAccelerator(SwtKeys.accelerator(accelerator));
        }
        item.addListener(
            SWT.Selection,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                controller.execute(command);
              }
            });
        if (command.check()) {
          checkItems.put(command, item);
        }
        if (command == CommandId.CLEAN_RECENT) {
          recentMenu = target;
        }
        if (FILE_COMMANDS.contains(command)) {
          fileItems.put(command, item);
        }
      }
      if (group == CommandGroup.HIGHLIGHT) {
        highlightMenu = menu;
      }
      if (group == CommandGroup.ENCODING) {
        encodingMenu = menu;
      }
      if (group == CommandGroup.FAVORITES) {
        favoritesMenu = menu;
      }
      menu.addListener(
          SWT.Show,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              syncMenu();
            }
          });
    }
    shell.setMenuBar(bar);
    if (old != null) {
      old.dispose();
    }
    syncEnablement();
  }

  /**
   * Sets each check item from the controllers' state, enables the commands that need a file, and
   * lists the highlighter sets and the open files.
   */
  void syncMenu() {
    DocumentController document = controller == null ? null : controller.activeDocument();
    syncHighlightMenu();
    syncOpenedFilesMenu();
    syncListedMenus(document);
    syncEnablement();
    for (Map.Entry<CommandId, MenuItem> entry : checkItems.entrySet()) {
      MenuItem item = entry.getValue();
      if (item.isDisposed()) {
        continue;
      }
      CommandId command = entry.getKey();
      if (APPLICATION_CHECKS.contains(command)) {
        item.setSelection(controller != null && applicationChecked(command));
        continue;
      }
      item.setSelection(document != null && checked(command, document));
    }
    syncEncodingMenu(document);
  }

  /**
   * Enables the menu items that need a file or a document. Runs on every document change as well as
   * on menu Show: a disabled item's accelerator does not fire.
   */
  void syncEnablement() {
    DocumentController document = controller == null ? null : controller.activeDocument();
    boolean file = document != null && !document.document().inMemory();
    for (MenuItem item : fileItems.values()) {
      if (!item.isDisposed()) {
        item.setEnabled(file);
      }
    }
    for (Map.Entry<CommandId, MenuItem> entry : checkItems.entrySet()) {
      MenuItem item = entry.getValue();
      if (!item.isDisposed() && !APPLICATION_CHECKS.contains(entry.getKey())) {
        item.setEnabled(document != null);
      }
    }
  }

  /** The state of a check item in {@link #APPLICATION_CHECKS}. */
  private boolean applicationChecked(CommandId command) {
    ThemeMode theme = controller.theme().mode();
    HighlightSettings highlights = controller.highlightSettings();
    return switch (command) {
      case RUN_SEARCH_ON_ADD -> controller.runSearchOnAdd();
      case FOLLOW_ON_LOAD -> controller.preferences().follow().onLoad();
      case FOLLOW_ON_OVERSCROLL -> controller.preferences().follow().onOverscroll();
      case TOGGLE_OVERVIEW -> controller.preferences().overview();
      case TOGGLE_MATCH_HIGHLIGHT -> highlights.match().enabled();
      case THEME_LIGHT -> theme == ThemeMode.LIGHT;
      case THEME_DARK -> theme == ThemeMode.DARK;
      case THEME_SYSTEM -> theme == ThemeMode.SYSTEM;
      case NO_HIGHLIGHTERS -> highlights.active().isEmpty();
      case WRAP_ON_OPEN -> controller.preferences().view().wrapOnOpen();
      case TOGGLE_WHITESPACE -> controller.preferences().view().showWhitespace();
      case HIDE_ANSI -> controller.preferences().view().hideAnsi();
      case BLOCK_SELECTION -> controller.blockMode();
      case DEBUG_LOG_FILE -> controller.debugLogFile();
      default -> false;
    };
  }

  /** Lists the open files in View > Opened Files, the shown one checked; choosing one shows it. */
  private void syncOpenedFilesMenu() {
    if (openedFilesMenu == null || openedFilesMenu.isDisposed() || controller == null) {
      return;
    }
    for (MenuItem item : openedFilesMenu.getItems()) {
      item.dispose();
    }
    DocumentController shown = controller.activeDocument();
    for (final DocumentController document : controller.documents()) {
      MenuItem item = new MenuItem(openedFilesMenu, SWT.RADIO);
      String path = document.state().path();
      item.setText(path.isEmpty() ? controller.tabTitle(document) : path);
      item.setSelection(document == shown);
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              if (((MenuItem) event.widget).getSelection()) {
                controller.activate(document);
              }
            }
          });
    }
  }

  /**
   * Returns View > Opened Files.
   *
   * @return the submenu, filled when the View menu shows
   */
  Menu openedFilesMenu() {
    return openedFilesMenu;
  }

  /** Lists the highlighter sets after None in the Highlighters menu, the active ones checked. */
  private void syncHighlightMenu() {
    if (highlightMenu == null || highlightMenu.isDisposed() || controller == null) {
      return;
    }
    MenuItem none = checkItems.get(CommandId.NO_HIGHLIGHTERS);
    for (MenuItem item : highlightMenu.getItems()) {
      if (item.getData() instanceof HighlightSet) {
        item.dispose();
      }
    }
    HighlightSettings settings = controller.highlightSettings();
    int at = highlightMenu.indexOf(none) + 1;
    for (final HighlightSet set : settings.sets()) {
      MenuItem item = new MenuItem(highlightMenu, SWT.CHECK, at++);
      item.setText(set.name());
      item.setData(set);
      item.setSelection(settings.isActive(set.id()));
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              controller.toggleHighlightSet(set.id());
            }
          });
    }
  }

  private static boolean checked(CommandId command, DocumentController document) {
    SearchOptions options = document.search().options();
    Visibility visibility = document.filtered().visibility();
    return switch (command) {
      case FOLLOW -> document.following();
      case TOGGLE_LINE_NUMBERS -> document.lineNumbers();
      case TOGGLE_FILTERED_LINE_NUMBERS -> document.filtered().lineNumbers();
      case SHOW_MARKS_AND_MATCHES -> visibility == Visibility.MARKS_AND_MATCHES;
      case SHOW_MARKS -> visibility == Visibility.MARKS;
      case SHOW_MATCHES -> visibility == Visibility.MATCHES;
      case TOGGLE_CASE -> options.caseSensitive();
      case TOGGLE_REGEX -> options.mode() == MatchMode.REGEX;
      case TOGGLE_INVERSE -> options.inverse();
      case TOGGLE_COMBINING -> options.combining();
      case TOGGLE_AUTO_REFRESH -> options.autoRefresh();
      case TOGGLE_KEEP_RESULTS -> options.keepResults();
      case TOGGLE_WRAP -> document.wrap();
      default -> false;
    };
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
    boolean positionSupported = !"wayland".equals(System.getProperty(GDK_BACKEND));
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
    // On win32, computeTrim overstates the height once a menu bar is set; place again with the
    // trim measured from the sized shell.
    if ("win32".equals(SWT.getPlatform())) {
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
   * @return size, modification time and encoding separated by {@link StatusText#GAP}
   */
  public String infoText() {
    return sizeLabel.getText()
        + StatusText.GAP
        + modifiedLabel.getText()
        + StatusText.GAP
        + encodingLabel.getText();
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
    canvas.control().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    overviewBar = new OverviewBar(mainHolder, document, palette);
    GridData barData = new GridData(SWT.FILL, SWT.FILL, false, true);
    barData.widthHint = OverviewBar.WIDTH;
    overviewBar.control().setLayoutData(barData);
    showOverview(controller.preferences().overview());
    results = new ResultsPane(resultsHolder, document, controller, keymap, palette, prefixListener);
    document.addListener(statusListener);
    searchBar.bind(document);
    showStatus(document.state());
    showPosition();
    mainHolder.layout(true);
    resultsHolder.layout(true);
    canvas.control().setFocus();
  }

  @Override
  public void documentClosed(DocumentController document) {
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
    if (canvas != null) {
      canvas.document().removeListener(statusListener);
      canvas.control().dispose();
      canvas = null;
    }
    if (results != null) {
      results.control().dispose();
      results = null;
    }
    if (overviewBar != null) {
      overviewBar.control().dispose();
      overviewBar = null;
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
    changed |= set(modifiedLabel, StatusText.modified(state, zone));
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
    set(modifiedLabel, "");
    set(encodingLabel, "");
    set(position, "");
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

  private void showPosition() {
    if (position.isDisposed() || canvas == null) {
      return;
    }
    DocumentController document = (DocumentController) canvas.document();
    set(position, PositionText.of(document.selection(), document.viewport().lineCount()));
  }

  private void setSplit(int mainPercent) {
    int percent = Math.max(Preferences.MIN_SPLIT, Math.min(Preferences.MAX_SPLIT, mainPercent));
    split.setWeights(percent, 100 - percent);
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
    mainHolder.layout(true);
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
  public void resizeMainView(int deltaPercent) {
    setSplit(splitPercent() + deltaPercent);
  }

  @Override
  public int splitPercent() {
    int[] weights = split.getWeights();
    int total = weights[0] + weights[1];
    return total <= 0 ? Preferences.DEFAULTS.splitPercent() : Math.round(weights[0] * 100f / total);
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
      scratchpad = new ScratchpadWindow(shell);
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

  @Override
  public void showSelectorsDialog() {
    new SelectorsDialog(shell, controller).open();
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
    // Native chrome can differ from the palette (cocoa, win32 before a restart).
    Icons.Variant variant =
        ToolIcons.dark(shell.getBackground()) ? Icons.Variant.DARK : Icons.Variant.LIGHT;
    new AboutDialog(shell, info, variant, licenses, this).open();
  }

  @Override
  public String toolkitDescription() {
    String gtk = System.getProperty(GTK_VERSION);
    return "SWT "
        + SWT.getVersion()
        + " "
        + SWT.getPlatform()
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

  // Options dialog, recent files, favorites, encoding, zoom and remembered window state.

  /** Charsets the Encoding menu lists first, when the runtime supports them. */
  static final String[] COMMON_ENCODINGS = {
    "UTF-8",
    "UTF-16LE",
    "UTF-16BE",
    "ISO-8859-1",
    "ISO-8859-15",
    "windows-1252",
    "US-ASCII",
    "windows-1251",
    "KOI8-R",
    "GB18030",
    "Big5",
    "Shift_JIS",
    "EUC-JP",
    "EUC-KR"
  };

  /** Widget data of the items the menus list after their commands. */
  private static final String LISTED = "qlogg.listed";

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
   * Rebuilds the menu bar and the views after the event that changed the options, which may come
   * from a menu item or a view about to be replaced.
   */
  @Override
  public void optionsChanged() {
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
    if (shortcuts != null && !shortcuts.shell().isDisposed()) {
      shortcuts.shell().dispose();
    }
    if (canvas != null) {
      showViews((DocumentController) canvas.document());
    }
  }

  @Override
  public void showOptions() {
    OptionsDialog dialog = new OptionsDialog(shell, controller);
    dialog.open();
    remember(dialog.shell(), "options");
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

  /** Lists the recent files in File > Open Recent and the favorites in the Favorites menu. */
  private void syncListedMenus(DocumentController document) {
    if (controller == null) {
      return;
    }
    if (recentMenu != null && !recentMenu.isDisposed()) {
      clearListed(recentMenu);
      List<String> recent = controller.preferences().state().recentFiles();
      int at = 0;
      for (String path : recent) {
        listedItem(recentMenu, at++, path);
      }
      if (recent.isEmpty()) {
        MenuItem none = new MenuItem(recentMenu, SWT.PUSH, at++);
        none.setText(UiText.RECENT_NONE.text());
        none.setEnabled(false);
        none.setData(LISTED);
      }
      new MenuItem(recentMenu, SWT.SEPARATOR, at).setData(LISTED);
    }
    if (favoritesMenu != null && !favoritesMenu.isDisposed()) {
      clearListed(favoritesMenu);
      boolean file = document != null && !document.document().inMemory();
      boolean favorite = controller.isFavorite(document);
      for (MenuItem item : favoritesMenu.getItems()) {
        if (item.getData() == CommandId.ADD_FAVORITE) {
          item.setEnabled(file && !favorite);
        } else if (item.getData() == CommandId.REMOVE_FAVORITE) {
          item.setEnabled(favorite);
        }
      }
      new MenuItem(favoritesMenu, SWT.SEPARATOR).setData(LISTED);
      List<String> favorites = controller.preferences().options().favorites();
      for (String path : favorites) {
        listedItem(favoritesMenu, favoritesMenu.getItemCount(), path);
      }
      if (favorites.isEmpty()) {
        MenuItem none = new MenuItem(favoritesMenu, SWT.PUSH);
        none.setText(UiText.FAVORITES_NONE.text());
        none.setEnabled(false);
        none.setData(LISTED);
      }
    }
  }

  private static void clearListed(Menu menu) {
    for (MenuItem item : menu.getItems()) {
      if (LISTED.equals(item.getData()) || item.getData() instanceof Path) {
        item.dispose();
      }
    }
  }

  private void listedItem(Menu menu, int index, final String path) {
    MenuItem item = new MenuItem(menu, SWT.PUSH, index);
    item.setText(path.replace("&", "&&"));
    item.setData(LISTED);
    item.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            controller.openListed(path);
          }
        });
  }

  /**
   * Lists the encodings after Auto, the shown file's forced one checked, and checks Auto while its
   * encoding is detected. Every other charset the runtime supports sits in the Other cascade.
   */
  private void syncEncodingMenu(DocumentController document) {
    if (encodingMenu == null || encodingMenu.isDisposed() || controller == null) {
      return;
    }
    boolean file = document != null && !document.document().inMemory();
    String forced =
        file && document.state().encodingForced() ? document.state().encodingName() : "";
    MenuItem auto = checkItems.get(CommandId.ENCODING_AUTO);
    if (auto != null && !auto.isDisposed()) {
      auto.setEnabled(file);
      auto.setSelection(file && forced.isEmpty());
    }
    for (MenuItem item : encodingMenu.getItems()) {
      if (LISTED.equals(item.getData()) || item.getData() instanceof Charset) {
        item.dispose();
      }
    }
    new MenuItem(encodingMenu, SWT.SEPARATOR).setData(LISTED);
    List<Charset> common = new ArrayList<Charset>();
    for (String name : COMMON_ENCODINGS) {
      Charset charset = Options.charset(name);
      if (charset != null) {
        common.add(charset);
        encodingItem(encodingMenu, charset, file, forced);
      }
    }
    MenuItem other = new MenuItem(encodingMenu, SWT.CASCADE);
    other.setText(UiText.MENU_ENCODING_OTHER.text());
    other.setData(LISTED);
    other.setEnabled(file);
    Menu all = new Menu(shell, SWT.DROP_DOWN);
    other.setMenu(all);
    for (Charset charset : Charset.availableCharsets().values()) {
      if (!common.contains(charset)) {
        encodingItem(all, charset, file, forced);
      }
    }
  }

  private void encodingItem(Menu menu, final Charset charset, boolean enabled, String forced) {
    MenuItem item = new MenuItem(menu, SWT.RADIO);
    item.setText(charset.name());
    item.setData(charset);
    item.setEnabled(enabled);
    item.setSelection(charset.name().equals(forced));
    item.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            DocumentController shown = controller.activeDocument();
            if (((MenuItem) event.widget).getSelection() && shown != null) {
              controller.setEncoding(shown, charset);
            }
          }
        });
  }

  /**
   * Returns the Encoding menu.
   *
   * @return the menu, filled when it shows
   */
  Menu encodingMenu() {
    return encodingMenu;
  }

  /**
   * Returns the Favorites menu.
   *
   * @return the menu, filled when it shows
   */
  Menu favoritesMenu() {
    return favoritesMenu;
  }

  /**
   * Returns File > Open Recent.
   *
   * @return the cascade, filled when the File menu shows
   */
  Menu recentMenu() {
    return recentMenu;
  }

  // User guide.

  @Override
  public boolean showGuide(Path file) {
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
}
