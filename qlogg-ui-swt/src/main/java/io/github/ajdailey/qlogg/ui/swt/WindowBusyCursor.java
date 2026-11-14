package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.view.BusyCounter;
import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * A main window's busy cursor, {@link PlatformTraits#busyCursor}, driven by its {@link
 * BusyCounter}. While busy it is set on the main shell and on every shell the main shell owns at
 * any depth; a control with a cursor of its own keeps it. While busy a Display {@code SWT.Show}
 * filter sets it on an owned shell that opens meanwhile; idle removes the filter and clears every
 * shell's cursor. Disposed shells are skipped. Confined to the UI thread.
 */
final class WindowBusyCursor implements BusyCounter.Indicator {
  private final Shell shell;
  private final Listener shown =
      new Listener() {
        @Override
        public void handleEvent(Event event) {
          if (event.widget instanceof Shell owned
              && owned != shell
              && !owned.isDisposed()
              && owns(owned)) {
            owned.setCursor(cursor());
          }
        }
      };

  /**
   * Creates the cursor, idle; disposing the shell removes the filter.
   *
   * @param shell the main window's shell
   */
  WindowBusyCursor(Shell shell) {
    this.shell = shell;
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            watch(false);
          }
        });
  }

  @Override
  public void busyChanged(boolean active) {
    if (shell.isDisposed()) {
      return;
    }
    Cursor cursor = active ? cursor() : null;
    shell.setCursor(cursor);
    for (Shell owned : shell.getShells()) {
      if (!owned.isDisposed()) {
        owned.setCursor(cursor);
      }
    }
    watch(active);
  }

  private Cursor cursor() {
    return shell.getDisplay().getSystemCursor(PlatformTraits.PLATFORM.busyCursor());
  }

  /** Reports whether the main shell owns the shell, at any depth. */
  private boolean owns(Shell owned) {
    Composite parent = owned.getParent();
    while (parent != null) {
      if (parent == shell) {
        return true;
      }
      parent = parent.getParent();
    }
    return false;
  }

  /** Adds the Show filter while busy and removes it when idle or disposed. */
  private void watch(boolean on) {
    Display display = shell.getDisplay();
    if (display.isDisposed()) {
      return;
    }
    if (on) {
      display.addFilter(SWT.Show, shown);
    } else {
      display.removeFilter(SWT.Show, shown);
    }
  }
}
