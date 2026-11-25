package io.github.ajdailey.qlogg.ui.swt.platform;

/** The GTK 4 answers of {@link PlatformTraits}. */
final class Gtk4Traits extends GtkTraits {
  /** The window manager's menu offers the setting. */
  @Override
  public WindowStacking stacking() {
    return new WindowManagerStacking();
  }

  @Override
  public ToolItemSizing toolItemSizing() {
    return Gtk4ToolItemSizing.create();
  }

  @Override
  public MenuBehavior menuBehavior() {
    return new Gtk4MenuBehavior();
  }

  @Override
  public WindowPlacement windowPlacement() {
    return new Gtk4WindowPlacement();
  }

  @Override
  public GuideDisplay guideDisplay() {
    return new NoticeGuideDisplay();
  }
}
