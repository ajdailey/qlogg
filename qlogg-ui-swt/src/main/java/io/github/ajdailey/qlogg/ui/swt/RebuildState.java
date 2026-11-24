package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import java.nio.file.Path;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

/**
 * The state of a main window that only its widgets hold, read before a rebuild disposes it and
 * applied to the window that replaces it: placement, minimized, full screen, Always on Top, the
 * focused view, the active shell, and the open tool windows with their bounds, the scratchpad's
 * tabs, the Show Log text, the shortcuts filter and the guide's location. The controllers hold
 * everything else. Confined to the UI thread.
 */
final class RebuildState {
  /** Where the focus was in the main window. */
  enum Focus {
    MAIN,
    FILTERED,
    SEARCH,
    QUICK_FIND
  }

  /** An open tool window's shell and bounds. */
  private record Tool(Shell shell, Rectangle bounds) {
    static Tool of(Shell shell) {
      return shell == null || shell.isDisposed() ? null : new Tool(shell, shell.getBounds());
    }
  }

  private final Shell shell;
  private final boolean visible;
  private final boolean minimized;
  private final boolean maximized;
  private final boolean fullScreen;
  private final WindowGeometry.Request normal;
  private final Rectangle bounds;
  private final boolean onTop;
  private final Focus focus;
  private final Shell active;
  private final Tool marks;
  private final Tool multiSearch;
  private final Tool folderSearch;
  private final Tool scratchpad;
  private final ScratchpadWindow.Tabs scratchTabs;
  private final Tool log;
  private final String logText;
  private final Tool shortcuts;
  private final String shortcutsFilter;
  private final Tool guide;
  private final Path guideFile;
  private final String guideAt;
  private Shell reactivate;

  /**
   * Reads a window's state and lets a running Search in Folder outlive its window.
   *
   * @param window the window a rebuild replaces
   * @param controller its controller
   */
  RebuildState(MainWindow window, ApplicationController controller) {
    shell = window.shell();
    visible = shell.getVisible();
    minimized = shell.getMinimized();
    maximized = window.windowMaximized();
    fullScreen = window.fullScreen();
    normal = window.windowBounds();
    bounds = shell.getBounds();
    onTop = controller.alwaysOnTop().shown();
    focus = focusOf(window, shell.getDisplay().getFocusControl());
    active = shell.getDisplay().getActiveShell();
    marks = window.marks() == null ? null : Tool.of(window.marks().shell());
    multiSearch = window.multiSearch() == null ? null : Tool.of(window.multiSearch().shell());
    folderSearch = window.folderSearch() == null ? null : Tool.of(window.folderSearch().shell());
    if (folderSearch != null) {
      window.folderSearch().keepSearch();
    }
    scratchpad = window.scratchpad() == null ? null : Tool.of(window.scratchpad().shell());
    scratchTabs = scratchpad == null ? null : window.scratchpad().tabsContent();
    log = window.logWindow() == null ? null : Tool.of(window.logShell());
    logText = log == null ? null : window.logWindow().content();
    shortcuts = window.shortcuts() == null ? null : Tool.of(window.shortcuts().shell());
    shortcutsFilter = shortcuts == null ? null : window.shortcuts().filter().getText();
    guide = window.guideWindow() == null ? null : Tool.of(window.guideWindow().shell());
    guideFile = guide == null ? null : window.guideWindow().file();
    String at = guide == null ? null : window.guideWindow().location();
    guideAt = at != null && at.startsWith("file:") ? at : null;
  }

  /**
   * Places a new window where the old one was; call before its tabs are added.
   *
   * @param next the new window, not yet open
   */
  void place(MainWindow next) {
    if (maximized || fullScreen) {
      next.place(normal, null);
      next.shell().setMaximized(maximized);
    } else {
      next.shell().setBounds(bounds);
    }
  }

  /**
   * Opens the new window when the old one showed, with its full screen, Always on Top and tool
   * windows, minimized when the old one was, and activates the shell that was active: the focused
   * part of the main window, a tool window, or another window; a minimized window activates only
   * another main window.
   *
   * @param next the new window, its tabs added
   */
  void open(MainWindow next) {
    if (!visible) {
      return;
    }
    if (onTop) {
      next.setOnTop(true);
    }
    next.open();
    if (fullScreen) {
      next.setFullScreen(true);
    }
    reopen(next);
    if (minimized) {
      next.shell().setMinimized(true);
      if (active != null && active != shell && !active.isDisposed() && active.getParent() == null) {
        active.setActive();
      }
    } else if (active == shell) {
      switch (focus) {
        case FILTERED -> next.focusView(true);
        case SEARCH -> next.focusSearch();
        case QUICK_FIND -> next.focusQuickFind();
        case MAIN -> next.focusView(false);
      }
      next.shell().setActive();
    } else if (reactivate != null) {
      reactivate.setActive();
    } else if (active != null && !active.isDisposed()) {
      active.setActive();
    }
  }

  /** Reopens the tool windows at their bounds and keeps the one that was active. */
  private void reopen(MainWindow next) {
    if (marks != null) {
      next.showMarks();
      restore(marks, next.marks().shell());
    }
    if (multiSearch != null) {
      next.showMultiSearch();
      restore(multiSearch, next.multiSearch().shell());
    }
    if (folderSearch != null) {
      next.showFolderSearch();
      restore(folderSearch, next.folderSearch().shell());
    }
    if (scratchpad != null) {
      next.showScratchpad(null, false);
      next.scratchpad().restore(scratchTabs);
      restore(scratchpad, next.scratchpad().shell());
    }
    if (log != null) {
      next.showLog(logText);
      restore(log, next.logShell());
    }
    if (shortcuts != null) {
      next.showKeyboardShortcuts();
      next.shortcuts().filter().setText(shortcutsFilter);
      restore(shortcuts, next.shortcuts().shell());
    }
    if (guide != null && next.showGuideAt(guideFile, guideAt)) {
      restore(guide, next.guideWindow().shell());
    }
  }

  private void restore(Tool tool, Shell reopened) {
    if (!reopened.isDisposed()) {
      reopened.setBounds(tool.bounds());
      if (tool.shell() == active) {
        reactivate = reopened;
      }
    }
  }

  /**
   * Reports where the focus is in a main window.
   *
   * @param window the window
   * @param control the focus control, or null
   * @return the focused part; {@link Focus#MAIN} when the focus is elsewhere
   */
  static Focus focusOf(MainWindow window, Control control) {
    if (control == null) {
      return Focus.MAIN;
    }
    if (window.results() != null && control == window.results().canvas().control()) {
      return Focus.FILTERED;
    }
    if (window.quickFindBar() != null && inside(control, window.quickFindBar().control())) {
      return Focus.QUICK_FIND;
    }
    if (inside(control, window.searchBar().control())) {
      return Focus.SEARCH;
    }
    return Focus.MAIN;
  }

  private static boolean inside(Control control, Control parent) {
    for (Control c = control; c != null; c = c.getParent()) {
      if (c == parent) {
        return true;
      }
    }
    return false;
  }
}
