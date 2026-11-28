package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.ui.swt.platform.MenuBehavior;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * Clears every accelerator of a main window's menu bar and of the application menu ({@link
 * MenuBehavior#applicationMenuItems}) while a log view's two-stroke sequence is pending, and
 * restores them when it ends. A cleared accelerator lets the sequence's second stroke, such as
 * {@code Cmd+M} after {@code Cmd+E}, reach the log view instead of running a menu command; the menu
 * takes a key before the focused control on every platform. Confined to the UI thread.
 */
final class AcceleratorHold {
  private final Shell shell;
  private final Map<MenuItem, Integer> cleared = new HashMap<MenuItem, Integer>();
  private boolean held;

  /**
   * Creates the hold.
   *
   * @param shell the main window
   */
  AcceleratorHold(Shell shell) {
    this.shell = shell;
  }

  /**
   * Returns whether the accelerators are held.
   *
   * @return true between {@link #hold} and {@link #release}
   */
  boolean held() {
    return held;
  }

  /** Clears the accelerators and keeps each to restore; does nothing while held. */
  void hold() {
    if (held) {
      return;
    }
    held = true;
    clear();
  }

  /** Restores every cleared accelerator on an item not disposed; does nothing unless held. */
  void release() {
    if (!held) {
      return;
    }
    held = false;
    for (Map.Entry<MenuItem, Integer> entry : cleared.entrySet()) {
      if (!entry.getKey().isDisposed()) {
        entry.getKey().setAccelerator(entry.getValue());
      }
    }
    cleared.clear();
  }

  /**
   * Clears the accelerators a menu bar build or keymap change set while held, keeping the new ones
   * to restore; a menu bar item left without one restores none. Call after each.
   */
  void menuBarChanged() {
    if (!held || shell.isDisposed()) {
      return;
    }
    Menu bar = shell.getMenuBar();
    if (bar != null) {
      replace(bar);
    }
  }

  private void replace(Menu menu) {
    for (MenuItem item : menu.getItems()) {
      if (item.getAccelerator() != 0) {
        clear(item);
      } else {
        cleared.remove(item);
      }
      if (item.getMenu() != null) {
        replace(item.getMenu());
      }
    }
  }

  private void clear() {
    if (shell.isDisposed()) {
      return;
    }
    Menu bar = shell.getMenuBar();
    if (bar != null) {
      clear(bar);
    }
    for (MenuItem item : MenuBehavior.PLATFORM.applicationMenuItems(shell.getDisplay())) {
      clear(item);
    }
  }

  private void clear(Menu menu) {
    for (MenuItem item : menu.getItems()) {
      clear(item);
      if (item.getMenu() != null) {
        clear(item.getMenu());
      }
    }
  }

  private void clear(MenuItem item) {
    int accelerator = item.getAccelerator();
    if (accelerator != 0) {
      cleared.put(item, accelerator);
      item.setAccelerator(0);
    }
  }
}
