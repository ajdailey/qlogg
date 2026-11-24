package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.ui.swt.platform.DialogColors;
import io.github.ajdailey.qlogg.ui.swt.platform.WindowRebuild;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * The main windows of a Display, each in its {@link MainWindowSlot}, and their rebuild. {@link
 * #rebuild} rebuilds each open window built under the other side of {@link
 * DialogColors#nativeDark}, one window per UI turn, starting on the turn after the call, so a
 * window is never disposed inside its own menu or key handler; switching back before a window's
 * turn leaves it alone. A window waits while it owns a visible modal shell, a visible modeless
 * window that {@link #holdWhile} marks as holding input, a native chooser runs over it ({@link
 * NativeDialogs}) or a menu bar menu shows, and is checked again every {@link #RECHECK_MILLIS} ms:
 * disposing it would dispose the dialog and drop its result. Confined to the UI thread.
 */
public final class WindowViews implements WindowRebuild {
  /** Milliseconds between checks of a window that waits for its dialogs. */
  static final int RECHECK_MILLIS = 250;

  /**
   * Shell data key: a {@link BooleanSupplier} that is true while the shell's disposal loses input.
   */
  private static final String HOLD = WindowViews.class.getName() + ".hold";

  private static final int MODAL = SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL;

  /** A window and the native setting its controls were created under. */
  private static final class Entry {
    final MainWindowSlot slot;
    boolean builtDark;

    Entry(MainWindowSlot slot, boolean builtDark) {
      this.slot = slot;
      this.builtDark = builtDark;
    }
  }

  private final Display display;
  private final UiDispatcher ui;
  private final List<Entry> entries = new ArrayList<Entry>();
  private final Runnable step =
      new Runnable() {
        @Override
        public void run() {
          scheduled = false;
          step();
        }
      };
  private final Runnable recheck =
      new Runnable() {
        @Override
        public void run() {
          timer = false;
          schedule();
        }
      };
  private boolean scheduled;
  private boolean timer;
  private int rebuilds;

  /**
   * Creates the set.
   *
   * @param display the Display
   */
  public WindowViews(Display display) {
    this(display, new SwtUiDispatcher(display));
  }

  /**
   * Creates the set over a dispatcher that runs its turns.
   *
   * @param display the Display
   * @param ui the UI-thread dispatcher
   */
  WindowViews(Display display, UiDispatcher ui) {
    this.display = display;
    this.ui = ui;
  }

  /**
   * Puts a new window, built under the native setting now ({@link DialogColors#nativeDark}), in a
   * slot; attach the slot to its controller next.
   *
   * @param window the window, not yet attached
   * @return the slot
   */
  public MainWindowSlot add(MainWindow window) {
    for (Entry old : new ArrayList<Entry>(entries)) {
      if (!old.slot.open()) {
        entries.remove(old);
      }
    }
    MainWindowSlot slot = new MainWindowSlot(window);
    entries.add(new Entry(slot, DialogColors.nativeDark(display)));
    return slot;
  }

  /**
   * Reports whether any main window is open. A rebuilt window counts from before the old one is
   * disposed.
   *
   * @return false once every window is disposed
   */
  public boolean anyOpen() {
    for (Entry e : entries) {
      if (e.slot.open()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void rebuild() {
    schedule();
  }

  /**
   * Counts the windows rebuilt so far.
   *
   * @return the count
   */
  int rebuilds() {
    return rebuilds;
  }

  /**
   * Reports whether an open window still waits for its rebuild.
   *
   * @return true while one was built under the other native setting
   */
  boolean pending() {
    boolean dark = DialogColors.nativeDark(display);
    for (Entry e : entries) {
      if (e.slot.open() && e.builtDark != dark) {
        return true;
      }
    }
    return false;
  }

  private void schedule() {
    if (!scheduled && !display.isDisposed()) {
      scheduled = true;
      ui.asyncExec(step);
    }
  }

  /**
   * Rebuilds the first open window built under the other native setting that is free, and schedules
   * the next turn; a window that waits is checked again after {@link #RECHECK_MILLIS}. A hidden
   * window, or one whose controller shuts down, takes the setting without a rebuild.
   */
  private void step() {
    boolean dark = DialogColors.nativeDark(display);
    boolean waiting = false;
    for (Entry e : new ArrayList<Entry>(entries)) {
      if (!e.slot.open()) {
        entries.remove(e);
      } else if (e.builtDark != dark) {
        if (!e.slot.window().shell().getVisible() || e.slot.closing()) {
          e.builtDark = dark;
        } else if (busy(e.slot.window().shell())) {
          waiting = true;
        } else {
          e.builtDark = dark;
          e.slot.rebuild();
          rebuilds++;
          schedule();
          return;
        }
      }
    }
    if (waiting && !timer) {
      timer = true;
      ui.timerExec(RECHECK_MILLIS, recheck);
    }
  }

  /**
   * Marks a modeless window whose disposal loses the user's input while a condition holds, so its
   * main window waits for a rebuild.
   *
   * @param shell the window
   * @param holding true while closing the window would lose input; read on the UI thread
   */
  static void holdWhile(Shell shell, BooleanSupplier holding) {
    shell.setData(HOLD, holding);
  }

  /**
   * Reports whether a main window shows something its disposal would break: a visible modal shell
   * it owns at any depth, a visible window {@link #holdWhile} marks while it holds input, a native
   * chooser, or a menu bar menu.
   *
   * @param shell the main window's shell
   * @return true while the window must wait
   */
  static boolean busy(Shell shell) {
    if (NativeDialogs.openOver(shell)) {
      return true;
    }
    for (Shell owned : shell.getShells()) {
      if (owned.isDisposed() || !owned.isVisible()) {
        continue;
      }
      if ((owned.getStyle() & MODAL) != 0
          || owned.getData(HOLD) instanceof BooleanSupplier holding && holding.getAsBoolean()) {
        return true;
      }
    }
    Menu bar = shell.getMenuBar();
    if (bar != null) {
      for (MenuItem item : bar.getItems()) {
        if (item.getMenu() != null && item.getMenu().isVisible()) {
          return true;
        }
      }
    }
    return false;
  }
}
