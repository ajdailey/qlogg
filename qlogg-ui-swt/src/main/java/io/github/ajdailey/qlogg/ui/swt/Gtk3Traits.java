package io.github.ajdailey.qlogg.ui.swt;

/** The GTK 3 answers of {@link PlatformTraits}. */
final class Gtk3Traits extends GtkTraits {
  @Override
  public boolean opensBrowser() {
    return true;
  }

  /** {@link GtkX11Stacking} on X11; under Wayland the window manager's menu offers the setting. */
  @Override
  public WindowStacking stacking() {
    return "x11".equals(System.getProperty(MainWindow.GDK_BACKEND))
        ? new GtkX11Stacking()
        : WindowStacking.WINDOW_MANAGER;
  }

  @Override
  public ToolItemSizing toolItemSizing() {
    return NativeToolItemSizing.INSTANCE;
  }
}
