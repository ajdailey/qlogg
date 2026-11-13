package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.MenuTable;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.CommandStates;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.controller.TabDataListener;
import io.github.ajdailey.qlogg.app.controller.TabDataState;
import io.github.ajdailey.qlogg.app.controller.TabTips;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.core.model.Rgb;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * The main window's tab row: one {@link CTabItem} per open document, its data the document's {@link
 * DocumentController}, with {@link TabStripButtons} at its right end. The row shows while any tab
 * exists. Selection, close buttons, middle clicks, the context menu and drags go to the {@link
 * ApplicationController}; a drag moves a tab within the row or, released off it, to another main
 * window or a new one ({@link #dropTab}). Each tab shows its {@link TabDataState} as a dot from
 * {@link TabDots} and in its tool tip. Created and used on the UI thread.
 */
final class TabStrip {
  /** Shell data key holding the shell's {@code TabStrip}. */
  private static final String WINDOW = "qlogg.mainWindow";

  private final Shell shell;
  private final CTabFolder tabs;
  private final TabStripButtons buttons;
  private ApplicationController controller;
  private CommandStates states;
  private CTabItem dragged;
  private boolean movingTab;
  private Menu tabMenu;
  // Tab close: the controller picks the tab shown next.
  private boolean removingTab;
  private PointerProbe pointer =
      new PointerProbe() {
        @Override
        public Control controlAt(Point point) {
          return shell.getDisplay().getCursorControl();
        }
      };
  private TabDots tabDots;
  private TabDataListener tabDataListener;

  /**
   * Creates the row, hidden and excluded from the shell's layout, as the shell's next child.
   *
   * @param shell the main window's shell, laid out by a {@code GridLayout}
   * @param palette the palette the buttons start with
   * @param open runs Open
   * @param close runs Close
   */
  TabStrip(Shell shell, Palette palette, Runnable open, Runnable close) {
    this.shell = shell;
    shell.setData(WINDOW, this);
    tabs = new CTabFolder(shell, SWT.TOP | SWT.FLAT);
    GridData tabsData = new GridData(SWT.FILL, SWT.TOP, true, false);
    // The folder adds its tab row as trim around the hint, which is the empty client area.
    tabsData.heightHint = 1;
    tabsData.exclude = true;
    tabs.setLayoutData(tabsData);
    tabs.setVisible(false);
    buttons = new TabStripButtons(tabs, palette, open, close);
  }

  /**
   * Sends tab selection, close buttons, middle clicks, the context menu and drags to the
   * controller. Closing the last tab leaves an empty window unless the window closes with it.
   *
   * @param application the window's controller
   * @param commandStates enables the context menu's items
   */
  void attach(ApplicationController application, CommandStates commandStates) {
    controller = application;
    states = commandStates;
    tabs.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!movingTab
                && !removingTab
                && event.item instanceof CTabItem item
                && !item.isDisposed()) {
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
    CommandStates.TabItems state = states.tabItems(document);
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
      case TAB_COPY_PATH -> controller.pathCommands().copyPath(document);
      case TAB_COPY_FILE_NAME -> controller.pathCommands().copyFileName(document);
      case TAB_OPEN_FOLDER -> controller.pathCommands().openContainingFolder(document);
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
   * Ends a tab drag released off this row. Over another main window the tab moves there: before the
   * tab under the point, else after the last tab. Outside every QLogg shell and outside this
   * window's bounds it moves to a new window with its top-left corner at the point. A release
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
        || !(other.getData(WINDOW) instanceof TabStrip target)
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

  /**
   * Adds a tab and shows the row.
   *
   * @param document the tab's controller
   * @param index the position; past the last tab appends
   */
  void add(DocumentController document, int index) {
    newTab(document, index);
    showTabs();
  }

  private CTabItem newTab(DocumentController document, int index) {
    CTabItem item = new CTabItem(tabs, SWT.CLOSE, Math.min(index, tabs.getItemCount()));
    item.setData(document);
    showTabTitle(item, document);
    showTabData(item);
    return item;
  }

  /**
   * Removes a document's tab and hides the row when it was the last. Disposing the selected item
   * makes {@code CTabFolder} select a neighbor and send {@code SWT.Selection}; the tab listener
   * ignores it, so a Close All does not show each remaining tab in turn and the controller picks
   * the tab shown next.
   *
   * @param document the tab's controller
   */
  void remove(DocumentController document) {
    CTabItem item = tabOf(document);
    if (item != null) {
      removingTab = true;
      try {
        item.dispose();
      } finally {
        removingTab = false;
      }
    }
    showTabs();
  }

  /**
   * Selects a document's tab, when it has one.
   *
   * @param document the tab's controller
   */
  void select(DocumentController document) {
    CTabItem item = tabOf(document);
    if (item != null) {
      tabs.setSelection(item);
    }
  }

  /**
   * Gives a document's tab to its replacement and shows the replacement's data.
   *
   * @param closed the tab's controller
   * @param replacement the controller the tab shows from now on
   */
  void replace(DocumentController closed, DocumentController replacement) {
    CTabItem item = tabOf(closed);
    if (item != null) {
      item.setData(replacement);
      // Tab data dot.
      showTabData(item);
    }
  }

  /**
   * Shows a document's tab title again.
   *
   * @param document the tab's controller
   */
  void showTitle(DocumentController document) {
    CTabItem item = tabOf(document);
    if (item != null) {
      showTabTitle(item, document);
    }
  }

  /** Sets a tab's text; a {@code CTabItem} takes a single {@code &} as a mnemonic. */
  private void showTabTitle(CTabItem item, DocumentController document) {
    item.setText(controller.tabTitle(document).replace("&", "&&"));
  }

  // Tab data dot.

  /**
   * Shows each tab's {@link TabDataState} as a dot from {@link TabDots}; the dots and the listener
   * go with the shell.
   *
   * @param application the window's controller
   */
  void listenToTabData(final ApplicationController application) {
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
    showData();
  }

  /** Shows every tab's dot and tool tip again; the time sync group is part of the tool tip. */
  void showData() {
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

  /**
   * Recolors the row: creates the palette's dots, moves every tab to them and disposes the old
   * ones, then colors the folder and the buttons.
   *
   * @param next the palette
   */
  void setPalette(Palette next) {
    TabDots old = tabDots;
    tabDots = new TabDots(shell.getDisplay(), next);
    showData();
    if (old != null) {
      old.dispose();
    }
    colorTabs(tabs, next);
    // Tab strip buttons.
    buttons.setPalette(next);
  }

  /**
   * Returns the dots the tabs show.
   *
   * @return the current palette's images, or null before the first {@link #setPalette}
   */
  TabDots tabDots() {
    return tabDots;
  }

  private CTabItem tabOf(DocumentController document) {
    for (CTabItem item : tabs.getItems()) {
      if (item.getData() == document) {
        return item;
      }
    }
    return null;
  }

  /** Shows the row while a tab exists. */
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
   * Returns the folder.
   *
   * @return the folder; each item's data is its {@link DocumentController}
   */
  CTabFolder tabs() {
    return tabs;
  }

  /**
   * Returns the buttons at the right end of the row.
   *
   * @return Open, the tab list and Close
   */
  TabStripButtons buttons() {
    return buttons;
  }

  /**
   * Colors a tab folder from the palette. {@code CTabFolder} copies the Display's list colors when
   * created, and the Display reads them once, so they outlast a theme change. The selection
   * background shows only while focus is inside the folder; elsewhere the selected tab has the
   * strip's background and keeps the selection foreground, so that foreground must read on both.
   * {@link CocoaChrome} leaves a folder colored here alone.
   *
   * @param folder the folder
   * @param next the palette
   */
  static void colorTabs(CTabFolder folder, Palette next) {
    folder.setData(CocoaChrome.PALETTE_COLORED, Boolean.TRUE);
    folder.setBackground(color(next.gutter()));
    folder.setForeground(color(next.text()));
    folder.setSelectionBackground(color(next.background()));
    folder.setSelectionForeground(color(next.text()));
  }

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }
}
