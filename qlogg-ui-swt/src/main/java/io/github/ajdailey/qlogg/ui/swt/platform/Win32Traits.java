package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;

/** The win32 answers of {@link PlatformTraits}. */
final class Win32Traits implements PlatformTraits {
  @Override
  public String name() {
    return "win32";
  }

  @Override
  public String toolkitVersion() {
    return "";
  }

  @Override
  public String monospaceFamily() {
    return "Consolas";
  }

  @Override
  public int monospaceSize() {
    return 10;
  }

  @Override
  public boolean macKeys() {
    return false;
  }

  @Override
  public int busyCursor() {
    return SWT.CURSOR_APPSTARTING;
  }

  @Override
  public DialogColors dialogColors() {
    return Win32DialogColors.INSTANCE;
  }

  @Override
  public ChromeColors chromeColors(Display display) {
    return new Win32ChromeColors(display);
  }

  @Override
  public DialogTabs dialogTabs(Composite parent) {
    return DialogColors.nativeDark(parent.getDisplay())
        ? new Win32DialogTabs(parent)
        : new NativeDialogTabs(parent);
  }

  @Override
  public CellImages cellImages(Table table, int column) {
    return new Win32CellImages(table, column);
  }

  @Override
  public WindowStacking stacking() {
    return new Win32Stacking();
  }

  @Override
  public ToolItemSizing toolItemSizing() {
    return NativeToolItemSizing.INSTANCE;
  }

  @Override
  public ThemeFollow themeFollow(Display display) {
    return new Win32ThemeFollow(display);
  }

  @Override
  public FullScreen fullScreen(Shell shell, UiDispatcher ui) {
    return new NativeFullScreen(shell);
  }

  @Override
  public MenuBehavior menuBehavior() {
    return new Win32MenuBehavior();
  }

  @Override
  public LaunchDocuments launchDocuments() {
    return new NativeLaunchDocuments();
  }

  @Override
  public AccessibleLabels accessibleLabels() {
    return new NativeAccessibleLabels();
  }

  @Override
  public ComboListSelection comboListSelection() {
    return new Win32ComboListSelection();
  }

  @Override
  public ListSelection listSelection() {
    return new Win32ListSelection();
  }

  @Override
  public WindowPlacement windowPlacement() {
    return new Win32WindowPlacement();
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
