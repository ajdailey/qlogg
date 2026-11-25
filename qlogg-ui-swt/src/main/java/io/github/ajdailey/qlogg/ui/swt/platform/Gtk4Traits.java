package io.github.ajdailey.qlogg.ui.swt.platform;

/** The GTK 4 answers of {@link PlatformTraits}. */
final class Gtk4Traits extends GtkTraits {
  /** The window manager's menu offers the setting. */
  @Override
  public WindowStacking stacking() {
    return WindowStacking.WINDOW_MANAGER;
  }

  @Override
  public ToolItemSizing toolItemSizing() {
    return Gtk4ToolItemSizing.create();
  }

  @Override
  public MenuBehavior menus() {
    return new Gtk4MenuBehavior();
  }

  @Override
  public WindowPlacement placement() {
    return new Gtk4WindowPlacement();
  }

  @Override
  public GuideDisplay guideDisplay() {
    return new NoticeGuideDisplay();
  }
}
