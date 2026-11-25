package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.widgets.Shell;

/**
 * GTK 4: the GTK 3 placement, and a maximize repeated after {@code open}, since GTK 4 drops one
 * requested before the shell maps.
 */
final class Gtk4WindowPlacement implements WindowPlacement {
  private final GtkWindowPlacement placement = new GtkWindowPlacement();

  @Override
  public void place(Shell shell, Placer placer) {
    placement.place(shell, placer);
  }

  @Override
  public void afterOpen(Shell shell, boolean maximized) {
    if (maximized && !shell.getMaximized()) {
      shell.setMaximized(true);
    }
  }
}
