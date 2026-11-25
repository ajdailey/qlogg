package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.widgets.Shell;

/** GTK 3: one placement from the computed trim, with no location under Wayland. */
final class GtkWindowPlacement implements WindowPlacement {
  @Override
  public void place(Shell shell, Placer placer) {
    TrimPlacement.place(
        shell, placer, !"wayland".equals(System.getProperty(PlatformTraits.GDK_BACKEND)));
  }

  @Override
  public void afterOpen(Shell shell, boolean maximized) {}
}
