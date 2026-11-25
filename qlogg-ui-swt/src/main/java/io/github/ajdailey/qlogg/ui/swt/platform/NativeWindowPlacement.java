package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.widgets.Shell;

/** cocoa: one placement from the computed trim. */
final class NativeWindowPlacement implements WindowPlacement {
  @Override
  public void place(Shell shell, Placer placer) {
    TrimPlacement.place(shell, placer, true);
  }

  @Override
  public void afterOpen(Shell shell, boolean maximized) {}
}
