package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/** The cocoa answers of {@link PlatformTraits}. */
final class CocoaTraits implements PlatformTraits {
  @Override
  public String name() {
    return "cocoa";
  }

  @Override
  public String toolkitVersion() {
    return "";
  }

  @Override
  public String monospaceFamily() {
    return "Menlo";
  }

  @Override
  public int monospaceSize() {
    return 12;
  }

  @Override
  public boolean macKeys() {
    return true;
  }

  @Override
  public int busyCursor() {
    return SWT.CURSOR_WAIT;
  }

  @Override
  public DialogColors dialogColors(Display display) {
    return new NativeDialogColors();
  }

  @Override
  public ChromeColors chromeColors(Display display) {
    return new NativeChromeColors();
  }

  @Override
  public DialogTabs dialogTabs(Composite parent) {
    return new NativeDialogTabs(parent);
  }

  @Override
  public WindowStacking stacking() {
    return new CocoaStacking();
  }

  @Override
  public ToolItemSizing toolItemSizing() {
    return NativeToolItemSizing.INSTANCE;
  }

  @Override
  public ThemeFollow themeFollow(Display display) {
    return new CocoaThemeFollow(display);
  }

  @Override
  public FullScreen fullScreen(Shell shell, UiDispatcher ui) {
    return new CocoaFullScreen(shell, ui);
  }

  @Override
  public MenuBehavior menus() {
    return new CocoaMenuBehavior();
  }

  @Override
  public LaunchDocuments launchDocuments() {
    return new CocoaLaunchDocuments();
  }

  @Override
  public AccessibleLabels accessibleLabels() {
    return new CocoaAccessibleLabels();
  }

  @Override
  public WindowPlacement windowPlacement() {
    return new NativeWindowPlacement();
  }

  @Override
  public GuideDisplay guideDisplay() {
    return new BrowserGuideDisplay();
  }

  @Override
  public Color shownBackground(Control source) {
    return source.getBackground();
  }
}
