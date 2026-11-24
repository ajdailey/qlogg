package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.widgets.Shell;

/**
 * Full screen on win32 and GTK: the window keeps its menu bar and only {@code Shell.setFullScreen}
 * changes the state. The state is the last request, set before {@code setFullScreen} enters and
 * cleared after it leaves, so the size events SWT sends during the call already count as full
 * screen.
 */
final class NativeFullScreen implements FullScreen {
  private final Shell shell;
  private boolean requested;
  private boolean maximized;

  NativeFullScreen(Shell shell) {
    this.shell = shell;
  }

  @Override
  public boolean on() {
    return requested;
  }

  @Override
  public boolean shown() {
    return requested;
  }

  @Override
  public void set(boolean value) {
    if (shell.isDisposed() || value == requested) {
      return;
    }
    if (value) {
      maximized = shell.getMaximized();
      requested = true;
      shell.setFullScreen(true);
    } else {
      shell.setFullScreen(false);
      requested = false;
    }
  }

  @Override
  public void track() {
    if (!requested) {
      maximized = shell.getMaximized();
    }
  }

  @Override
  public boolean maximized() {
    return requested ? maximized : shell.getMaximized();
  }
}
