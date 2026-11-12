package io.github.ajdailey.qlogg.ui.swt;

/** The GTK 4 answers of {@link PlatformTraits}. */
final class Gtk4Traits extends GtkTraits {
  @Override
  public boolean opensBrowser() {
    return false;
  }

  /** The window manager's menu offers the setting. */
  @Override
  public WindowStacking stacking() {
    return WindowStacking.WINDOW_MANAGER;
  }

  @Override
  public ToolItemSizing toolItemSizing() {
    return Gtk4ToolItemSizing.create();
  }
}
