package io.github.ajdailey.qlogg.ui.swt.platform;

/** The GTK 3 answers of {@link PlatformTraits}. */
final class Gtk3Traits extends GtkTraits {
  /** {@link GtkX11Stacking} on X11; under Wayland the window manager's menu offers the setting. */
  @Override
  public WindowStacking stacking() {
    return "x11".equals(System.getProperty(GDK_BACKEND))
        ? new GtkX11Stacking()
        : WindowStacking.WINDOW_MANAGER;
  }

  @Override
  public ToolItemSizing toolItemSizing() {
    return NativeToolItemSizing.INSTANCE;
  }

  @Override
  public MenuBehavior menus() {
    return new Gtk3MenuBehavior();
  }

  @Override
  public WindowPlacement placement() {
    return new GtkWindowPlacement();
  }

  @Override
  public GuideDisplay guideDisplay() {
    return new BrowserGuideDisplay();
  }
}
