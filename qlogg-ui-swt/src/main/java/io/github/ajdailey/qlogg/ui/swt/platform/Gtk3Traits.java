package io.github.ajdailey.qlogg.ui.swt.platform;

/** The GTK 3 answers of {@link PlatformTraits}. */
final class Gtk3Traits extends GtkTraits {
  /** {@link GtkX11Stacking} on X11; under Wayland the window manager's menu offers the setting. */
  @Override
  public WindowStacking stacking() {
    return "x11".equals(System.getProperty(GDK_BACKEND))
        ? new GtkX11Stacking()
        : new WindowManagerStacking();
  }

  @Override
  public ToolItemSizing toolItemSizing() {
    return NativeToolItemSizing.INSTANCE;
  }

  @Override
  public MenuBehavior menuBehavior() {
    return new Gtk3MenuBehavior();
  }

  @Override
  public WindowPlacement windowPlacement() {
    return new GtkWindowPlacement();
  }

  @Override
  public GuideDisplay guideDisplay() {
    return new BrowserGuideDisplay();
  }
}
